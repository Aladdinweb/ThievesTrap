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
 *
 * v2.9.9.2: if the failure was RESULT_ERROR_GENERIC_FAILURE (code 1) and the
 * part was sent with subscription targeting, retry the whole message once
 * through the plain (non-subscription-bound) SmsManager -- on some dual-SIM /
 * XOS firmware the default-SMS-subscription ID resolves to a value that
 * createForSubscriptionId() accepts without throwing, but that is not
 * actually usable for sending, so every part fails identically and at once.
 * The retry is deduplicated per logical message (not per part) so a 4-part
 * message triggers exactly one retry, not four.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val originalTo = intent.getStringExtra(EXTRA_TO).orEmpty() // exact string passed to send(), reused verbatim for the retry
        val toTail = originalTo.filter { it.isDigit() }.takeLast(4)
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val totalParts = intent.getIntExtra(EXTRA_TOTAL_PARTS, 1)
        val usedSubTargeting = intent.getBooleanExtra(EXTRA_USED_SUB_TARGETING, false)
        val originalMessage = intent.getStringExtra(EXTRA_MESSAGE)

        val reason = when (resultCode) {
            Activity.RESULT_OK -> null
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
            // v2.9.9.4: this was the real cause of the repeated "error code
            // 32" seen in testing -- it's RESULT_NO_DEFAULT_SMS_APP, not
            // generic failure (that's code 1). On some XOS/Android Go-style
            // builds the OS has no app registered as the default SMS
            // handler at all, and refuses to send SMS for ANY app until one
            // is set -- that's why a manual send from the stock Messages
            // app worked while this app's sends didn't: Messages is
            // presumably the one registered as default. Switching which
            // SmsManager instance we use (the v2.9.9.2 retry) can't fix
            // this, since it's a device-wide registration issue, not a
            // per-subscription one -- see MainActivity.promptDefaultSmsApp().
            SmsManager.RESULT_NO_DEFAULT_SMS_APP -> "no default SMS app set on this device"
            else -> "error code $resultCode"
        }

        if (reason == null) {
            Log.i(TAG, "SMS to ..$toTail part $part sent OK")
            return
        }

        Log.e(TAG, "SMS to ..$toTail part $part FAILED: $reason")

        val canRetry = resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE &&
            usedSubTargeting && !originalMessage.isNullOrBlank() && originalTo.isNotBlank()

        // Capturing into a local `val msg: String` (not `String?`) here is what
        // actually lets the compiler treat it as non-null below -- the
        // `canRetry` boolean already proved it, but a Boolean computed from
        // !isNullOrBlank() doesn't smart-cast the original nullable property
        // the way a direct `if (x != null)` would. This is the fix for the
        // "inferred type is String? but String was expected" build error.
        val msg: String? = if (canRetry) originalMessage else null
        if (canRetry && msg != null) {
            // Dedupe: only the first part of this message triggers the retry.
            // A fresh dedupe key per message content means a genuinely new
            // alert with the same text a moment later still retries once.
            val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            val key = "sms_retry_${originalTo.hashCode()}_${msg.hashCode()}"
            if (!prefs.getBoolean(key, false)) {
                prefs.edit().putBoolean(key, true).apply()
                Log.w(TAG, "SMS to ..$toTail: subscription-targeted send got GENERIC_FAILURE, retrying once via default SIM manager")
                try {
                    ActivityLog.append(context, "SMS to ..$toTail failed ($reason), retrying via default SIM")
                } catch (e: Exception) {}
                try {
                    SmsWake.send(context, originalTo, msg, useSubscriptionTargeting = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Retry dispatch failed: ${e.message}")
                }
                return
            }
        }

        try { ActivityLog.append(context, "SMS to ..$toTail failed: $reason") } catch (e: Exception) {}
    }

    companion object {
        private const val TAG = "SmsSentReceiver"
        const val EXTRA_TO = "to"
        const val EXTRA_PART = "part"
        const val EXTRA_TOTAL_PARTS = "total_parts"
        const val EXTRA_USED_SUB_TARGETING = "used_sub_targeting"
        const val EXTRA_MESSAGE = "message"
    }
}
