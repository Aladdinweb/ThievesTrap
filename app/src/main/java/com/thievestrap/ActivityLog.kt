package com.thievestrap

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.9.8: one persistent, timestamped activity log shared by MonitorService
 * (every incoming command, accepted or dropped, plus security events) and
 * MainActivity (ARM/DISARM, Theft Mode toggle). Replaces the earlier split
 * between MonitorService's internal "cmd_log" (used only by the STATUS SMS
 * reply) and the single-line "last_alert" (used only by the on-screen
 * Activity Log) -- that split was why ARM/DISARM and most commands never
 * reached the screen, and why an SMS command's own outcome could overwrite
 * what the screen was showing. Both now read and write through here, so
 * nothing is tracked in only one of the two places. Keeps the most recent
 * MAX_LINES entries; both readers show as many of those as they have room
 * for.
 */
object ActivityLog {
    private const val MAX_LINES = 20
    private const val PREFS = "tt_prefs"
    private const val KEY = "activity_log"

    fun append(context: Context, line: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            val entry = "$time \u2014 $line"
            val old = prefs.getString(KEY, "").orEmpty().split("\n").filter { it.isNotBlank() }
            prefs.edit()
                .putString(KEY, (old + entry).takeLast(MAX_LINES).joinToString("\n"))
                .putString("last_alert", entry) // kept for any older code still reading the single-line summary
                .apply()
        } catch (e: Exception) { /* logging must never crash the caller */ }
    }

    /** Most recent [n] entries, oldest first (so they read top-to-bottom chronologically). */
    fun recent(context: Context, n: Int = 6): List<String> {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty()
                .split("\n").filter { it.isNotBlank() }.takeLast(n)
        } catch (e: Exception) { emptyList() }
    }
}
