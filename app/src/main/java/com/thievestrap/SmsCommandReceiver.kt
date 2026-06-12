package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v2.7.8 — Static, manifest-registered SMS receiver (priority=999).
 *
 * Survives process death. Extracts the SMS payload SAFELY and hands off
 * to MonitorService via an explicit Intent + startForegroundService(),
 * then returns IMMEDIATELY — all parsing/handling happens inside
 * MonitorService on Dispatchers.IO, never blocking this receiver thread.
 *
 * "WHERE" (and Plan-B "WHERE <PIN>") bypass all premium checks.
 */
class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TT-SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        Log.i(TAG, "SMS_RECEIVED intercepted — handing off to MonitorService")

        // Build an explicit intent carrying the original extras (pdus, format)
        val serviceIntent = Intent(context, MonitorService::class.java).apply {
            action = "SMS_COMMAND"
            putExtras(intent)
        }

        // Launch on IO immediately — this returns the receiver instantly,
        // the actual startForegroundService call itself is lightweight but
        // we keep it off any potential main-thread contention regardless.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward SMS to MonitorService: ${e.message}")
            }
        }
    }
}
