package com.thievestrap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.9.23: dedicated foreground service (foregroundServiceType="location") for Family Network live tracking.
 *
 * Why a service of its own: Android stops delivering background GPS to anything that is not a running
 * foreground service of type location, which left the watcher on "Waiting for the location of ..." even though
 * the target phone was online. This service:
 *   - promotes itself with an ongoing, non-dismissable notification (Stop button) and the explicit LOCATION type,
 *   - asks the fused provider for HIGH_ACCURACY fixes every 5 s (30 s once the phone stands still, see FamilyTracker),
 *   - holds a partial wake lock for the length of the session so CPU and network stay up with the screen locked,
 *   - hands every fix to FamilyTracker, which encrypts it and pushes it over the family WebSocket relay,
 *   - runs a watchdog: no fix for 40 s -> asks for a fresh one and re-checks the relay sockets.
 * It stops itself the moment the session is over (time out, disarmed, permission removed, Stop).
 */
class LocationTrackingService : Service() {

    private val TAG = "TT-LocService"
    private val handler = Handler(Looper.getMainLooper())
    private var callback: LocationCallback? = null
    private var currentMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var holdsSocket = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() first, always (the OS kills the app if a startForegroundService() call is not answered)
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) {                       // Stop button of the notification
            FamilyTracker.stop(this, "stopped"); stopSelf(); return START_NOT_STICKY
        }
        // also true for a null intent = the OS restarted us after killing the process
        if (!FamilyTracker.validate(this)) { stopSelf(); return START_NOT_STICKY }

        acquireWakeLock()
        if (!holdsSocket) { holdsSocket = true; try { FamilySocket.acquire(this) } catch (e: Exception) { holdsSocket = false } }
        val firstRun = callback == null
        applyInterval(FamilyTracker.desiredMs)
        if (firstRun) immediateFix()
        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, WATCHDOG_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) } catch (e: Exception) {} }
        callback = null
        if (holdsSocket) { holdsSocket = false; try { FamilySocket.release() } catch (e: Exception) {} }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ location

    private fun applyInterval(ms: Long) {
        if (callback != null && ms == currentMs) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        callback?.let { try { client.removeLocationUpdates(it) } catch (e: Exception) {} }
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { handleFix(it) } }
        }
        callback = cb; currentMs = ms
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
                .setMinUpdateIntervalMillis(ms / 2)
                .setWaitForAccurateLocation(false)
                .build()
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing")
            callback = null
            FamilyTracker.stop(this, "revoked"); stopSelf()
        }
    }

    private fun handleFix(loc: Location) {
        if (!FamilyTracker.onFix(this, loc)) { stopSelf(); return }
        applyInterval(FamilyTracker.desiredMs)           // switches between 5 s and 30 s
        updateNotification()
    }

    /** Do not wait for the first scheduled fix: send the freshest known position and ask for a new one at once. */
    private fun immediateFix() {
        try {
            val c = LocationServices.getFusedLocationProviderClient(this)
            c.lastLocation.addOnSuccessListener { l -> if (l != null && System.currentTimeMillis() - l.time < 120_000L) handleFix(l) }
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { l -> if (l != null) handleFix(l) }
        } catch (e: SecurityException) { Log.w(TAG, "no location permission for the immediate fix") }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (FamilyTracker.activePid(this@LocationTrackingService).isEmpty()) return
            val quiet = System.currentTimeMillis() - maxOf(FamilyTracker.lastFixAt, startedAt)
            if (quiet > QUIET_MS) {
                Log.w(TAG, "no fix for ${quiet / 1000}s: asking for a fresh one")
                try { FamilySocket.ensure(this@LocationTrackingService) } catch (e: Exception) {}
                currentMs = 0L; callback?.let { try { LocationServices.getFusedLocationProviderClient(this@LocationTrackingService).removeLocationUpdates(it) } catch (e: Exception) {} }
                callback = null
                applyInterval(FAST_FORCE)
                immediateFix()
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }
    private val startedAt = System.currentTimeMillis()

    // ------------------------------------------------------------------ wake lock

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val left = (FamilyTracker.sessionUntil(this) - System.currentTimeMillis() + 60_000L).coerceIn(60_000L, 125 * 60_000L)
            if (wakeLock?.isHeld == true) wakeLock?.release()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThievesTrap:LocationTracking").also { it.acquire(left) }   // bounded: never held forever
        } catch (e: Exception) { Log.w(TAG, "wake lock failed: ${e.message}") }
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
        true
    } catch (e: Exception) {
        // e.g. location permission missing, or Android refuses a background start: end the session honestly
        Log.e(TAG, "startForeground failed: ${e.message}")
        try { FamilyTracker.stop(this, "stopped") } catch (x: Exception) {}
        false
    }

    private fun updateNotification() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) } catch (e: Exception) {}
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        val until = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(FamilyTracker.sessionUntil(this)))
        val stop = PendingIntent.getService(this, 77, Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(Strings.get(this, "fam_trk_title"))
            .setContentText(String.format(Strings.get(this, "fam_trk_text"), FamilyTracker.activeName(this), until))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, Strings.get(this, "fam_trk_stop"), stop).build()
    }

    companion object {
        const val ACTION_STOP = "com.thievestrap.LOC_TRACK_STOP"
        private const val CHANNEL = "tt_family_track"
        private const val NOTIF_ID = 7900
        private const val WATCHDOG_MS = 20_000L
        private const val QUIET_MS = 40_000L
        private const val FAST_FORCE = FamilyTracker.FAST_MS

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, LocationTrackingService::class.java)) }
            catch (e: Exception) { Log.w("TT-LocService", "could not start: ${e.message}") }
        }

        fun stop(ctx: Context) {
            try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, LocationTrackingService::class.java)) } catch (e: Exception) {}
        }
    }
}
