package com.thievestrap

import android.os.Build

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.location.Location
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private var airplaneReceiver: BroadcastReceiver? = null
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
                    // Persist for AccessibilityService (different process)
                    prefs().edit()
                        .putFloat("last_lat", it.latitude.toFloat())
                        .putFloat("last_lng", it.longitude.toFloat())
                        .putString("last_location_str", "Map: https://maps.google.com/?q=${it.latitude},${it.longitude}")
                        .apply()
                }
            }
        }
        createChannel()
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
                smsAll(buildFullInfo("TEST ✅", s("sms_test")))
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
                // Fix 5b: sendAlert internally calls recordAlert — only call once
                val alertBody = "$count failed unlock attempt(s) detected"
                sendAlert("⚠️", alertBody)
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
                    // Fix 4: Send photo to Telegram immediately (Premium only)
                    if (LicenseManager.isPremium(this) && photoFile.exists() && photoFile.length() > 0) {
                        val captionLine1 = "🚨 THIEVES TRAP — INTRUDER PHOTO #$count"
                        val captionLine2 = "Time: ${ts()} | Battery: ${bat()}%"
                        val captionLine3 = locStr()
                        val caption = captionLine1 + "\n" + captionLine2 + "\n" + captionLine3
                        TelegramUploader.sendPhoto(this, photoFile, caption)
                        Log.i("TT", "Intruder photo #$count sent to Telegram: ${photoFile.name}")
                    }
                }
                return START_STICKY
            }
            "THEFT_ON" -> {
                if (!LicenseManager.isPremium(this)) return START_STICKY
                val p = prefs()
                // Fix 3: Force ALL premium toggles ON when theft mode activated
                p.edit()
                    .putBoolean("theft_mode", true)
                    .putBoolean("selfie_enabled", true)
                    .putBoolean("alert_sim", true)
                    .putBoolean("alert_airplane", true)
                    .putBoolean("alert_silent", true)
                    .putBoolean("location_ping", true)
                    .putBoolean("telegram_enabled", true)
                    .apply()
                updateNotif()
                // Ping stays OFF — only activated via remote PING command
                sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                return START_STICKY
            }
            "THEFT_OFF" -> {
                val p = prefs()
                // Fix 3: Force ALL premium toggles OFF when theft mode deactivated
                p.edit()
                    .putBoolean("theft_mode", false)
                    .putBoolean("selfie_enabled", false)
                    .putBoolean("alert_sim", false)
                    .putBoolean("alert_airplane", false)
                    .putBoolean("alert_silent", false)
                    .putBoolean("location_ping", false)
                    .putBoolean("telegram_enabled", false)
                    .apply()
                updateNotif()
                sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                return START_STICKY
            }
        }

        currentPingInterval = prefs().getInt("ping_interval", 5)
        prefs().edit().putBoolean("location_ping", false).apply()  // Always OFF at start
        startForeground(NOTIF_ID, buildNotif())
        // Start SOS foreground service — persistent key listener
        // Fix 1: Acquire partial wake lock to keep CPU alive 24/7
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "ThievesTrap:MonitorLock"
            ).also { it.acquire(24 * 60 * 60 * 1000L) } // 24 hour max
        } catch (e: Exception) {}
        startLocationUpdates()
        saveSimInfo()
        registerReceivers()
        // Ping is OFF at startup — only activates via remote PING SMS command
        // SMS on arm removed — SMS only sent on wrong password or remote command
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wakeLock?.release() } catch (e: Exception) {}
        fusedClient.removeLocationUpdates(locationCallback)
        pingRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        listOf(unlockReceiver, simReceiver, airplaneReceiver,
            smsReceiver, silentReceiver).forEach {
            try { it?.let { r -> unregisterReceiver(r) } } catch (e: Exception) {}
        }
        // Fix 1: Auto-restart if still armed
        if (prefs().getBoolean("running", false)) {
            handler.postDelayed({
                try {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        Intent(applicationContext, MonitorService::class.java).apply { action = "START" }
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

    // ── Receivers ─────────────────────────────────────────────────

    private fun registerReceivers() {
        // Unlock
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

        // SIM
        simReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Fix 3: Handle SIM removal (ABSENT) and SIM change (LOADED/READY)
                val state = intent.getStringExtra("ss")
                    ?: intent.getStringExtra("simStatus") ?: ""
                Log.i("TT-SIM", "SIM state: $state")
                // Fire on any state change — checkSimChange() guards internally
                checkSimChange()
            }
        }
        val simIntentFilter = android.content.IntentFilter().apply {
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
            addAction("android.telephony.action.SUBSCRIPTION_INFO_UPDATED")
        }
        registerReceiver(simReceiver, simIntentFilter)

        // Airplane
        airplaneReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!theftMode() || !prefs().getBoolean("alert_airplane", false)) return
                val isOn = intent.getBooleanExtra("state", false)
                if (isOn && canSendAlert("airplane")) {
                    sendAlertWithLocation("✈️", s("sms_airplane"))
                }
            }
        }
        registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))



        // Silent mode
        silentReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!theftMode() || !prefs().getBoolean("alert_silent", false)) return
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT ->
                        if (canSendAlert("silent")) { sendAlertWithLocation("🔇", s("sms_silent_on")) }
                    AudioManager.RINGER_MODE_VIBRATE ->
                        if (canSendAlert("vibrate")) { sendAlert("📳", s("sms_vibrate")) }
                }
            }
        }
        registerReceiver(silentReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))

        // SMS commands
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
                val myNum = prefs().getString("my_number", "").orEmpty().filter { it.isDigit() }
                val myPhone = prefs().getString("phone", "").orEmpty().filter { it.isDigit() }
                for (pdu in pdus) {
                    @Suppress("DEPRECATION")
                    val msg = android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
                    val rawBody = msg.messageBody?.trim() ?: continue
                    val body = rawBody.uppercase()
                    val sender = msg.originatingAddress ?: continue
                    val senderDigits = sender.filter { it.isDigit() }

                    // LOOP GUARD 1: Ignore messages from own number (prevents self-loop)
                    if (myNum.isNotBlank() && senderDigits.endsWith(myNum.takeLast(8))) continue
                    if (myPhone.isNotBlank() && senderDigits.endsWith(myPhone.takeLast(8))) continue

                    // LOOP GUARD 2: Only process known command keywords — ignore all other SMS
                    val knownCommands = setOf("WHERE","INFO","STATUS","BATTERY","SIM","IMEI",
                        "HISTORY","ALARM","STOP ALARM","STOP","LOCK","SELFIE","PHOTO","PICTURE",
                        "PING","ACTIVE","ACTIVATE","DEACTIVATE","DISARM","HELP","STOP PING")
                    val isKnownCmd = knownCommands.any { body == it || body.startsWith("$it ") ||
                        body.startsWith("DISARM ") || body.startsWith("PING ") }
                    if (!isKnownCmd) continue  // Silent ignore — not a Thieves Trap command

                    // LOOP GUARD 3: Per-sender cooldown (1 command per sender per 5 seconds)
                    val lastCmdKey = "last_cmd_$senderDigits"
                    val lastCmd = prefs().getLong(lastCmdKey, 0L)
                    if (System.currentTimeMillis() - lastCmd < 5000L) continue
                    prefs().edit().putLong(lastCmdKey, System.currentTimeMillis()).apply()

                    handleCommand(body, sender)
                }
            }
        }
        registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
    }

    // ── Event Handlers ────────────────────────────────────────────

    private fun onUnlock() {
        screenOnCount = 0
        // Cancel grace period only if it was enabled
        if (prefs().getBoolean("grace_enabled", false)) {
            GracePeriodManager.cancelGrace()
        }
    }

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
            // Phase 1: Log immediately
            recordAlert(s("sms_sim_changed"))
            Log.i("TT-SIM", "SIM change detected — starting 30s wait for network registration")
            if (GracePeriodManager.isActive()) return
            // Phase 2+3: 30-second countdown then send
            handler.postDelayed({
                val simAlert = "SIM CHANGE DETECTED\n${locStr()}" +
                    "\n\nNote: You can only enable and disable auto location ping remotely. Commands: (PING 2), (PING 5), or (STOP PING)."
                smsAll(simAlert)
                if (LicenseManager.isPremium(this))
                    TelegramUploader.sendMessage(this, "SIM CHANGE DETECTED\n${locStr()}")
            }, 30_000L)
        } catch (e: Exception) { Log.e("TT", "simChange: ${e.message}") }
    }

    private fun handleCommand(cmd: String, sender: String) {
        val password = prefs().getString("password", "") ?: ""

        // Free-tier commands (no premium needed): HELP, STATUS
        // Fix 4: WHERE/LOCATION is free for all users (one location per request)
        if (cmd == "WHERE" || cmd == "LOCATION" || cmd == "LOC" || cmd == "FIND") {
            sms(sender, buildFullInfo("📍", s("sms_location")))
            return
        }
        val freeCommands = setOf("HELP", "STATUS")
        if (freeCommands.contains(cmd)) {
            when (cmd) {
                "HELP" -> { sms(sender, s("sms_help")); return }
                "STATUS" -> { sms(sender, "${s("app_name")}: Armed=${prefs().getBoolean("running",false)} | Premium=${LicenseManager.isPremium(this)}"); return }
            }
        }

        // All other commands require Premium — silent rejection (no SMS waste)
        if (!LicenseManager.isPremium(this)) return

        // ACTIVE — full sync ON: master + all sub-features
        if (cmd == "ACTIVE" || cmd == "ACTIVATE") {
            if (!theftMode()) {
                // Fix 1: Sync ALL sub-features ON (mirrors DEACTIVATE)
                prefs().edit()
                    .putBoolean("theft_mode", true)
                    .putBoolean("selfie_enabled", true)
                    .putBoolean("alert_sim", true)
                    .putBoolean("alert_airplane", true)
                    .putBoolean("alert_silent", true)
                    .putBoolean("telegram_enabled", true)
                    .apply()
                updateNotif()
                sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                // Fix 4: Only ONE SMS on first activation — rest go to Telegram
                val activationMsg = "🛡️ Thieves Trap ACTIVATED\nAll alerts are now ON.\n${locStr()}"
                sms(sender, activationMsg)
                TelegramUploader.sendMessage(this, "✅ THEFT MODE ON (remote)\n$activationMsg")
            } else {
                // Already active — Telegram only, no SMS
                TelegramUploader.sendMessage(this, "${s("app_name")}: Theft Mode already active.")
            }
            return
        }

        // DEACTIVATE — full sync: turn OFF master + all sub-features
        if (cmd == "DEACTIVATE" || cmd == "DEACTIVATE") {
            prefs().edit()
                .putBoolean("theft_mode", false)
                .putBoolean("selfie_enabled", false)
                .putBoolean("alert_sim", false)
                .putBoolean("alert_airplane", false)
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
            cmd == "WHERE" || cmd == "LOCATION" || cmd == "LOC" || cmd == "FIND" ->
                sms(sender, buildFullInfo("📍", s("sms_location")))

            cmd == "INFO" || cmd == "DEVICE" ->
                TelegramUploader.sendMessage(this, buildFullInfo("📱", s("sms_info")))

            cmd == "STATUS" ->
                TelegramUploader.sendMessage(this, buildString {
                    appendLine("${s("app_name")} V8 ✅")
                    appendLine("${s("sms_time")}: ${ts()}")
                    appendLine("${s("sms_battery")}: ${bat()}% | ${chargingStr()}")
                    appendLine("Mode: ${if (theftMode()) s("theft_mode_on") else s("theft_mode_off")}")
                    appendLine("Ping: ${currentPingInterval}min")
                    appendLine("───────────────────────")
                    append(locStr())
                })

            cmd == "BATTERY" || cmd == "BAT" ->
                TelegramUploader.sendMessage(this, "${s("app_name")}\n${s("sms_battery")}: ${bat()}%\n${chargingStr()}\n${s("sms_time")}: ${ts()}")

            cmd == "SIM" ->
                TelegramUploader.sendMessage(this, "${s("app_name")} - SIM\n${simBlock()}")

            cmd == "IMEI" -> {
                val imei = prefs().getString("imei", "") ?: ""
                TelegramUploader.sendMessage(this, "${s("app_name")}\n${s("sms_imei")}: ${if (imei.isNotBlank()) imei else "N/A"}\n${s("sms_android_id")}: ${androidId()}")
            }

            cmd == "HISTORY" ->
                TelegramUploader.sendMessage(this, buildString {
                    appendLine("${s("app_name")} - ${s("sms_history_title")}")
                    if (locationHistory.isEmpty()) appendLine(s("sms_no_history"))
                    else locationHistory.forEachIndexed { i, loc -> appendLine("${i+1}. $loc") }
                })

            cmd == "SELFIE" || cmd == "PHOTO" || cmd == "PICTURE" -> {
                // Remote SELFIE command always works — bypasses selfie_enabled setting
                SelfieService.takePhoto(this, 3)
                recordAlert("Remote SELFIE command")
                TelegramUploader.sendMessage(this, "📸 ${s("app_name")}: Taking 3 selfies now. Photo will be emailed when saved.")
            }

            cmd == "ALARM" || cmd == "RING" ->  {
                ContextCompat.startForegroundService(this,
                    Intent(this, AlarmService::class.java).apply { action = "START_ALARM" })
                TelegramUploader.sendMessage(this, "🚨 ${s("app_name")}: ${s("sms_alarm_on")}")
            }

            cmd == "STOP ALARM" || cmd == "SILENCE" -> {
                startService(Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" })
                TelegramUploader.sendMessage(this, "${s("app_name")}: ${s("sms_alarm_off")}")
            }

            cmd.startsWith("PING ") -> {
                // Fix 5: PING command activates ping and sets the interval
                val mins = cmd.removePrefix("PING ").trim().toIntOrNull()
                if (mins != null && mins in 1..60) {
                    restartPing(mins)
                    prefs().edit().putInt("ping_interval", mins)
                        .putBoolean("location_ping", true).apply()  // Fix 5: activate on PING
                    TelegramUploader.sendMessage(this, "${s("app_name")}: ${String.format(s("sms_ping"), mins)}")
                }
            }

            cmd == "STOP PING" -> {
                prefs().edit().putBoolean("location_ping", false).apply()  // Fix 5: deactivate
                pingRunnable?.let { handler.removeCallbacks(it) }
                pingRunnable = null
                TelegramUploader.sendMessage(this, "${s("app_name")}: ${s("sms_ping_stopped")}")
            }

            cmd == "LOCK" -> {
                try {
                    (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                    TelegramUploader.sendMessage(this, "${s("app_name")}: ${s("sms_locked")}")
                } catch (e: Exception) { TelegramUploader.sendMessage(this, "Lock failed.") }
            }

            cmd == "HELP" || cmd == "COMMANDS" || cmd == "?" ->
                TelegramUploader.sendMessage(this, s("sms_help"))

            cmd.startsWith("DISARM ") -> {
                val pin = cmd.removePrefix("DISARM ").trim()
                if (pin == password) {
                    TelegramUploader.sendMessage(this, "${s("app_name")}: ${s("sms_disarmed_remote")}")
                    prefs().edit().putBoolean("running", false)
                        .putBoolean("theft_mode", false).apply()
                    stopSelf()
                } else {
                    TelegramUploader.sendMessage(this, "${s("app_name")}: ${s("sms_wrong_pin_remote")}")
                    smsAll(buildFullInfo("⚠️", "${s("sms_wrong_pin_remote")} from: $sender"))
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
                if (prefs().getBoolean("location_ping", false) && theftMode()) {  // Fix 5: off by default
                    smsAll(buildString {
                        appendLine("📍 ${s("app_name")} — ${s("sms_location_ping_title")}")
                        appendLine("${currentPingInterval}min | ${ts()}")
                        appendLine("═══════════════════════")
                        appendLine(locStr())
                        appendLine("${s("sms_battery")}: ${bat()}% | ${chargingStr()}")
                        appendLine("───────────────────────")
                        appendLine(simBlock())
                        append("\n\nNote: You can only enable and disable auto location ping remotely. Commands: (PING 2), (PING 5), or (STOP PING).")
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
        // Task 3: Only keep the Note — remove PING control instructions from SMS
        val stopPingNote = "\n\nNote: You can only enable and disable auto location ping remotely. Commands: (PING 2), (PING 5), or (STOP PING)."
        val body = buildFullInfo(emoji, description) + stopPingNote
        smsAll(body)
        showNotif("$emoji $description")
        recordAlert(description.take(30))
    }

    private fun buildFullInfo(emoji: String, description: String): String {
        return buildString {
            appendLine("🚨 THIEVES TRAP: SECURITY ALERT")
            appendLine("$emoji $description")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📍 LOCATION")
            appendLine(locStr())
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📱 DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("🔋 Battery: ${bat()}%")
            appendLine("🕐 Time: ${ts()}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            append("📡 SIM: ")
            append(simBlock())
        }
    }

    private fun deviceBlock(): String = buildString {
        val p = prefs()
        appendLine("${s("sms_brand")}: ${Build.BRAND}")
        appendLine("${s("sms_model")}: ${Build.MODEL}")
        appendLine("${s("sms_android")}: ${Build.VERSION.RELEASE}")
        appendLine("${s("sms_android_id")}: ${androidId()}")
        val imei = p.getString("imei", "") ?: ""
        if (imei.isNotBlank()) appendLine("${s("sms_imei")}: $imei")
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
            appendLine("${s("sms_new_number")}: ${try { tm.line1Number ?: "N/A" } catch (e: Exception) { "N/A" }}")
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
        // Use cached location immediately if fresh enough (< 60 seconds old)
        val loc = lastLocation ?: getBestLastKnownLocation()
        if (loc == null) {
            // Try Network provider synchronously (cell towers / Wi-Fi)
            return try {
                val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                @Suppress("DEPRECATION")
                val netLoc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (netLoc != null) {
                    val mUrl = "https://maps.google.com/?q=${netLoc.latitude},${netLoc.longitude}"
                    "Network: ${"%.5f".format(netLoc.latitude)}, ${"%.5f".format(netLoc.longitude)}\nMap: $mUrl"
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

    private fun ts() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    private fun bat(): Int = (getSystemService(BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private fun chargingStr(): String {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return if (bm.isCharging) s("sms_charging_status") else s("sms_not_charging")
    }
    private fun androidId() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "N/A"

    /** Fix 5: Wait up to 15s for GPS fix before sending alert with location */
    private fun sendAlertWithLocation(emoji: String, description: String) {
        if (lastLocation != null) {
            sendAlert(emoji, description)
            return
        }
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
        p.edit().putInt("total_alerts", count).putString("last_alert", "$time — $title").apply()
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
        // Contact 2 is premium only
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
            Log.i(TAG, "SMS → $to")
        } catch (e: Exception) { Log.e(TAG, "SMS failed: ${e.message}") }
    }

    private fun buildNotif(): Notification {
        val p = prefs()
        val running = p.getBoolean("running", false)
        val theft   = theftMode()
        val (title, text) = when {
            theft   -> Pair("Protection Active", "Protection Status: Full Protection")
            running -> Pair("Protection Active", "Protection Status: Medium Protection")
            else    -> Pair("UNPROTECTED", "System Idle — Monitoring OFF.")
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
            // Fix 1: Kill notification completely
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") stopForeground(true)
                }
            } catch (e: Exception) {}
            try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (e: Exception) {}
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
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, s("app_name"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
