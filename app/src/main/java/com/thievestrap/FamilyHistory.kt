package com.thievestrap

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.io.File

/**
 * v2.9.18: location history of a paired phone, kept ONLY on the monitoring phone (app-private storage,
 * one small file per pair). The server never stores any history. A fix is recorded only when the phone
 * moved at least 20 m or 2 minutes passed, and the file is trimmed to its newest 400 entries.
 * Erased when the pair is removed.
 */
object FamilyHistory {

    class Fix(val ts: Long, val la: Double, val lo: Double, val ac: Float)

    private const val MAX_LINES = 600
    private const val KEEP_LINES = 400
    private val lastAdded = HashMap<String, Fix>()

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
        fl.appendText(JSONObject().put("ts", f.ts).put("la", f.la).put("lo", f.lo).put("ac", f.ac.toDouble()).toString() + "\n")
        lastAdded[pid] = f
        val lines = fl.readLines()
        if (lines.size > MAX_LINES) fl.writeText(lines.takeLast(KEEP_LINES).joinToString("\n") + "\n")
        return true
    }

    /** Oldest first, at most `limit` newest entries. */
    @Synchronized
    fun load(ctx: Context, pid: String, limit: Int): List<Fix> {
        val fl = file(ctx, pid)
        if (!fl.exists()) return emptyList()
        return fl.readLines().takeLast(limit).mapNotNull {
            try { val o = JSONObject(it); Fix(o.getLong("ts"), o.getDouble("la"), o.getDouble("lo"), o.optDouble("ac", 0.0).toFloat()) }
            catch (e: Exception) { null }
        }
    }

    @Synchronized
    fun clear(ctx: Context, pid: String) { lastAdded.remove(pid); file(ctx, pid).delete() }
}
