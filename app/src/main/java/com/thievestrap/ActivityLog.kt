package com.thievestrap

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.9.12: the on-screen Activity Log shows ONLY core security events:
 *   REMOTE CMD    a real remote SMS command (accepted or refused), PINs masked
 *   WRONG UNLOCK  a wrong screen-lock / PIN attempt caught by Device Admin
 *   SELFIE        an intruder selfie was captured
 * Everything else (ARM/DISARM, SMS delivery diagnostics, carrier or promo texts that
 * merely arrived) is kept OUT of this list. Unrelated SMS bodies are never stored at all.
 * Old entries written by earlier versions have no category prefix and are filtered out
 * when read, so the log starts clean after the update.
 * [append] is kept for existing callers: it feeds a small diagnostics buffer ("activity_diag")
 * that no screen displays, and still updates "last_alert" (Settings shows it).
 */
object ActivityLog {
    private const val MAX_LINES = 20
    private const val MAX_DIAG = 30
    private const val PREFS = "tt_prefs"
    private const val KEY = "activity_log"
    private const val KEY_DIAG = "activity_diag"
    private const val SEP = " \u2014 "
    private const val DOT = " \u00b7 "

    private const val TAG_CMD = "REMOTE CMD"
    private const val TAG_LOCK = "WRONG UNLOCK"
    private const val TAG_SELFIE = "SELFIE"
    private val CORE_PREFIXES = listOf(TAG_CMD + DOT, TAG_LOCK + DOT, TAG_SELFIE + DOT)

    // The only words that count as a remote command. Anything else is not logged.
    private val COMMANDS = setOf(
        "WHERE", "LOCATION", "LOC", "FIND", "INFO", "DEVICE", "STATUS", "BATTERY", "BAT",
        "SIM", "IMEI", "HISTORY", "ALARM", "RING", "SILENCE", "LOCK", "SELFIE", "PHOTO",
        "PICTURE", "PING", "ACTIVE", "ACTIVATE", "DEACTIVATE", "DISARM", "HELP", "COMMANDS", "LIMIT", "?"
    )

    /** True only for "<COMMAND>" or "STOP ALARM|PING", optionally followed by digits (PIN or minutes). */
    fun isRemoteCommand(raw: String): Boolean {
        val t = raw.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty() || t.size > 4) return false
        var rest = 1
        if (t[0] == "STOP") {
            if (t.size < 2 || (t[1] != "ALARM" && t[1] != "PING")) return false
            rest = 2
        } else if (t[0] !in COMMANDS) return false
        return t.drop(rest).all { it.length in 1..8 && it.all { c -> c.isDigit() } }
    }

    private fun mask(cmd: String): String =
        cmd.trim().uppercase().split(Regex("\\s+")).joinToString(" ") {
            if (it.length >= 3 && it.all { c -> c.isDigit() }) "\u2022\u2022\u2022\u2022" else it
        }

    /** A remote SMS command. Texts that are not commands (carrier, promos, chat) are ignored and never stored. */
    fun remoteCmd(context: Context, cmd: String, senderTail: String, outcome: String) {
        if (!isRemoteCommand(cmd)) return
        addCore(context, TAG_CMD + DOT + mask(cmd) + " (.." + senderTail + "): " + outcome)
    }

    fun wrongUnlock(context: Context, attempt: Int) =
        addCore(context, TAG_LOCK + DOT + "Wrong screen lock attempt #" + attempt)

    fun selfie(context: Context, label: String) =
        addCore(context, TAG_SELFIE + DOT + label + " captured")

    /** Not shown on screen. Kept for diagnostics and for the "Last alert" line in Settings. */
    fun append(context: Context, line: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entry = stamp() + SEP + line
            synchronized(this) {
                val old = prefs.getString(KEY_DIAG, "").orEmpty().split("\n").filter { it.isNotBlank() }
                prefs.edit()
                    .putString(KEY_DIAG, (old + entry).takeLast(MAX_DIAG).joinToString("\n"))
                    .putString("last_alert", entry)
                    .apply()
            }
        } catch (e: Exception) { /* logging must never crash the caller */ }
    }

    private fun addCore(context: Context, line: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entry = stamp() + SEP + line
            synchronized(this) {
                val old = readCore(prefs.getString(KEY, "").orEmpty())
                prefs.edit()
                    .putString(KEY, (old + entry).takeLast(MAX_LINES).joinToString("\n"))
                    .putString("last_alert", entry)
                    .apply()
            }
        } catch (e: Exception) { }
    }

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun readCore(raw: String): List<String> =
        raw.split("\n").filter { e ->
            val text = e.substringAfter(SEP, "")
            e.isNotBlank() && CORE_PREFIXES.any { text.startsWith(it) }
        }

    /** Most recent [n] core security events, oldest first. */
    fun recent(context: Context, n: Int = 6): List<String> {
        return try {
            readCore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty()).takeLast(n)
        } catch (e: Exception) { emptyList() }
    }
}
