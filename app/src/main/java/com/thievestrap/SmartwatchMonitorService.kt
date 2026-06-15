package com.thievestrap

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * SmartwatchMonitorService — v2.7.9b
 *
 * Retained: BLE ACL monitoring, 5-min countdown, "I'm Safe" no-PIN,
 * emergency SMS on expiry, screen lock on disconnect.
 *
 * v2.7.9b additions:
 *  - Loss alert notification now shows:
 *      "🚨 TRIGGER ALARM"  — fires AlarmService at max volume
 *      "🔕 DISARM / MUTE"  — cancels countdown + mutes alarm
 */
class SmartwatchMonitorService : Service() {

    companion object {
        const val TAG                = "TT-Watch"
        const val NOTIF_ID           = 9020
        const val CHAN_ID            = "tt_watch"
        const val COUNTDOWN_NOTIF_ID = 9021
        const val COUNTDOWN_CHAN_ID  = "tt_watch_countdown"
        const val TETHER_DURATION_MS = 5 * 60 * 1000L

        const val ACTION_IM_SAFE    = "com.thievestrap.WATCH_IM_SAFE"
        const val ACTION_ALARM      = "com.thievestrap.WATCH_ALARM"
        // v2.7.9b: DISARM/MUTE — cancels countdown AND stops alarm
        const val ACTION_DISARM_MUTE = "com.thievestrap.WATCH_DISARM_MUTE"
        const val ACTION_STOP_WATCH = "com.thievestrap.WATCH_STOP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(
                context, Intent(context, SmartwatchMonitorService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, SmartwatchMonitorService::class.java))
    }

    private val handler          = Handler(Looper.getMainLooper())
    private var countdownStart   = 0L
    private var countdownActive  = false
    private var countdownRunnable: Runnable? = null
    private var aclReceiver: BroadcastReceiver? = null
    private var actionReceiver: BroadcastReceiver? = null

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID,
            buildStatusNotif("Smartwatch Tether active — monitoring Bluetooth connection"))
        registerAclReceiver()
        registerActionReceiver()
        Log.i(TAG, "SmartwatchMonitorService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_IM_SAFE    -> handleImSafe()
            ACTION_ALARM      -> triggerAlarm()
            ACTION_DISARM_MUTE -> handleDisarmMute()
            ACTION_STOP_WATCH -> { stopVibration(); cancelCountdown() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCountdown()
        stopVibration()
        try { aclReceiver?.let { unregisterReceiver(it) } }    catch (e: Exception) {}
        try { actionReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "SmartwatchMonitorService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Bluetooth ACL receiver ────────────────────────────────

    private fun registerAclReceiver() {
        aclReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                Log.i(TAG, "BT event: ${intent.action} | device: ${device?.name}")

                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (!countdownActive) {
                            Log.i(TAG, "Watch disconnected — starting loss protocol")
                            onWatchDisconnected()
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (countdownActive) {
                            Log.i(TAG, "Watch reconnected — aborting countdown")
                            onWatchReconnected()
                        }
                    }
                }
            }
        }
        registerReceiver(aclReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        })
    }

    // ── Action receiver (notification buttons) ────────────────

    private fun registerActionReceiver() {
        actionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_IM_SAFE     -> handleImSafe()
                    ACTION_ALARM       -> triggerAlarm()
                    ACTION_DISARM_MUTE -> handleDisarmMute()
                    ACTION_STOP_WATCH  -> { stopVibration(); cancelCountdown() }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(ACTION_IM_SAFE)
            addAction(ACTION_ALARM)
            addAction(ACTION_DISARM_MUTE)
            addAction(ACTION_STOP_WATCH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(actionReceiver, f, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(actionReceiver, f)
    }

    // ── Loss protocol ─────────────────────────────────────────

    private fun onWatchDisconnected() {
        startLossVibration()
        lockScreen()
        showLossAlertNotif()
        startCountdown()
    }

    private fun onWatchReconnected() {
        cancelCountdown()
        stopVibration()
        getSystemService(NotificationManager::class.java)?.cancel(COUNTDOWN_NOTIF_ID)
        updateStatusNotif("Smartwatch Tether — watch reconnected \u2705")
    }

    // ── I'm Safe — NO PIN required ────────────────────────────

    private fun handleImSafe() {
        cancelCountdown()
        stopVibration()
        getSystemService(NotificationManager::class.java)?.cancel(COUNTDOWN_NOTIF_ID)
        prefs().edit().putBoolean("watch_emergency_active", false).apply()
        updateStatusNotif("Smartwatch Tether active — safe confirmed")
        Log.i(TAG, "I'm Safe — countdown cancelled, no PIN needed")
    }

    // ── v2.7.9b: DISARM / MUTE — cancels countdown AND stops alarm ──

    private fun handleDisarmMute() {
        cancelCountdown()
        stopVibration()
        // Stop the alarm siren if it's playing
        try {
            startService(Intent(this, AlarmService::class.java).apply {
                action = "STOP_ALARM"
            })
        } catch (e: Exception) {}
        getSystemService(NotificationManager::class.java)?.cancel(COUNTDOWN_NOTIF_ID)
        prefs().edit().putBoolean("watch_emergency_active", false).apply()
        updateStatusNotif("Smartwatch Tether — disarmed & muted")
        Log.i(TAG, "DISARM/MUTE — countdown cancelled, alarm stopped")
    }

    // ── Countdown ─────────────────────────────────────────────

    private fun startCountdown() {
        countdownActive = true
        countdownStart  = System.currentTimeMillis()
        prefs().edit().putBoolean("watch_emergency_active", true).apply()

        countdownRunnable = object : Runnable {
            override fun run() {
                val elapsed   = System.currentTimeMillis() - countdownStart
                val remaining = TETHER_DURATION_MS - elapsed
                if (remaining <= 0) {
                    countdownActive = false
                    getSystemService(NotificationManager::class.java)?.cancel(COUNTDOWN_NOTIF_ID)
                    fireEmergencySms()
                    prefs().edit().putBoolean("watch_emergency_active", false).apply()
                    return
                }
                val mins = (remaining / 60000).toInt()
                val secs = ((remaining % 60000) / 1000).toInt()
                updateCountdownNotif("%02d:%02d".format(mins, secs))
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(countdownRunnable!!)
    }

    private fun cancelCountdown() {
        countdownActive = false
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    // ── Emergency SMS on expiry ───────────────────────────────

    private fun fireEmergencySms() {
        val p      = prefs()
        val target = p.getString("phone", "").orEmpty().trim()
        if (target.isBlank()) { Log.w(TAG, "No emergency contact"); return }

        val lat  = p.getFloat("last_lat",  0f)
        val lng  = p.getFloat("last_lng",  0f)
        val link = if (lat != 0f && lng != 0f)
            "https://maps.google.com/?q=$lat,$lng" else "Location unavailable"

        val msg = "\uD83D\uDEA8 THIEVES TRAP \u2014 WATCH TETHER ALERT\n" +
            "Phone separated from paired smartwatch.\n" +
            "5-minute safety countdown expired without confirmation.\n" +
            "\uD83D\uDCCD Location: $link\n" +
            "Sent automatically by Thieves Trap Security"

        Thread {
            try {
                val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    getSystemService(android.telephony.SmsManager::class.java)
                else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                sm.sendMultipartTextMessage(target, null, sm.divideMessage(msg), null, null)
                Log.i(TAG, "Emergency SMS sent to $target")
            } catch (e: Exception) { Log.e(TAG, "SMS fail: ${e.message}") }
        }.start()

        if (LicenseManager.isPremium(this))
            TelegramUploader.sendMessage(this, "\uD83D\uDEA8 WATCH TETHER EXPIRED\n$msg")
    }

    // ── Screen lock ───────────────────────────────────────────

    private fun lockScreen() {
        try {
            (getSystemService(DEVICE_POLICY_SERVICE) as
                android.app.admin.DevicePolicyManager).lockNow()
            Log.i(TAG, "Screen locked via DPM")
        } catch (e: Exception) { Log.e(TAG, "Lock failed: ${e.message}") }
    }

    // ── Vibration ─────────────────────────────────────────────

    private fun startLossVibration() = try {
        val pattern = longArrayOf(0,600,200,600,200,600,300,1000,400,600,200,600,200,600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java)).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(pattern, 0))
        else @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
                .vibrate(VibrationEffect.createWaveform(pattern, 0))
    } catch (e: Exception) { Log.e(TAG, "Vibration error: ${e.message}") }

    private fun stopVibration() = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java)).defaultVibrator.cancel()
        else @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).cancel()
    } catch (e: Exception) {}

    // ── Alarm ─────────────────────────────────────────────────

    private fun triggerAlarm() {
        ContextCompat.startForegroundService(this,
            Intent(this, AlarmService::class.java).apply { action = "START_ALARM" })
        Log.i(TAG, "Phone alarm triggered from watch tether")
    }

    // ── Notifications ─────────────────────────────────────────
    // v2.7.9b: Loss alert now shows "🚨 TRIGGER ALARM" and "🔕 DISARM / MUTE"

    private fun showLossAlertNotif() {
        val alarmPi     = pendingServiceIntent(1, ACTION_ALARM)
        val disarmMutePi = pendingBroadcastIntent(2, ACTION_DISARM_MUTE)

        val n = NotificationCompat.Builder(this, COUNTDOWN_CHAN_ID)
            .setContentTitle("\uD83D\uDEA8 LOSS ALERT \u2014 Watch Disconnected!")
            .setContentText("Phone separated from smartwatch. Emergency SMS in 5:00")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_lock_silent_mode_off,
                "\uD83D\uDEA8 TRIGGER ALARM", alarmPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "\uD83D\uDD15 DISARM / MUTE", disarmMutePi)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(COUNTDOWN_NOTIF_ID, n)
    }

    private fun updateCountdownNotif(timeLeft: String) {
        val imSafePi    = pendingServiceIntent(0, ACTION_IM_SAFE)
        val alarmPi     = pendingServiceIntent(1, ACTION_ALARM)
        val disarmMutePi = pendingBroadcastIntent(2, ACTION_DISARM_MUTE)

        val n = NotificationCompat.Builder(this, COUNTDOWN_CHAN_ID)
            .setContentTitle("\uD83D\uDEA8 Watch Tether \u2014 Emergency in $timeLeft")
            .setContentText("Reconnect your watch or tap below")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_menu_myplaces,
                "\u2705 I'm Safe", imSafePi)
            .addAction(android.R.drawable.ic_lock_silent_mode_off,
                "\uD83D\uDEA8 TRIGGER ALARM", alarmPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "\uD83D\uDD15 DISARM / MUTE", disarmMutePi)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(COUNTDOWN_NOTIF_ID, n)
    }

    private fun buildStatusNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHAN_ID)
            .setContentTitle("Thieves Trap \u2014 Watch Tether")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

    private fun updateStatusNotif(text: String) =
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildStatusNotif(text))

    /** Service intent for actions that need onStartCommand routing */
    private fun pendingServiceIntent(reqCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, reqCode,
            Intent(this, SmartwatchMonitorService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Broadcast intent for quick-action buttons (fires actionReceiver) */
    private fun pendingBroadcastIntent(reqCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            this, reqCode + 100,
            Intent(action).apply { setPackage(packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHAN_ID, "Watch Tether Status",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); enableVibration(false)
                })
            nm?.createNotificationChannel(
                NotificationChannel(COUNTDOWN_CHAN_ID, "Watch Loss Alert",
                    NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun prefs() = getSharedPreferences("tt_prefs", MODE_PRIVATE)
}
