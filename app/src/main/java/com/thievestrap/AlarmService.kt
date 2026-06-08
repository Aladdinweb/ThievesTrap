package com.thievestrap

import android.app.*
import android.os.Build
import android.util.Log
import android.content.Context
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AlarmService : Service() {

    private val CHANNEL_ID = "tt_alarm"
    private val NOTIF_ID = 99
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var originalVolume = 0
    private var originalRingerMode = AudioManager.RINGER_MODE_NORMAL

    // Volume enforcement runnable — forces alarm volume back to max if lowered
    private val volumeEnforcer = object : Runnable {
        override fun run() {
            try {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                if (curVol < maxVol) {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 500)  // check every 500ms
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_ALARM" -> startAlarm()
            "STOP_ALARM"  -> stopAlarm()
        }
        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { super.onDestroy(); stopAlarm() }

    private fun startAlarm() {
        startForeground(NOTIF_ID, buildNotif())

        // Save state for restoration
        originalRingerMode = audioManager.ringerMode
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        // Force out of silent/DND — USAGE_ALARM bypasses this on most devices
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        } catch (e: Exception) {}

        // Set STREAM_ALARM to max volume
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (e: Exception) {}

        // Start volume enforcement loop
        handler.post(volumeEnforcer)

        // Use system ringtone at max volume via STREAM_ALARM (bypasses silent/DND)
        try {
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_ALARM)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f)  // Maximum output volume
                prepare()
                start()
            }
            Log.i("TT-Alarm", "Playing system ringtone at max volume via STREAM_ALARM")
        } catch (e: Exception) {
            Log.e("TT-Alarm", "MediaPlayer failed: ${e.message}")
            // Fallback: RingtoneManager API
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
            } catch (e2: Exception) { Log.e("TT-Alarm", "Fallback failed: ${e2.message}") }
        }

        // Vibration pattern
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 400, 200, 400, 200, 1000, 500), 0))
        } catch (e: Exception) {}

        // Auto-stop after 5 minutes
        handler.postDelayed({ stopAlarm() }, 5 * 60 * 1000L)
    }

    private fun stopAlarm() {
        handler.removeCallbacksAndMessages(null)
        try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null } catch (e: Exception) {}
        try { vibrator?.cancel() } catch (e: Exception) {}

        // Restore original ringer state
        try { audioManager.ringerMode = originalRingerMode } catch (e: Exception) {}
        try { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0) } catch (e: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotif(): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" },
            PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALARM — THIEVES TRAP")
            .setContentText("Intruder alert! Tap Stop to silence.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Thieves Trap Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, AlarmService::class.java).apply { action = "START_ALARM" })
        }
        fun stop(context: Context) {
            context.startService(Intent(context, AlarmService::class.java)
                .apply { action = "STOP_ALARM" })
        }
    }
}
