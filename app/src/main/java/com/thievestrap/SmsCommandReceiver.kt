package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * v2.7.9b — Static manifest-registered SMS receiver (priority=999).
 *
 * FIX: Strict timestamp deduplication filter (5s window) to prevent
 * duplicate SMS responses when both the static receiver (manifest) and
 * the dynamic receiver (MonitorService) would otherwise both fire.
 * Only ONE path processes any given SMS broadcast — this static receiver
 * wins due to its manifest priority=999, and MonitorService's dynamic
 * receiver is the fallback while the service is alive.
 */
class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TT-SmsReceiver"

        // v2.7.9b: Deduplication — shared across all instances (static field)
        @Volatile private var lastProcessedTime: Long = 0
        private const val DEDUP_WINDOW_MS = 5000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        // ── v2.7.9b: Deduplication filter ──────────────────────────────
        // Prevents double-firing when both this static receiver AND the
        // dynamic smsReceiver inside MonitorService both intercept the
        // same broadcast (which can happen on some ROMs/API levels).
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Duplicate SMS broadcast suppressed (within ${DEDUP_WINDOW_MS}ms window)")
            return
        }
        lastProcessedTime = now
        // ───────────────────────────────────────────────────────────────

        Log.i(TAG, "SMS_RECEIVED intercepted — forwarding to MonitorService")

        val serviceIntent = Intent(context, MonitorService::class.java).apply {
            action = "SMS_COMMAND"
            putExtras(intent)
        }

        // FIX -- was launching a Dispatchers.IO coroutine and calling
        // startForegroundService() from inside it. onReceive() only
        // guarantees the process stays alive for its own synchronous
        // execution -- once it returns (immediately, since launching a
        // coroutine doesn't block), Android can kill the process before
        // that coroutine is ever scheduled, especially under Samsung One
        // UI's background-process management. No blocking I/O happens here
        // to justify the async wrapper -- this must run synchronously.
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward SMS to MonitorService: ${e.message}")
        }
    }
}
