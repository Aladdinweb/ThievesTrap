package com.thievestrap

import android.app.*
import android.content.*
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat

class SurvivalTimerService : Service() {

    companion object {
        const val TAG          = "TT-Survival"
        const val NOTIF_ID     = 9010
        const val CHAN_ID      = "tt_survival"
        const val ACTION_PING  = "com.thievestrap.SURVIVAL_PING"
        const val ACTION_STOP  = "com.thievestrap.SURVIVAL_STOP"
        const val WARN_5MIN    = 5 * 60 * 1000L
        const val WARN_1MIN    = 1 * 60 * 1000L
        const val SMS2_DELAY   = 15 * 60 * 1000L

        fun start(context: Context, durationMs: Long) {
            val i = Intent(context, SurvivalTimerService::class.java).apply {
                putExtra("duration", durationMs)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
        fun stop(context: Context) =
            context.stopService(Intent(context, SurvivalTimerService::class.java))

        // FIX 6: ACTION_IM_SAFE now handled directly by service — no PIN required
        const val ACTION_IM_SAFE = "com.thievestrap.IM_SAFE"
    }

    private val handler     = Handler(Looper.getMainLooper())
    private var durationMs  = 0L
    private var warnBefore  = WARN_5MIN
    private var startMs     = 0L
    private var warned      = false
    private var sms1Sent    = false
    private var sms2Sent    = false

    // FIX 6: Internal BroadcastReceiver to handle I'm Safe without PIN dialog
    private val imSafeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_IM_SAFE) {
                Log.i(TAG, "I'm Safe received — stopping timer immediately")
                autoStop()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val elapsed   = System.currentTimeMillis() - startMs
            val remaining = durationMs - elapsed
            Log.d(TAG, "Remaining: ${remaining/1000}s")
            when {
                remaining <= 0 && !sms1Sent -> {
                    sms1Sent = true
                    sendSurvivalSMS(1)
                    updateNotif("SOS SENT — awaiting confirmation")
                    handler.postDelayed({
                        if (!sms2Sent) { sms2Sent = true; sendSurvivalSMS(2) }
                        autoStop()
                    }, SMS2_DELAY)
                }
                remaining in 1..(warnBefore + 1000) && !warned -> {
                    warned = true
                    vibrate5Min()
                    updateNotif("Survival Timer: ${warnBefore/60000} minute(s) remaining — confirm you are safe!")
                    handler.postDelayed(this, 10_000)
                }
                remaining > 0 -> {
                    val mins = (remaining / 60000).toInt()
                    val secs = ((remaining % 60000) / 1000).toInt()
                    updateNotif("Survival Timer: %02d:%02d remaining".format(mins, secs))
                    handler.postDelayed(this, 10_000)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("Survival Timer active"))
        // FIX 6: Register I'm Safe receiver locally — no PIN dialog, instant stop
        val filter = IntentFilter(ACTION_IM_SAFE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(imSafeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(imSafeReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FIX 6: Handle I'm Safe action sent directly to service via intent
        if (intent?.action == ACTION_IM_SAFE) {
            Log.i(TAG, "I'm Safe via onStartCommand — stopping timer immediately")
            autoStop()
            return START_NOT_STICKY
        }
        durationMs = intent?.getLongExtra("duration", 30 * 60 * 1000L) ?: (30 * 60 * 1000L)
        warnBefore = if (durationMs <= WARN_5MIN) WARN_1MIN else WARN_5MIN
        startMs    = System.currentTimeMillis()
        warned     = false; sms1Sent = false; sms2Sent = false
        handler.post(tick)
        Log.i(TAG, "Survival timer started: ${durationMs/60000} min")
        return START_STICKY
    }

    private fun sendSurvivalSMS(smsNum: Int) {
        val prefs  = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val target = prefs.getString("survival_recipient", "").orEmpty()
            .ifBlank { prefs.getString("phone", "").orEmpty() }.trim()
        if (target.isBlank()) { Log.w(TAG, "No survival recipient"); return }

        val lat  = prefs.getFloat("last_lat", 0f)
        val lng  = prefs.getFloat("last_lng", 0f)
        val link = if (lat != 0f && lng != 0f)
            "https://maps.google.com/?q=$lat,$lng" else "Location unavailable"
        val lang = prefs.getString("language", "en") ?: "en"
        val msg = when (lang) {
            "ar" -> "\uD83D\uDEA8 \u062A\u0646\u0628\u064A\u0647 \u0637\u0648\u0627\u0631\u0626 - Thieves Trap \uD83D\uDEA8" +
                "\n\u0627\u0646\u062A\u0647\u0649 \u0645\u0624\u0642\u062A Survival Timer \u0648\u0644\u0645 \u064A\u062A\u0645 \u062A\u0623\u0643\u064A\u062F \u0627\u0644\u0633\u0644\u0627\u0645\u0629." +
                "\n\uD83D\uDCCD \u0627\u0644\u0645\u0648\u0642\u0639: $link" +
                (if (smsNum == 2) "\n[\u062A\u062D\u062F\u064A\u062B 2]" else "") +
                "\n\uD83D\uDD0D \u0645\u0644\u0627\u062D\u0638\u0629: \u0644\u0644\u062A\u062A\u0628\u0639 \u0623\u0631\u0633\u0644: WHERE"
            "fr" -> "\uD83D\uDEA8 Alerte urgence - Thieves Trap \uD83D\uDEA8" +
                "\nSurvival Timer expire sans confirmation de securite." +
                "\n\uD83D\uDCCD Position: $link" +
                (if (smsNum == 2) "\n[Mise a jour 2]" else "") +
                "\n\uD83D\uDD0D Note: Suivez en envoyant: WHERE"
            else  -> "\uD83D\uDEA8 Emergency Alert - Thieves Trap \uD83D\uDEA8" +
                "\nSurvival Timer expired without safety confirmation." +
                "\n\uD83D\uDCCD Location: $link" +
                (if (smsNum == 2) "\n[Update 2]" else "") +
                "\n\uD83D\uDD0D Note: Track by sending: WHERE"
        }

        Thread {
            try {
                val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sm.sendMultipartTextMessage(target, null, sm.divideMessage(msg), null, null)
                Log.i(TAG, "Survival SMS $smsNum sent to $target")
            } catch (e: Exception) { Log.e(TAG, "SMS fail: ${e.message}") }
        }.start()
    }

    private fun vibrate5Min() = try {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } catch (e: Exception) {}

    private fun stopVibration() = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java)).defaultVibrator.cancel()
        else @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).cancel()
    } catch (e: Exception) {}

    private fun autoStop() {
        val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("survival_timer_on", false).apply()
        sendBroadcast(Intent("com.thievestrap.SETTINGS_REFRESH"))
        stopVibration()
        stopSelf()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        // FIX 6: PendingIntent sends ACTION_IM_SAFE directly to this service — no PIN dialog
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, SurvivalTimerService::class.java).apply {
                action = ACTION_IM_SAFE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHAN_ID)
            .setContentTitle("Thieves Trap — Survival Timer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_myplaces, "I'm Safe", pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHAN_ID, "Survival Timer",
                NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopVibration()
        try { unregisterReceiver(imSafeReceiver) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
