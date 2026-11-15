package com.thievestrap

import android.content.Context
import android.location.Geocoder
import android.location.Location
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v2.9.18: location history of a paired phone, kept ONLY on the monitoring phone (app-private storage,
 * one small file per pair). The server never stores any history. A fix is recorded only when the phone
 * moved at least 20 m or 2 minutes passed, and the file is trimmed to its newest 400 entries.
 * Erased when the pair is removed.
 */
object FamilyHistory {

    class Fix(val ts: Long, val la: Double, val lo: Double, val ac: Float, val ad: String = "")

    private const val MAX_LINES = 600
    private const val KEEP_LINES = 400
    private val lastAdded = HashMap<String, Fix>()
    private val geo = Executors.newSingleThreadExecutor()

    private fun file(ctx: Context, pid: String) = File(ctx.filesDir, "family_loc_$pid.jsonl")

    @Synchronized
    fun add(ctx: Context, pid: String, f: Fix): Boolean {
        val prev = lastAdded[pid] ?: load(ctx, pid, 1).lastOrNull()
        if (prev != null) {
            val d = FloatArray(1)
            Location.distanceBetween(prev.la, prev.lo, f.la, f.lo, d)
            if (d[0] < 20f && f.ts - prev.ts < 120) return false
        }
        val fl = file(ctx, pid)
        fl.appendText(JSONObject().put("ts", f.ts).put("la", f.la).put("lo", f.lo).put("ac", f.ac.toDouble()).put("ad", f.ad).toString() + "\n")
        lastAdded[pid] = f
        val lines = fl.readLines()
        if (lines.size > MAX_LINES) fl.writeText(lines.takeLast(KEEP_LINES).joinToString("\n") + "\n")
        if (f.ad.isEmpty()) geo.execute {                       // look the street name up right away, in the background
            try { reverse(ctx, f.la, f.lo)?.let { setAddress(ctx, pid, f.ts, it) } } catch (e: Exception) { /* offline: filled in later */ }
        }
        return true
    }

    /** Street / area name of a position (Android's geocoder; it needs internet and sends the coordinates to its provider). */
    @Suppress("DEPRECATION")
    fun reverse(ctx: Context, la: Double, lo: Double): String? {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(ctx.applicationContext, Locale.getDefault()).getFromLocation(la, lo, 1)?.firstOrNull() ?: return null
        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").trim()
        val s = listOf(street, a.subLocality ?: "", a.locality ?: a.subAdminArea ?: "").filter { it.isNotBlank() }.distinct().joinToString(", ")
        return if (s.isNotBlank()) s else a.getAddressLine(0)
    }

    @Synchronized
    fun setAddress(ctx: Context, pid: String, ts: Long, address: String) {
        val fl = file(ctx)
        if (!fl.exists()) return
        val out = fl.readLines().map {
            try { val o = JSONObject(it); if (o.getLong("ts") == ts) o.put("ad", address).toString() else it } catch (e: Exception) { it }
        }
        fl.writeText(out.joinToString("\n") + "\n")
    }

    /** Fills in the names of recent points that have none yet (offline at the time). Returns how many were found. */
    fun resolveMissing(ctx: Context, pid: String, max: Int): Int {
        var n = 0
        for (f in load(ctx, pid, 60).filter { it.ad.isEmpty() }.takeLast(max)) {
            val a = try { reverse(ctx, f.la, f.lo) } catch (e: Exception) { null } ?: continue
            setAddress(ctx, pid, f.ts, a); n++
        }
        return n
    }

    /** Oldest first, at most `limit` newest entries. */
    @Synchronized
    fun load(ctx: Context, pid: String, limit: Int): List<Fix> {
        val fl = file(ctx, pid)
        if (!fl.exists()) return emptyList()
        return fl.readLines().takeLast(limit).mapNotNull {
            try { val o = JSONObject(it); Fix(o.getLong("ts"), o.getDouble("la"), o.getDouble("lo"), o.optDouble("ac", 0.0).toFloat(), o.optString("ad", "")) }
            catch (e: Exception) { null }
        }
    }

    @Synchronized
    fun clear(ctx: Context, pid: String) { lastAdded.remove(pid); file(ctx, pid).delete() }
}
