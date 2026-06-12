package com.thievestrap

import android.os.Build

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.location.Location
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class MonitorService : Service() {

    private val CHANNEL_ID = "tt_channel"
    private val NOTIF_ID = 1
    private val TAG = "ThievesTrap"

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null
    private val handler = Handler(Looper.getMainLooper())

    private var unlockReceiver: BroadcastReceiver? = null
    private var simReceiver: BroadcastReceiver? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var silentReceiver: BroadcastReceiver? = null

    private var screenOnCount = 0
    private var pingRunnable: Runnable? = null
    private var currentPingInterval = 5
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var sensorMgr: android.hardware.SensorManager? = null
    private var accel: android.hardware.Sensor? = null
    private val locationHistory = ArrayDeque<String>(5)
    private val lastAlertTimes = mutableMapOf<String, Long>()

    // Silent grace period state
    private var silentGraceHandler: Handler? = null
    private var silentGraceRunnable: Runnable? = null
    private var silentGraceCancelReceiver: BroadcastReceiver? = null
    private val SILENT_GRACE_NOTIF_ID = 8001
    private val SILENT_GRACE_CHAN_ID = "tt_silent_grace"

    // v2.7.7: SIM trap — network state listener for "wait for network" alert
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun canSendAlert(key: String, cooldownMs: Long = 60_000L): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAlertTimes[key] ?: 0L
        return if (now - last > cooldownMs) { lastAlertTimes[key] = now; true } else false
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                lastLocation = r.lastLocation
                r.lastLocation?.let {
                    addToHistory(it)
                    prefs().edit()
                        .putFloat("last_lat", it.latitude.toFloat())
                        .putFloat("last_lng", it.longitude.toFloat())
                        .putString("last_location_str",
                            "Map: https://maps.google.com/?q=${it.latitude},${it.longitude}")
                        .apply()
                }
            }
        }
        createChannel()
        createSilentGraceChannel()
        cacheDeviceIdentifiers()
        // v2.7.7: If a SIM swap was pending from a previous session, resume
        // the wait-for-network watcher so the trap survives process death.
        if (prefs().getBoolean("SIM_CHANGED_PENDING_ALERT", false)) {
            registerSimTrapNetworkListener()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "TAMPER" -> {
                val reason = intent.getStringExtra("reason") ?: "Unknown"
                val sosTarget = intent.getStringExtra("sos_target")
                if (sosTarget != null) {
                    val sosMsg = "\uD83D\uDEA8 SOS EMERGENCY ALERT \uD83D\uDEA8" +
                        "\n\uD83C\uDD98 I need urgent help!" +
                        "\n\uD83D\uDD0B Battery: ${bat()}% | \uD83D\uDCCD Location: ${locStr()}" +
                        "\nSent via Thieves Trap Security"
                    sms(sosTarget, sosMsg)
                    recordAlert("SOS sent to $sosTarget")
                } else {
                    smsAll(buildFullInfo(s("sms_tamper"), reason))
                }
                return START_STICKY
            }
            "TEST" -> {
                smsAll(buildFullInfo("TEST \u2705", s("sms_test")))
                return START_STICKY
            }
            "UPDATE_NOTIF" -> {
                updateNotif()
                return START_STICKY
            }
            "UPDATE_PING" -> {
                restartPing(intent.getIntExtra("interval", 5))
                return START_STICKY
            }
            "FAILED_ATTEMPTS" -> {
                val count = intent.getIntExtra("count", 1)
                val alertBody = "$count failed unlock attempt(s) detected"
                sendAlert("\u26a0\ufe0f", alertBody)
                return START_STICKY
            }
            "SELFIE_SAVED" -> {
                val path = intent.getStringExtra("path") ?: ""
                val ts = intent.getStringExtra("timestamp") ?: ""
                val count = intent.getIntExtra("count", 1)
                if (path.isNotBlank()) {
                    val photoFile = java.io.File(path)
                    recordAlert("Intruder selfie #$count")
                    prefs().edit().putString("last_selfie_path", path).apply()
                    if (LicenseManager.isPremium(this) && photoFile.exists() &&
                        photoFile.length() > 0) {
                        val captionLine1 = "\uD83D\uDEA8 THIEVES TRAP \u2014 INTRUDER PHOTO #$count"
                        val captionLine2 = "Time: ${ts()} | Battery: ${bat()}%"
                        val captionLine3 = locStr()
                        val caption = "$captionLine1\n$captionLine2\n$captionLine3"
                        TelegramUploader.sendPhoto(this, photoFile, caption)
                        Log.i("TT", "Intruder photo #$count sent to Telegram: ${photoFile.name}")
                    }
                }
                return START_STICKY
            }
            "THEFT_ON" -> {
                if (!LicenseManager.isPremium(this)) return START_STICKY
                val p = prefs()
                p.edit()
                    .putBoolean("theft_mode", true)
                    .putBoolean("selfie_enabled", true)
                    .putBoolean("alert_sim", true)
                    .putBoolean("alert_silent", true)
                    .putBoolean("location_ping", true)
                    .putBoolean("telegram_enabled", true)
                    .apply()
                updateNotif()
                sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                return START_STICKY
            }
            "THEFT_OFF" -> {
                val p = prefs()
                p.edit()
                    .putBoolean("theft_mode", false)
                    .putBoolean("selfie_enabled", false)
                    .putBoolean("alert_sim", false)
                    .putBoolean("alert_silent", false)
                    .putBoolean("location_ping", false)
                    .putBoolean("telegram_enabled", false)
                    .apply()
                updateNotif()
                sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                return START_STICKY
            }
            // v2.7.7: Entry point from the static SmsCommandReceiver.
            // This works EVEN IF the service wasn't already running.
            "SMS_COMMAND" -> {
                handleIncomingSmsIntent(intent)
                // Continue into normal startup below ONLY if not already running,
                // so a cold-started service (triggered purely by an SMS) also
                // sets up its foreground notification + receivers correctly.
                if (prefs().getBoolean("running", false)) {
                    return START_STICKY
                }
                // fallthrough to normal startup sequence below
            }
        }

        currentPingInterval = prefs().getInt("ping_interval", 5)
        prefs().edit().putBoolean("location_ping", false).apply()
        startForeground(NOTIF_ID, buildNotif())

        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "ThievesTrap:MonitorLock"
            ).also { it.acquire(24 * 60 * 60 * 1000L) }
        } catch (e: Exception) {}

        startLocationUpdates()
        saveSimInfo()
        registerReceivers()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wakeLock?.release() } catch (e: Exception) {}
        fusedClient.removeLocationUpdates(locationCallback)
        pingRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        silentGraceHandler?.removeCallbacksAndMessages(null)
        listOf(unlockReceiver, simReceiver, smsReceiver, silentReceiver).forEach {
            try { it?.let { r -> unregisterReceiver(r) } } catch (e: Exception) {}
        }
        try { silentGraceCancelReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        unregisterSimTrapNetworkListener()
        if (prefs().getBoolean("running", false)) {
            handler.postDelayed({
                try {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        Intent(applicationContext, MonitorService::class.java).apply {
                            action = "START"
                        }
                    )
                } catch (e: Exception) {}
            }, 2000)
        }
    }

    override fun onBind(intent: Intent?) = null

    private fun prefs() = getSharedPreferences("tt_prefs", MODE_PRIVATE)
    private fun theftMode() = prefs().getBoolean("theft_mode", false)
    private fun phone() = prefs().getString("phone", "") ?: ""
    private fun phone2() = prefs().getString("phone2", "") ?: ""
    private fun lang() = LocaleHelper.getLang(this)
    private fun s(key: String) = Strings.getStr(lang(), key)

    // ── IMEI / Device ID caching (retained from v2.7.6) ─────────────

    private fun cacheDeviceIdentifiers() {
        try {
            val p = prefs()
            val existing = p.getString("imei", "") ?: ""
            if (existing.isNotBlank()) return

            var imei: String? = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                    @Suppress("DEPRECATION")
                    imei = tm.deviceId
                } catch (e: Exception) { imei = null }
            }
            val androidId = androidId()
            val finalId = imei?.takeIf { it.isNotBlank() && it != "0" } ?: androidId
            p.edit().putString("imei", finalId).apply()
            Log.i(TAG, "Device identifier cached: $finalId")
        } catch (e: Exception) { Log.e(TAG, "cacheDeviceIdentifiers: ${e.message}") }
    }

    private fun deviceImeiOrId(): String {
        val saved = prefs().getString("imei", "") ?: ""
        return if (saved.isNotBlank()) saved else androidId()
    }

    // ══════════════════════════════════════════════════════════════
    // ── v2.7.7: SMS handling — works from BOTH the static receiver
    //    (SmsCommandReceiver, manifest priority=999) and the dynamic
    //    receiver registered below (covers devices/ROMs where only
    //    dynamic registration delivers SMS_RECEIVED while running).
    // ══════════════════════════════════════════════════════════════

    /** Entry point for SMS forwarded from the static SmsCommandReceiver */
    private fun handleIncomingSmsIntent(intent: Intent) {
        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.extras?.getString("format")
        processSmsPdus(pdus, format)
    }

    /**
     * Parses raw PDUs into (sender -> fullBody) pairs, applies loop guards,
     * and dispatches recognized commands asynchronously.
     *
     * "WHERE"/"LOCATION"/"LOC"/"FIND" is handled with ZERO premium gating —
     * works identically in Free and Paid.
     */
    private fun processSmsPdus(pdus: Array<*>, format: String?) {
        val myNum = prefs().getString("my_number", "").orEmpty().filter { it.isDigit() }
        val myPhone = prefs().getString("phone", "").orEmpty().filter { it.isDigit() }

        val messagesBySender = LinkedHashMap<String, StringBuilder>()
        for (pdu in pdus) {
            @Suppress("DEPRECATION")
            val msg = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format != null)
                    android.telephony.SmsMessage.createFromPdu(pdu as ByteArray, format)
                else
                    android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
            } catch (e: Exception) { null } ?: continue

            val sender = msg.originatingAddress ?: continue
            val part = msg.messageBody ?: ""
            messagesBySender.getOrPut(sender) { StringBuilder() }.append(part)
        }

        for ((sender, sb) in messagesBySender) {
            val rawBody = sb.toString().trim()
            if (rawBody.isEmpty()) continue
            val body = rawBody.uppercase()
            val senderDigits = sender.filter { it.isDigit() }

            // LOOP GUARD 1: Ignore own number
            if (myNum.isNotBlank() && senderDigits.endsWith(myNum.takeLast(8))) continue
            if (myPhone.isNotBlank() && senderDigits.endsWith(myPhone.takeLast(8))) continue

            // LOOP GUARD 2: Only process known commands
            val knownCommands = setOf(
                "WHERE", "LOCATION", "LOC", "FIND",
                "INFO", "DEVICE", "STATUS", "BATTERY", "BAT", "SIM", "IMEI",
                "HISTORY", "ALARM", "RING", "STOP ALARM", "SILENCE", "STOP", "LOCK",
                "SELFIE", "PHOTO", "PICTURE",
                "PING", "ACTIVE", "ACTIVATE", "DEACTIVATE", "DISARM",
                "HELP", "COMMANDS", "?", "STOP PING"
            )
            val isKnownCmd = knownCommands.any {
                body == it || body.startsWith("$it ") ||
                body.startsWith("DISARM ") || body.startsWith("PING ")
            }
            if (!isKnownCmd) continue

            // LOOP GUARD 3: Per-sender cooldown (1 command per 5 seconds)
            val lastCmdKey = "last_cmd_$senderDigits"
            val lastCmd = prefs().getLong(lastCmdKey, 0L)
            if (System.currentTimeMillis() - lastCmd < 5000L) continue
            prefs().edit().putLong(lastCmdKey, System.currentTimeMillis()).apply()

            // Process off the main thread — keeps ALARM/SELFIE responsive
            // and avoids the broadcast-receiver execution time limit.
            Thread {
                try {
                    handleCommand(body, sender)
                } catch (e: Exception) {
                    Log.e(TAG, "handleCommand error: ${e.message}")
                }
            }.start()
        }
    }

    // ── Receivers ─────────────────────────────────────────────────

    private fun registerReceivers() {
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> onUnlock()
                    Intent.ACTION_SCREEN_ON -> screenOnCount++
                }
            }
        }
        registerReceiver(unlockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        })

        // ── v2.7.7: SIM monitoring — Smart Trap state machine ──
        simReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getStringExtra("ss")
                    ?: intent.getStringExtra("simStatus") ?: ""
                Log.i("TT-SIM", "SIM state: $state")
                checkSimChange()
            }
        }
        val simIntentFilter = android.content.IntentFilter().apply {
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
            addAction("android.telephony.action.SUBSCRIPTION_INFO_UPDATED")
        }
        registerReceiver(simReceiver, simIntentFilter)

        // Silent mode — 1-minute grace period with Cancel action
        silentReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!theftMode() || !prefs().getBoolean("alert_silent", false)) return
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT,
                    AudioManager.RINGER_MODE_VIBRATE -> {
                        if (canSendAlert("silent")) {
                            startSilentGracePeriod()
                        }
                    }
                }
            }
        }
        registerReceiver(silentReceiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))

        // ── v2.7.7: Dynamic SMS receiver — secondary path while the
        // service is alive. Highest priority allowed for dynamic
        // registration. The static SmsCommandReceiver (manifest,
        // priority=999) is the primary path and works even if this
        // service/process has been killed.
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
                val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
                val format = intent.extras?.getString("format")
                processSmsPdus(pdus, format)
            }
        }
        val smsFilter = IntentFilter("android.provider.Telephony.SMS_RECEIVED").apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(smsReceiver, smsFilter)

        // v2.7.7: If a SIM swap is pending from before this service started,
        // resume the network watcher now that we have a Context again.
        if (prefs().getBoolean("SIM_CHANGED_PENDING_ALERT", false)) {
            registerSimTrapNetworkListener()
        }
    }

    // ── Event Handlers ────────────────────────────────────────────

    private fun onUnlock() {
        screenOnCount = 0
        if (prefs().getBoolean("grace_enabled", false)) {
            GracePeriodManager.cancelGrace()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── v2.7.7: SMART SIM CARD CHANGE TRAPPING MECHANISM
    //
    //    1. SIM swap/removal detected → set SIM_CHANGED_PENDING_ALERT=true,
    //       record old SIM snapshot, register a ConnectivityManager network
    //       callback that waits for ANY active cellular network.
    //    2. When the new SIM gets network signal, read the new line number
    //       (if available) and the new carrier name.
    //    3. Fire ONE emergency SMS to the saved Emergency Contact informing
    //       them the SIM was swapped, with live location + new carrier info.
    //    4. Clear SIM_CHANGED_PENDING_ALERT and unregister the listener.
    // ══════════════════════════════════════════════════════════════

    private fun checkSimChange() {
        if (!theftMode() || !prefs().getBoolean("alert_sim", false)) return
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val isAbsent = (tm.simState == android.telephony.TelephonyManager.SIM_STATE_ABSENT)
            @Suppress("DEPRECATION")
            val cur = try { tm.simSerialNumber ?: "" } catch (e: Exception) { "" }
            val saved = prefs().getString("sim", "") ?: ""
            val changed = isAbsent || (saved.isNotBlank() && cur.isNotBlank() && cur != saved)
            if (!changed) return
            if (!isAbsent) prefs().edit().putString("sim", cur).apply()

            recordAlert(s("sms_sim_changed"))
            Log.i("TT-SIM", "SIM change detected — entering Wait-for-Network trap")

            // v2.7.7: Set persistent pending-alert flag and snapshot the event
            prefs().edit()
                .putBoolean("SIM_CHANGED_PENDING_ALERT", true)
                .putLong("sim_change_time", System.currentTimeMillis())
                .apply()

            if (GracePeriodManager.isActive()) return

            // Send what we can immediately (best-effort — may fail if no signal)
            handler.postDelayed({
                val simAlert = "SIM CHANGE DETECTED\n${locStr()}" +
                    "\n\nNote: You can only enable and disable auto location ping remotely. " +
                    "Commands: (PING 2), (PING 5), or (STOP PING)."
                smsAll(simAlert)
                if (LicenseManager.isPremium(this))
                    TelegramUploader.sendMessage(this, "SIM CHANGE DETECTED\n${locStr()}")
            }, 30_000L)

            // v2.7.7: Register the network watcher — fires the guaranteed
            // emergency SMS the moment ANY cellular network becomes active,
            // regardless of whether the immediate send above succeeded.
            registerSimTrapNetworkListener()

        } catch (e: Exception) { Log.e("TT", "simChange: ${e.message}") }
    }

    /**
     * v2.7.7: Registers a ConnectivityManager.NetworkCallback that fires
     * as soon as the device has an active, validated cellular network —
     * i.e. the moment the NEW SIM gets signal. At that point we read the
     * new line number / carrier and fire the swap-alert SMS exactly once.
     */
    private fun registerSimTrapNetworkListener() {
        if (networkCallback != null) return // already registered

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "SIM trap: cellular network became available")
                onNewSimNetworkAvailable()
            }

            override fun onCapabilitiesChanged(
                network: Network, caps: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, caps)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Log.i(TAG, "SIM trap: cellular network validated")
                    onNewSimNetworkAvailable()
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "SIM trap: network listener registered — awaiting new SIM signal")
        } catch (e: Exception) {
            Log.e(TAG, "SIM trap: failed to register network callback: ${e.message}")
            networkCallback = null
        }
    }

    private fun unregisterSimTrapNetworkListener() {
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {}
        }
        networkCallback = null
    }

    /**
     * v2.7.7: Called when the new SIM gets cellular signal after a SIM swap.
     * Reads the new line number / carrier, fires ONE emergency SMS to the
     * Emergency Contact with location + new carrier exposure, then clears
     * the pending-alert flag.
     */
    private fun onNewSimNetworkAvailable() {
        // Only fire once per SIM-change event
        if (!prefs().getBoolean("SIM_CHANGED_PENDING_ALERT", false)) return

        // Debounce — wait briefly for TelephonyManager to populate carrier info
        handler.postDelayed({
            try {
                if (!prefs().getBoolean("SIM_CHANGED_PENDING_ALERT", false)) return@postDelayed

                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val newCarrier = tm.networkOperatorName ?: "Unknown"
                val newCountry = tm.simCountryIso?.uppercase() ?: "N/A"
                @Suppress("DEPRECATION")
                val newLine = try { tm.line1Number ?: "" } catch (e: Exception) { "" }
                val newLineDisplay = if (newLine.isNotBlank()) newLine else "N/A (not exposed by carrier)"

                val msg = buildString {
                    appendLine("\uD83D\uDEA8 THIEVES TRAP \u2014 SIM CARD SWAPPED!")
                    appendLine("The original SIM was removed/changed.")
                    appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
                    appendLine("\uD83D\uDCCD LOCATION")
                    appendLine(locStr())
                    appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
                    appendLine("\uD83D\uDCF1 NEW SIM DETAILS")
                    appendLine("Carrier: $newCarrier")
                    appendLine("Country: $newCountry")
                    appendLine("New Number: $newLineDisplay")
                    appendLine("IMEI: ${deviceImeiOrId()}")
                    appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
                    append("Sent automatically by Thieves Trap Security")
                }

                // Fire to the Emergency Contact (smsAll covers contact 1 + 2 if premium)
                smsAll(msg)
                if (LicenseManager.isPremium(this))
                    TelegramUploader.sendMessage(this, msg)

                recordAlert("SIM swap alert sent (new carrier: $newCarrier)")
                Log.i(TAG, "SIM trap: emergency SMS sent — new carrier=$newCarrier, number=$newLineDisplay")

            } catch (e: Exception) {
                Log.e(TAG, "onNewSimNetworkAvailable: ${e.message}")
            } finally {
                // Clear the pending flag and stop listening — one-shot trap
                prefs().edit().putBoolean("SIM_CHANGED_PENDING_ALERT", false).apply()
                unregisterSimTrapNetworkListener()
            }
        }, 5_000L) // 5s debounce for carrier info to populate
    }

    // ── Silent mode grace period — 60s countdown with Cancel notification ──
    private fun startSilentGracePeriod() {
        val GRACE_MS = 60_000L
        val startMs = System.currentTimeMillis()

        val nm = getSystemService(android.app.NotificationManager::class.java)

        val cancelIntent = Intent("com.thievestrap.CANCEL_SILENT_GRACE").apply {
            setPackage(packageName)
        }
        val cancelPi = android.app.PendingIntent.getBroadcast(
            this, SILENT_GRACE_NOTIF_ID, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )

        fun buildCountdownNotif(secsLeft: Int): android.app.Notification =
            NotificationCompat.Builder(this, SILENT_GRACE_CHAN_ID)
                .setContentTitle("\u26a0\ufe0f Silent Mode Detected")
                .setContentText("Emergency SMS in ${secsLeft}s — tap Cancel to stop")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel / Stop",
                    cancelPi
                )
                .build()

        silentGraceCancelReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
        }
        silentGraceCancelReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "com.thievestrap.CANCEL_SILENT_GRACE") {
                    silentGraceHandler?.removeCallbacksAndMessages(null)
                    nm?.cancel(SILENT_GRACE_NOTIF_ID)
                    Log.i(TAG, "Silent grace period cancelled by user")
                    try { unregisterReceiver(this) } catch (e: Exception) {}
                    silentGraceCancelReceiver = null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(silentGraceCancelReceiver!!,
                IntentFilter("com.thievestrap.CANCEL_SILENT_GRACE"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(silentGraceCancelReceiver!!,
                IntentFilter("com.thievestrap.CANCEL_SILENT_GRACE"))
        }

        silentGraceHandler?.removeCallbacksAndMessages(null)
        silentGraceHandler = Handler(Looper.getMainLooper())

        silentGraceRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startMs
                val remaining = GRACE_MS - elapsed
                if (remaining <= 0) {
                    nm?.cancel(SILENT_GRACE_NOTIF_ID)
                    silentGraceCancelReceiver?.let {
                        try { unregisterReceiver(it) } catch (e: Exception) {}
                    }
                    silentGraceCancelReceiver = null
                    sendAlertWithLocation("\uD83D\uDD07", s("sms_silent_on"))
                    return
                }
                val secsLeft = (remaining / 1000).toInt()
                nm?.notify(SILENT_GRACE_NOTIF_ID, buildCountdownNotif(secsLeft))
                silentGraceHandler?.postDelayed(this, 1000)
            }
        }
        nm?.notify(SILENT_GRACE_NOTIF_ID, buildCountdownNotif(60))
        silentGraceHandler?.postDelayed(silentGraceRunnable!!, 1000)
        Log.i(TAG, "Silent grace period started — 60s countdown")
    }

    private fun createSilentGraceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                SILENT_GRACE_CHAN_ID,
                "Silent Mode Alert",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(android.app.NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── v2.7.7: handleCommand — WHERE detached from premium check
    // ══════════════════════════════════════════════════════════════
    private fun handleCommand(cmd: String, sender: String) {
        val password = prefs().getString("password", "") ?: ""

        // ── FREE: WHERE / LOCATION / LOC / FIND ──
        // Zero premium gating. Sends EXACTLY ONE SMS reply with GPS link.
        // No ping side-effects, no recurring behavior.
        if (cmd == "WHERE" || cmd == "LOCATION" || cmd == "LOC" || cmd == "FIND") {
            sms(sender, buildFullInfo("\uD83D\uDCCD", s("sms_location")))
            Log.i(TAG, "WHERE command (free/paid, no gating): single location SMS sent to $sender")
            return
        }

        // ── FREE: HELP / STATUS ──
        val freeCommands = setOf("HELP", "STATUS")
        if (freeCommands.contains(cmd)) {
            when (cmd) {
                "HELP" -> { sms(sender, s("sms_help")); return }
                "STATUS" -> {
                    sms(sender, "${s("app_name")}: Armed=${prefs().getBoolean("running", false)}" +
                        " | Premium=${LicenseManager.isPremium(this)}")
                    return
                }
            }
        }

        // ── Everything below requires Premium ──
        if (!LicenseManager.isPremium(this)) return

        if (cmd == "ACTIVE" || cmd == "ACTIVATE") {
            if (!theftMode()) {
                prefs().edit()
                    .putBoolean("theft_mode", true)
                    .putBoolean("selfie_enabled", true)
                    .putBoolean("alert_sim", true)
                    .putBoolean("alert_silent", true)
                    .putBoolean("telegram_enabled", true)
                    .apply()
                updateNotif()
                sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                val activationMsg = "\uD83D\uDEE1\ufe0f Thieves Trap ACTIVATED\n" +
                    "All alerts are now ON.\n${locStr()}"
                sms(sender, activationMsg)
                TelegramUploader.sendMessage(this,
                    "\u2705 THEFT MODE ON (remote)\n$activationMsg")
            } else {
                TelegramUploader.sendMessage(this,
                    "${s("app_name")}: Theft Mode already active.")
            }
            return
        }

        if (cmd == "DEACTIVATE") {
            prefs().edit()
                .putBoolean("theft_mode", false)
                .putBoolean("selfie_enabled", false)
                .putBoolean("alert_sim", false)
                .putBoolean("alert_silent", false)
                .putBoolean("alert_shutdown", false)
                .putBoolean("location_ping", false)
                .putBoolean("telegram_enabled", false)
                .putBoolean("fake_shutdown_enabled", false)
                .apply()
            pingRunnable?.let { handler.removeCallbacks(it) }
            updateNotif()
            sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
            sms(sender, "${s("app_name")}: ${s("sms_theft_deactivated")}")
            return
        }

        when {
            cmd == "INFO" || cmd == "DEVICE" ->
                TelegramUploader.sendMessage(this, buildFullInfo("\uD83D\uDCF1", s("sms_info")))

            cmd == "STATUS" ->
                TelegramUploader.sendMessage(this, buildString {
                    appendLine("${s("app_name")} V8 \u2705")
                    appendLine("${s("sms_time")}: ${ts()}")
                    appendLine("${s("sms_battery")}: ${bat()}% | ${chargingStr()}")
                    appendLine("Mode: ${if (theftMode()) s("theft_mode_on") else s("theft_mode_off")}")
                    appendLine("Ping: ${currentPingInterval}min")
                    appendLine("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
                    append(locStr())
                })

            cmd == "BATTERY" || cmd == "BAT" ->
                TelegramUploader.sendMessage(this,
                    "${s("app_name")}\n${s("sms_battery")}: ${bat()}%\n" +
                    "${chargingStr()}\n${s("sms_time")}: ${ts()}")

            cmd == "SIM" ->
                TelegramUploader.sendMessage(this,
                    "${s("app_name")} - SIM\n${simBlock()}")

            cmd == "IMEI" -> {
                TelegramUploader.sendMessage(this,
                    "${s("app_name")}\n${s("sms_imei")}: ${deviceImeiOrId()}\n" +
                    "${s("sms_android_id")}: ${androidId()}")
            }

            cmd == "HISTORY" ->
                TelegramUploader.sendMessage(this, buildString {
                    appendLine("${s("app_name")} - ${s("sms_history_title")}")
                    if (locationHistory.isEmpty()) appendLine(s("sms_no_history"))
                    else locationHistory.forEachIndexed { i, loc ->
                        appendLine("${i + 1}. $loc")
                    }
                })

            // v2.7.7: SELFIE — async camera capture, runs on background thread already
            cmd == "SELFIE" || cmd == "PHOTO" || cmd == "PICTURE" -> {
                SelfieService.takePhoto(this, 3)
                recordAlert("Remote SELFIE command")
                TelegramUploader.sendMessage(this,
                    "\uD83D\uDCF8 ${s("app_name")}: Taking 3 selfies now.")
                Log.i(TAG, "SELFIE command executed for $sender")
            }

            // v2.7.7: ALARM — max-volume siren
            cmd == "ALARM" || cmd == "RING" -> {
                ContextCompat.startForegroundService(this,
                    Intent(this, AlarmService::class.java).apply { action = "START_ALARM" })
                TelegramUploader.sendMessage(this,
                    "\uD83D\uDEA8 ${s("app_name")}: ${s("sms_alarm_on")}")
                Log.i(TAG, "ALARM command executed for $sender")
            }

            cmd == "STOP ALARM" || cmd == "SILENCE" -> {
                startService(Intent(this, AlarmService::class.java).apply {
                    action = "STOP_ALARM"
                })
                TelegramUploader.sendMessage(this,
                    "${s("app_name")}: ${s("sms_alarm_off")}")
            }

            cmd.startsWith("PING ") -> {
                val mins = cmd.removePrefix("PING ").trim().toIntOrNull()
                if (mins != null && mins in 1..60) {
                    restartPing(mins)
                    prefs().edit().putInt("ping_interval", mins)
                        .putBoolean("location_ping", true).apply()
                    TelegramUploader.sendMessage(this,
                        "${s("app_name")}: ${String.format(s("sms_ping"), mins)}")
                }
            }

            cmd == "STOP PING" -> {
                prefs().edit().putBoolean("location_ping", false).apply()
                pingRunnable?.let { handler.removeCallbacks(it) }
                pingRunnable = null
                TelegramUploader.sendMessage(this,
                    "${s("app_name")}: ${s("sms_ping_stopped")}")
            }

            cmd == "LOCK" -> {
                try {
                    (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                    TelegramUploader.sendMessage(this,
                        "${s("app_name")}: ${s("sms_locked")}")
                } catch (e: Exception) {
                    TelegramUploader.sendMessage(this, "Lock failed.")
                }
            }

            cmd == "HELP" || cmd == "COMMANDS" || cmd == "?" ->
                TelegramUploader.sendMessage(this, s("sms_help"))

            cmd.startsWith("DISARM ") -> {
                val pin = cmd.removePrefix("DISARM ").trim()
                if (pin == password) {
                    TelegramUploader.sendMessage(this,
                        "${s("app_name")}: ${s("sms_disarmed_remote")}")
                    prefs().edit().putBoolean("running", false)
                        .putBoolean("theft_mode", false).apply()
                    stopSelf()
                } else {
                    TelegramUploader.sendMessage(this,
                        "${s("app_name")}: ${s("sms_wrong_pin_remote")}")
                    smsAll(buildFullInfo("\u26a0\ufe0f",
                        "${s("sms_wrong_pin_remote")} from: $sender"))
                }
            }
        }
    }

    // ── Ping ──────────────────────────────────────────────────────

    private fun schedulePing(intervalMins: Int) {
        currentPingInterval = intervalMins
        pingRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable = object : Runnable {
            override fun run() {
                if (prefs().getBoolean("location_ping", false) && theftMode()) {
                    smsAll(buildString {
                        appendLine("\uD83D\uDCCD ${s("app_name")} \u2014 ${s("sms_location_ping_title")}")
                        appendLine("${currentPingInterval}min | ${ts()}")
                        appendLine("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550")
                        appendLine(locStr())
                        appendLine("${s("sms_battery")}: ${bat()}% | ${chargingStr()}")
                        appendLine("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
                        appendLine(simBlock())
                        append("\n\nNote: You can only enable and disable auto location ping remotely. " +
                            "Commands: (PING 2), (PING 5), or (STOP PING).")
                    })
                    recordAlert(s("sms_location_ping_title"))
                }
                handler.postDelayed(this, currentPingInterval * 60 * 1000L)
            }
        }
        handler.postDelayed(pingRunnable!!, intervalMins * 60 * 1000L)
    }

    private fun restartPing(mins: Int) { schedulePing(mins) }

    // ── Info Builders ─────────────────────────────────────────────

    private fun sendAlert(emoji: String, description: String) {
        val stopPingNote = "\n\nNote: You can only enable and disable auto location ping remotely. " +
            "Commands: (PING 2), (PING 5), or (STOP PING)."
        val body = buildFullInfo(emoji, description) + stopPingNote
        smsAll(body)
        showNotif("$emoji $description")
        recordAlert(description.take(30))
    }

    private fun buildFullInfo(emoji: String, description: String): String {
        return buildString {
            appendLine("\uD83D\uDEA8 THIEVES TRAP: SECURITY ALERT")
            appendLine("$emoji $description")
            appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
            appendLine("\uD83D\uDCCD LOCATION")
            appendLine(locStr())
            appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
            appendLine("\uD83D\uDCF1 DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("IMEI: ${deviceImeiOrId()}")
            appendLine("\uD83D\uDD0B Battery: ${bat()}%")
            appendLine("\uD83D\uDD50 Time: ${ts()}")
            appendLine("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501")
            append("\uD83D\uDCE1 SIM: ")
            append(simBlock())
        }
    }

    private fun deviceBlock(): String = buildString {
        val p = prefs()
        appendLine("${s("sms_brand")}: ${Build.BRAND}")
        appendLine("${s("sms_model")}: ${Build.MODEL}")
        appendLine("${s("sms_android")}: ${Build.VERSION.RELEASE}")
        appendLine("${s("sms_android_id")}: ${androidId()}")
        appendLine("IMEI: ${deviceImeiOrId()}")
        val myNum = p.getString("my_number", "") ?: ""
        if (myNum.isNotBlank()) appendLine("${s("sms_owner")}: $myNum")
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            appendLine("ICCID: ${tm.simSerialNumber ?: "N/A"}")
        } catch (e: Exception) {}
    }

    private fun simBlock(): String = buildString {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            appendLine("${s("sms_operator")}: ${tm.networkOperatorName ?: "N/A"}")
            appendLine("${s("sms_country")}: ${tm.simCountryIso?.uppercase() ?: "N/A"}")
            @Suppress("DEPRECATION")
            appendLine("${s("sms_new_number")}: " +
                "${try { tm.line1Number ?: "N/A" } catch (e: Exception) { "N/A" }}")
            val type = when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                else -> "2G"
            }
            append("${s("sms_network")}: $type")
        } catch (e: Exception) { append("N/A") }
    }

    private fun locStr(): String {
        val loc = lastLocation ?: getBestLastKnownLocation()
        if (loc == null) {
            return try {
                val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                @Suppress("DEPRECATION")
                val netLoc = lm.getLastKnownLocation(
                    android.location.LocationManager.NETWORK_PROVIDER)
                if (netLoc != null) {
                    val mUrl = "https://maps.google.com/?q=${netLoc.latitude},${netLoc.longitude}"
                    "Network: ${"%.5f".format(netLoc.latitude)}, " +
                        "${"%.5f".format(netLoc.longitude)}\nMap: $mUrl"
                } else {
                    "Location: Fetching... (GPS warming up)"
                }
            } catch (e: Exception) { "Location: GPS unavailable" }
        }
        val lat = "%.6f".format(loc.latitude)
        val lng = "%.6f".format(loc.longitude)
        val mapsUrl = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        return "GPS: $lat, $lng (\u00b1${loc.accuracy.toInt()}m)\nMap: $mapsUrl"
    }

    private fun getBestLastKnownLocation(): Location? = try {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER
        ).mapNotNull {
            @Suppress("DEPRECATION")
            try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
        }.minByOrNull { it.accuracy }
    } catch (e: Exception) { null }

    private fun addToHistory(loc: Location) {
        val entry = "${ts()} | ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
        if (locationHistory.size >= 5) locationHistory.removeLast()
        locationHistory.addFirst(entry)
    }

    private fun ts() =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun bat(): Int = (getSystemService(BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun chargingStr(): String {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return if (bm.isCharging) s("sms_charging_status") else s("sms_not_charging")
    }

    private fun androidId() =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "N/A"

    private fun sendAlertWithLocation(emoji: String, description: String) {
        if (lastLocation != null) { sendAlert(emoji, description); return }
        val startMs = System.currentTimeMillis()
        val checkRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startMs
                if (lastLocation != null || elapsed >= 15_000) {
                    sendAlert(emoji, description)
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(checkRunnable)
    }

    private fun recordAlert(title: String) {
        val p = prefs()
        val count = p.getInt("total_alerts", 0) + 1
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        p.edit().putInt("total_alerts", count).putString("last_alert", "$time \u2014 $title").apply()
    }

    private fun saveSimInfo() {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val serial = tm.simSerialNumber ?: return
            val p = prefs()
            if (p.getString("sim", "").isNullOrBlank())
                p.edit().putString("sim", serial).apply()
        } catch (e: Exception) {}
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20000L)
            .setMinUpdateIntervalMillis(10000L).build()
        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun smsAll(msg: String) {
        val p = phone()
        if (p.isNotBlank()) sms(p, msg)
        val p2 = phone2()
        if (p2.isNotBlank() && LicenseManager.isPremium(this)) sms(p2, msg)
    }

    private fun sms(to: String, msg: String) {
        try {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(msg)
            sm.sendMultipartTextMessage(to, null, parts, null, null)
            Log.i(TAG, "SMS \u2192 $to")
        } catch (e: Exception) { Log.e(TAG, "SMS failed: ${e.message}") }
    }

    private fun buildNotif(): Notification {
        val p = prefs()
        val running = p.getBoolean("running", false)
        val theft = theftMode()
        val (title, text) = when {
            theft   -> Pair("Protection Active", "Protection Status: Full Protection")
            running -> Pair("Protection Active", "Protection Status: Medium Protection")
            else    -> Pair("UNPROTECTED", "System Idle \u2014 Monitoring OFF.")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thieves Trap")
            .setSubText(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotif() {
        if (!prefs().getBoolean("notif_enabled", true)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") stopForeground(true)
                }
            } catch (e: Exception) {}
            try {
                getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            } catch (e: Exception) {}
            return
        }
        try {
            startForeground(NOTIF_ID, buildNotif())
        } catch (e: Exception) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotif())
        }
    }

    private fun showNotif(msg: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${s("app_name")} Alert")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, s("app_name"),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
