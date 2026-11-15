package com.thievestrap

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * v2.9.19: the PRIVACY LOG. A read-only record, kept only on this phone, of everything the family bridge
 * did to or from it: when someone started or ended live tracking, asked to ring it, was refused, or when
 * an intruder photo was sent. There is no "clear" function and the screen that shows it needs the
 * security PIN, so a thief holding an unlocked phone can neither read nor wipe it, while the legal owner
 * can always verify if and when the device was tracked. (Wiping app data or root access can still erase
 * it: that cannot be prevented on Android.)
 */
object FamilyAudit {

    class Entry(val ts: Long, val ev: String, val name: String, val x: String)

    private const val MAX_LINES = 300
    private const val KEEP_LINES = 200

    private fun file(ctx: Context) = File(ctx.filesDir, "family_audit.jsonl")

    @Synchronized
    fun add(ctx: Context, ev: String, name: String, x: String = "") {
        try {
            val f = file(ctx)
            f.appendText(JSONObject().put("ts", System.currentTimeMillis() / 1000).put("ev", ev)
                .put("n", name.take(24)).put("x", x).toString() + "\n")
            val lines = f.readLines()
            if (lines.size > MAX_LINES) f.writeText(lines.takeLast(KEEP_LINES).joinToString("\n") + "\n")
        } catch (e: Exception) { /* the log must never break the feature */ }
    }

    /** Newest first. */
    @Synchronized
    fun load(ctx: Context, limit: Int): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(limit).reversed().mapNotNull {
            try { val o = JSONObject(it); Entry(o.getLong("ts"), o.getString("ev"), o.optString("n"), o.optString("x")) }
            catch (e: Exception) { null }
        }
    }
}
