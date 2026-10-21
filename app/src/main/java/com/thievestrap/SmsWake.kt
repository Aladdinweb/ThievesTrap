package com.thievestrap

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

object SmsWake {
    private const val TAG = "SmsWake"

    /**
     * v2.9.7.1: wraps an outgoing SMS send in a brief PARTIAL_WAKE_LOCK. On some
     * OEM skins (reported on Infinix/Tecno XOS) the CPU can suspend mid-send when
     * triggered from a background receiver/service while the screen is off and
     * the app isn't exempted from battery optimization, silently dropping the
     * reply. The lock is held only for the few milliseconds the send call takes.
     */
    fun <T> withWakeLock(context: Context, tag: String = "ThievesTrap:SmsWakeLock", block: () -> T): T {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        try {
            wl?.acquire(5_000L)
            return block()
        } finally {
            try { if (wl?.isHeld == true) wl.release() } catch (e: Exception) {}
        }
    }

    /**
     * v2.9.9.1: the single reliable path for every outgoing SMS in the app.
     * Fixes a reported silent failure (commands processed + Telegram alert
     * sent, but the reply SMS never arrives), seen especially on dual-SIM
     * phones and stricter OEM ROMs:
     *
     * - Explicitly targets the device's default SMS subscription on Android
     *   12+ (SubscriptionManager.getDefaultSmsSubscriptionId() +
     *   SmsManager.createForSubscriptionId()) instead of letting the system
     *   guess, which on a dual-SIM phone can silently pick a SIM with no
     *   signal or no SMS credit.
     * - Falls back to the plain SmsManager if subscription resolution fails
     *   for any reason (single-SIM phones, older APIs, OEM quirks) so a
     *   missing subscription ID can never turn into "sends nothing".
     * - Still splits long text with divideMessage()/sendMultipartTextMessage:
     *   a plain sendTextMessage() silently truncates anything over 160
     *   characters, and every alert/status message this app sends is longer
     *   than that.
     * - Attaches a PendingIntent (explicitly targeting SmsSentReceiver, not
     *   an implicit action, so it works the same on every Android version)
     *   per message part, so a real failure -- no service, radio off, no
     *   SIM, generic failure -- gets logged to the Activity Log instead of
     *   vanishing.
     * - Still wrapped in the v2.9.7.1 wake lock.
     */
    fun send(context: Context, to: String, message: String) {
        withWakeLock(context) {
            try {
                val subId = try {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                } catch (e: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

                val sm: SmsManager = try {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID ->
                            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                            context.getSystemService(SmsManager::class.java)
                        else -> @Suppress("DEPRECATION") SmsManager.getDefault()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Subscription-targeted SmsManager failed (${e.message}), falling back to default")
                    @Suppress("DEPRECATION") SmsManager.getDefault()
                }

                val parts = sm.divideMessage(message)
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    val intent = Intent(context, SmsSentReceiver::class.java).apply {
                        putExtra(SmsSentReceiver.EXTRA_TO, to)
                        putExtra(SmsSentReceiver.EXTRA_PART, i)
                    }
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            context,
                            requestCode(to, i),
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                sm.sendMultipartTextMessage(to, null, parts, sentIntents, null)
                Log.i(TAG, "SMS -> $to (${parts.size} part(s), subId=$subId)")
            } catch (e: Exception) {
                Log.e(TAG, "SMS send failed to $to: ${e.message}")
                ActivityLog.append(context, "SMS to ..${to.filter { it.isDigit() }.takeLast(4)} failed to queue: ${e.message}")
            }
        }
    }

    private fun requestCode(to: String, part: Int) = (to.hashCode() * 31 + part) and 0x7fffffff
}
