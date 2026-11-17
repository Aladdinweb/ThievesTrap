package com.thievestrap

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * v2.9.18 (Family Bridge step 4), TARGET side: shares this phone's live location with ONE paired phone.
 *
 * v2.9.23: the GPS work, the wake lock and the persistent notification moved out of this object into the
 * dedicated foreground service [LocationTrackingService] (foregroundServiceType="location"), so sharing no
 * longer depends on MonitorService staying in the foreground. This object keeps the SESSION rules:
 * it runs only when ALL of these hold, and ends itself the moment one fails:
 *   - this phone is ARMED (pref running),
 *   - the pair's "See my location" permission (set by the owner of THIS phone) is on,
 *   - a session was started by the other phone with an encrypted "track_start" command, and its time
 *     (60 minutes by default, 120 at most) has not run out.
 *
 * Cadence: a fix every 5 s while moving (speed >= 0.8 m/s or moved > 15 m), every 30 s once three fixes in a
 * row were stationary. Each fix is encrypted with the pair key and pushed over the live WebSocket (HTTP as
 * fallback) into the server's single "latest location" slot, which the next fix overwrites: no history.
 */
object FamilyTracker {

    private const val TAG = "FamilyTracker"
    const val FAST_MS = 5_000L
    const val SLOW_MS = 30_000L
    private val io = Executors.newSingleThreadExecutor()

    /** Interval the service should currently request; the service re-registers when it changes. */
    @Volatile var desiredMs = FAST_MS
    /** Wall-clock time of the last fix handed to [onFix]; the service watchdog reads it. */
    @Volatile var lastFixAt = 0L
    private var still = 0
    private var last: Location? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tt_family", Context.MODE_PRIVATE)
    private fun armed(ctx: Context) = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)

    fun activePid(ctx: Context): String = sp(ctx).getString("trk_pid", "") ?: ""
    fun sessionUntil(ctx: Context): Long = sp(ctx).getLong("trk_until", 0L)
    fun activeName(ctx: Context): String = FamilyBridge.pairs(ctx).firstOrNull { it.pid == activePid(ctx) }?.name ?: "?"

    @Synchronized
    fun start(ctx: Context, pr: FamilyBridge.FamilyPair, minutes: Int) {
        val app = ctx.applicationContext
        sp(app).edit().putString("trk_pid", pr.pid).putLong("trk_until", System.currentTimeMillis() + minutes * 60_000L).apply()
        still = 0; last = null; desiredMs = FAST_MS; lastFixAt = 0L
        FamilyAudit.add(app, "track_on", pr.name)
        LocationTrackingService.start(app)           // GPS + notification + wake lock live in the service
        // tell the watcher if this phone only allows location "while the app is in use" (tracking can then stop after a restart)
        status(app, pr.pid, if (BackgroundLocation.isGranted(app)) "started" else "started_limited")
    }

    /** Ends the session. `reason` is told to the other phone (stopped / expired / disarmed / revoked); null = silent cleanup. */
    @Synchronized
    fun stop(ctx: Context, reason: String?) {
        val app = ctx.applicationContext
        val pid = activePid(app)
        LocationTrackingService.stop(app)
        sp(app).edit().remove("trk_pid").remove("trk_until").apply()
        if (pid.isNotEmpty()) {
            FamilyAudit.add(app, "track_off", FamilyBridge.pairs(app).firstOrNull { it.pid == pid }?.name ?: "?", reason ?: "ended")
            if (reason != null) status(app, pid, reason) else io.execute { FamilyBridge.clearLoc(pid) }
        }
    }

    /** Stops the service but keeps the session (used when MonitorService goes away for a restart). */
    @Synchronized
    fun pause(ctx: Context) { LocationTrackingService.stop(ctx.applicationContext) }

    /**
     * True when a session exists and may keep running; otherwise ends it with the right reason (and returns false).
     * Called by the service on every (re)start, so a restart by the OS cannot revive a dead session.
     */
    @Synchronized
    fun validate(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val pid = activePid(app)
        if (pid.isEmpty()) return false
        val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid }
        return when {
            pr == null -> { stop(app, null); false }
            System.currentTimeMillis() > sessionUntil(app) -> { stop(app, "expired"); false }
            !armed(app) -> { stop(app, "disarmed"); false }
            !pr.location -> { stop(app, "revoked"); false }
            else -> true
        }
    }

    /** Called when MonitorService starts: continue a session that was interrupted, or end it if it can no longer run. */
    @Synchronized
    fun resumeIfNeeded(ctx: Context) {
        val app = ctx.applicationContext
        if (activePid(app).isEmpty()) return
        if (validate(app)) { still = 0; last = null; desiredMs = FAST_MS; LocationTrackingService.start(app) }
    }

    /**
     * One fix from the service. Returns false when the session is over (already ended and told to the watcher),
     * in which case the service stops itself.
     */
    fun onFix(app: Context, loc: Location): Boolean {
        val pid = activePid(app)
        if (pid.isEmpty()) return false
        if (System.currentTimeMillis() > sessionUntil(app)) { stop(app, "expired"); return false }
        if (!armed(app)) { stop(app, "disarmed"); return false }
        val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid }
        if (pr == null) { stop(app, null); return false }
        if (!pr.location) { stop(app, "revoked"); return false }

        lastFixAt = System.currentTimeMillis()
        val prev = last; last = loc
        val moving = (loc.hasSpeed() && loc.speed >= 0.8f) || (prev != null && loc.distanceTo(prev) > 15f)
        if (moving) { still = 0; desiredMs = FAST_MS }
        else { still++; if (still >= 3) desiredMs = SLOW_MS }

        val bt = try { (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 }
        val payload = JSONObject().put("la", loc.latitude).put("lo", loc.longitude).put("ac", loc.accuracy.toDouble())
            .put("sp", if (loc.hasSpeed()) loc.speed.toDouble() else 0.0).put("ts", System.currentTimeMillis() / 1000)
            .put("bt", bt).put("mv", moving).toString()
        send(app, pid, payload)
        return true
    }

    private fun send(app: Context, pid: String, payload: String) {
        io.execute {
            try {
                val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid } ?: return@execute
                val key = FamilyBridge.pairKey(app, pr) ?: return@execute
                val ct = FamilyBridge.seal(key, "loc|$pid", payload.toByteArray())
                // v2.9.23: live WebSocket first (no new TLS handshake per fix, works on a flaky link); HTTP if it is down
                if (!FamilySocket.sendLoc(pid, ct)) FamilyBridge.putLoc(pid, ct)
            } catch (e: Exception) { Log.w(TAG, "send failed: ${e.message}") }
        }
    }

    /** A status line (not a location) for the monitor: started / not_armed / not_allowed / stopped / ... */
    fun status(app: Context, pid: String, st: String) =
        send(app, pid, JSONObject().put("st", st).put("ts", System.currentTimeMillis() / 1000).toString())
}
