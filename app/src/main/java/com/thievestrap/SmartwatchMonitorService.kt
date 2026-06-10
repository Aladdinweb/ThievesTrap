package com.thievestrap

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * SmartwatchMonitorService — v2.7.6
 * Monitors Bluetooth ACL connection to a paired smartwatch.
 * On disconnect: vibrates, locks screen, starts 5-min survival countdown.
 * On reconnect before expiry: aborts countdown automatically.
 */
class SmartwatchMonitorService : Service() {

    companion object {
        const val TAG               = "TT-Watch"
        const val NOTIF_ID          = 9020
        const val CHAN_ID           = "tt_watch"
        const val COUNTDOWN_NOTIF_ID = 9021
        const val COUNTDOWN_CHAN_ID  = "tt_watch_countdown"
        const val TETHER_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        const val ACTION_IM_SAFE     = "com.thievestrap.WATCH_IM_SAFE"
        const val ACTION_ALARM       = "com.thievestrap.WATCH_ALARM"
        const val ACTION_STOP_WATCH  = "com.thievestrap.WATCH_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, SmartwatchMonitorService::class.java)
            )
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, SmartwatchMonitorService::class.java))
        }
    }

    private val handler         = Handler(Looper.getMainLooper())
    private var countdownStart  = 0L
    private var countdownActive = false
    private var countdownRunnable: Runnable? = null
    private var aclReceiver: BroadcastReceiver? = null
    private var actionReceiver: BroadcastReceiver? = null

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildStatusNotif("Smartwatch Tether active — monitoring connection"))
        registerAclReceiver()
        registerActionReceiver()
        Log.i(TAG, "SmartwatchMonitorService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_IM_SAFE   -> handleImSafe()
            ACTION_ALARM     -> triggerAlarm()
            ACTION_STOP_WATCH -> { stopVibration(); cancelCountdown() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCountdown()
        stopVibration()
        try { aclReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        try { actionReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "SmartwatchMonitorService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Bluetooth ACL Receiver ────────────────────────────────

    private fun registerAclReceiver() {
        aclReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    @Suppress("DEPRECATION")
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
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        registerReceiver(aclReceiver, filter)
    }

    // ── Action Receiver (I'm Safe / Alarm / Stop) ─────────────

    private fun registerActionReceiver() {
        actionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_IM_SAFE    -> handleImSafe()
                    ACTION_ALARM      -> triggerAlarm()
                    ACTION_STOP_WATCH -> { stopVibration(); cancelCountdown() }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_IM_SAFE)
            addAction(ACTION_ALARM)
            addAction(ACTION_STOP_WATCH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }
    }

    // ── Loss Protocol ─────────────────────────────────────────

    private fun onWatchDisconnected() {
        // 1. Intense vibration
        startLossVibration()

        // 2. Lock screen immediately
        lockScreen()

        // 3. Show Loss Alert notification with ALARM + STOP buttons
        showLossAlertNotif()

        // 4. Start 5-minute countdown
        startCountdown()
    }

    private fun onWatchReconnected() {
        cancelCountdown()
        stopVibration()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(COUNTDOWN_NOTIF_ID)
        updateStatusNotif("Smartwatch Tether active — watch reconnected \u2705")
        Log.i(TAG, "Reconnection detected — emergency aborted")
    }

    private fun handleImSafe() {
        cancelCountdown()
        stopVibration()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(COUNTDOWN_NOTIF_ID)
        prefs().edit().putBoolean("watch_emergency_active", false).apply()
        updateStatusNotif("Smartwatch Tether active — safe confirmed")
        Log.i(TAG, "I'm Safe pressed — countdown cancelled")
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
                    // Timer expired — fire emergency SMS
                    countdownActive = false
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.cancel(COUNTDOWN_NOTIF_ID)
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

    // ── Emergency SMS ─────────────────────────────────────────

    private fun fireEmergencySms() {
        val p      = prefs()
        val target = p.getString("phone", "").orEmpty().trim()
        if (target.isBlank()) { Log.w(TAG, "No emergency contact"); return }

        val lat  = p.getFloat("last_lat",  0f)
        val lng  = p.getFloat("last_lng",  0f)
        val link = if (lat != 0f && lng != 0f)
            "https://maps.google.com/?q=$lat,$lng"
        else "Location unavailable"

        val msg = "\uD83D\uDEA8 THIEVES TRAP \u2014 WATCH TETHER ALERT\n" +
            "Phone was separated from paired smartwatch.\n" +
            "5-minute safety timer expired without confirmation.\n" +
            "\uD83D\uDCCD Location: $link\n" +
            "Sent automatically by Thieves Trap Security"

        Thread {
            try {
                val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    getSystemService(android.telephony.SmsManager::class.java)
                else @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                sm.sendMultipartTextMessage(target, null, sm.divideMessage(msg), null, null)
                Log.i(TAG, "Emergency SMS sent to $target")
            } catch (e: Exception) { Log.e(TAG, "SMS fail: ${e.message}") }
        }.start()

        // Also send to Telegram if premium
        if (LicenseManager.isPremium(this)) {
            TelegramUploader.sendMessage(this,
                "\uD83D\uDEA8 WATCH TETHER EXPIRED\n$msg")
        }
    }

    // ── Screen Lock ───────────────────────────────────────────

    private fun lockScreen() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.lockNow()
            Log.i(TAG, "Screen locked")
        } catch (e: Exception) { Log.e(TAG, "Lock failed: ${e.message}") }
    }

    // ── Vibration ─────────────────────────────────────────────

    private fun startLossVibration() = try {
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 1000, 300,
            500, 200, 500, 200, 500)  // intense repeating burst
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))
                .defaultVibrator
                .vibrate(VibrationEffect.createWaveform(pattern, 0)) // repeat from index 0
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
                .vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    } catch (e: Exception) { Log.e(TAG, "Vibration failed: ${e.message}") }

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
        Log.i(TAG, "Alarm triggered from watch tether")
    }

    // ── Notifications ─────────────────────────────────────────

    private fun showLossAlertNotif() {
        val alarmPi = PendingIntent.getService(
            this, 1,
            Intent(this, SmartwatchMonitorService::class.java).apply { action = ACTION_ALARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, SmartwatchMonitorService::class.java).apply { action = ACTION_STOP_WATCH },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, COUNTDOWN_CHAN_ID)
            .setContentTitle("\uD83D\uDEA8 LOSS ALERT — Watch Disconnected!")
            .setContentText("Phone separated from smartwatch. Emergency SMS in 5:00")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_lock_silent_mode_off, "ALARM", alarmPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPi)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(COUNTDOWN_NOTIF_ID, n)
    }

    private fun updateCountdownNotif(timeLeft: String) {
        val imSafePi = PendingIntent.getService(
            this, 0,
            Intent(this, SmartwatchMonitorService::class.java).apply { action = ACTION_IM_SAFE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmPi = PendingIntent.getService(
            this, 1,
            Intent(this, SmartwatchMonitorService::class.java).apply { action = ACTION_ALARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, COUNTDOWN_CHAN_ID)
            .setContentTitle("\uD83D\uDEA8 Watch Tether — Emergency in $timeLeft")
            .setContentText("Tap \u201cI\u2019m Safe\u201d to cancel \u2014 or reconnect your watch")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_menu_myplaces, "I'm Safe", imSafePi)
            .addAction(android.R.drawable.ic_lock_silent_mode_off, "ALARM", alarmPi)
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

    private fun updateStatusNotif(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildStatusNotif(text))
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHAN_ID, "Watch Tether Status",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            nm?.createNotificationChannel(
                NotificationChannel(COUNTDOWN_CHAN_ID, "Watch Loss Alert",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun prefs() = getSharedPreferences("tt_prefs", MODE_PRIVATE)
}
