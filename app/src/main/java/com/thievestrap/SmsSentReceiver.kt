package com.thievestrap

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

/**
 * v2.9.9.1: receives the per-part delivery outcome from SmsWake.send(). Static,
 * manifest-registered, and targeted explicitly by component (not an implicit
 * action) so it works the same whether the send happened from an Activity, a
 * Service, or another BroadcastReceiver with no UI around at all. A real send
 * failure (no service, radio off, no SIM, generic failure) is now logged to
 * the Activity Log instead of disappearing silently.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val to = intent.getStringExtra(EXTRA_TO)?.filter { it.isDigit() }?.takeLast(4) ?: "????"
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val reason = when (resultCode) {
            Activity.RESULT_OK -> null
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
            else -> "error code $resultCode"
        }
        if (reason != null) {
            Log.e(TAG, "SMS to ..$to part $part FAILED: $reason")
            try { ActivityLog.append(context, "SMS to ..$to failed: $reason") } catch (e: Exception) {}
        } else {
            Log.i(TAG, "SMS to ..$to part $part sent OK")
        }
    }

    companion object {
        private const val TAG = "SmsSentReceiver"
        const val EXTRA_TO = "to"
        const val EXTRA_PART = "part"
    }
}
