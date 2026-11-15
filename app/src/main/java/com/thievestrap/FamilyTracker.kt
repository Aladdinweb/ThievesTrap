package com.thievestrap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v2.9.18 (Family Bridge step 4), TARGET side: shares this phone's live location with ONE paired phone.
 *
 * It runs only when ALL of these hold, and stops itself the moment one fails:
 *   - this phone is ARMED (pref running) and MonitorService is alive,
 *   - the pair's "See my location" permission (set by the owner of THIS phone) is on,
 *   - a session was started by the other phone with an encrypted "track_start" command, and its time
 *     (60 minutes by default, 120 at most) has not run out.
 * While sharing, a persistent notification is shown (and refreshed on every fix) with a Stop button.
 *
 * Battery: a fix every 5 s while moving (speed >= 0.8 m/s or moved > 15 m), every 30 s once three fixes
 * in a row were stationary. Each fix is encrypted with the pair key and put into the server's single
 * "latest location" slot, which the next fix overwrites: the server never holds a history.
 */
object FamilyTracker {

    private const val TAG = "FamilyTracker"
    private const val CHANNEL = "tt_family_track"
    private const val NOTIF_ID = 7900
    private const val FAST_MS = 5_000L
    private const val SLOW_MS = 30_000L
    private val io = Executors.newSingleThreadExecutor()
    private var callback: LocationCallback? = null
    private var intervalMs = 0L
    private var still = 0
    private var last: Location? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tt_family", Context.MODE_PRIVATE)
    private fun armed(ctx: Context) = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)

    fun activePid(ctx: Context): String = sp(ctx).getString("trk_pid", "") ?: ""

    @Synchronized
    fun start(ctx: Context, pr: FamilyBridge.FamilyPair, minutes: Int) {
        val app = ctx.applicationContext
        sp(app).edit().putString("trk_pid", pr.pid).putLong("trk_until", System.currentTimeMillis() + minutes * 60_000L).apply()
        still = 0; last = null
        FamilyAudit.add(app, "track_on", pr.name)
        begin(app, FAST_MS)
        notifyActive(app, pr.name)
        // tell the watcher if this phone only allows location "while the app is in use" (tracking can then stop after a restart)
        status(app, pr.pid, if (BackgroundLocation.isGranted(app)) "started" else "started_limited")
        // v2.9.19: do not wait for the first scheduled fix: send the freshest known position right away
        try {
            val c = LocationServices.getFusedLocationProviderClient(app)
            c.lastLocation.addOnSuccessListener { l -> if (l != null && System.currentTimeMillis() - l.time < 120_000L) onFix(app, l) }
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { l -> if (l != null) onFix(app, l) }
        } catch (e: SecurityException) { Log.w(TAG, "no location permission for the immediate fix") }
    }

    /** Ends the session. `reason` is told to the other phone (stopped / expired / disarmed / revoked); null = silent cleanup. */
    @Synchronized
    fun stop(ctx: Context, reason: String?) {
        val app = ctx.applicationContext
        val pid = activePid(app)
        pause(app)
        sp(app).edit().remove("trk_pid").remove("trk_until").apply()
        if (pid.isNotEmpty()) {
            FamilyAudit.add(app, "track_off", FamilyBridge.pairs(app).firstOrNull { it.pid == pid }?.name ?: "?", reason ?: "ended")
            if (reason != null) status(app, pid, reason) else io.execute { FamilyBridge.clearLoc(pid) }
        }
    }

    /** Stops asking for GPS and removes the notification, but keeps the session (used when the service goes away). */
    @Synchronized
    fun pause(ctx: Context) {
        val app = ctx.applicationContext
        callback?.let { try { LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(it) } catch (e: Exception) {} }
        callback = null
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }

    /** Called when MonitorService starts: continue a session that was interrupted, or end it if it can no longer run. */
    @Synchronized
    fun resumeIfNeeded(ctx: Context) {
        val app = ctx.applicationContext
        val pid = activePid(app)
        if (pid.isEmpty() || callback != null) return
        val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid }
        when {
            pr == null -> stop(app, null)
            System.currentTimeMillis() > sp(app).getLong("trk_until", 0L) -> stop(app, "expired")
            !armed(app) -> stop(app, "disarmed")
            !pr.location -> stop(app, "revoked")
            else -> { still = 0; last = null; begin(app, FAST_MS); notifyActive(app, pr.name) }
        }
    }

    @Synchronized
    private fun begin(app: Context, ms: Long) {
        callback?.let { try { LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(it) } catch (e: Exception) {} }
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { onFix(app, it) } }
        }
        callback = cb
        intervalMs = ms
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms).setMinUpdateIntervalMillis(ms / 2).build()
            LocationServices.getFusedLocationProviderClient(app).requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) { Log.w(TAG, "location permission missing"); stop(app, "revoked") }
    }

    private fun onFix(app: Context, loc: Location) {
        val pid = activePid(app)
        if (pid.isEmpty()) { pause(app); return }
        if (System.currentTimeMillis() > sp(app).getLong("trk_until", 0L)) { stop(app, "expired"); return }
        if (!armed(app)) { stop(app, "disarmed"); return }
        val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid }
        if (pr == null) { stop(app, null); return }
        if (!pr.location) { stop(app, "revoked"); return }

        val prev = last; last = loc
        val moving = (loc.hasSpeed() && loc.speed >= 0.8f) || (prev != null && loc.distanceTo(prev) > 15f)
        if (moving) { still = 0; if (intervalMs != FAST_MS) begin(app, FAST_MS) }
        else { still++; if (still >= 3 && intervalMs != SLOW_MS) begin(app, SLOW_MS) }

        val bt = try { (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 }
        val payload = JSONObject().put("la", loc.latitude).put("lo", loc.longitude).put("ac", loc.accuracy.toDouble())
            .put("sp", if (loc.hasSpeed()) loc.speed.toDouble() else 0.0).put("ts", System.currentTimeMillis() / 1000)
            .put("bt", bt).put("mv", moving).toString()
        send(app, pid, payload)
        notifyActive(app, pr.name)
    }

    private fun send(app: Context, pid: String, payload: String) {
        io.execute {
            try {
                val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid } ?: return@execute
                val key = FamilyBridge.pairKey(app, pr) ?: return@execute
                FamilyBridge.putLoc(pid, FamilyBridge.seal(key, "loc|$pid", payload.toByteArray()))
            } catch (e: Exception) { Log.w(TAG, "send failed: ${e.message}") }
        }
    }

    /** A status line (not a location) for the monitor: started / not_armed / not_allowed / stopped / ... */
    fun status(app: Context, pid: String, st: String) =
        send(app, pid, JSONObject().put("st", st).put("ts", System.currentTimeMillis() / 1000).toString())

    private fun notifyActive(app: Context, name: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        val until = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sp(app).getLong("trk_until", 0L)))
        val stop = PendingIntent.getService(app, 77, Intent(app, MonitorService::class.java).setAction("FAMILY_TRACK_STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(Strings.get(app, "fam_trk_title"))
            .setContentText(String.format(Strings.get(app, "fam_trk_text"), name, until))
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, Strings.get(app, "fam_trk_stop"), stop).build()
        try { nm.notify(NOTIF_ID, n) } catch (e: Exception) {}
    }
}
