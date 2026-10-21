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
    // v2.9.9.2: a real, process-wide monotonic counter. Never repeats, so
    // request codes can never collide across parts OR across separate calls
    // to send() for the same number -- stronger than the previous
    // hashCode()-based formula, which (though parts within one call already
    // differed) reused the same codes for repeat sends to the same number.
    private val requestCodeCounter = java.util.concurrent.atomic.AtomicInteger(0)

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
    /**
     * v2.9.9.2: [useSubscriptionTargeting] false skips createForSubscriptionId()
     * entirely and uses the plain SmsManager. SmsSentReceiver sets this when
     * it retries a GENERIC_FAILURE (code 32) that happened on the
     * subscription-targeted path, since on some dual-SIM / XOS firmware the
     * default-SMS-subscription lookup resolves to a subscription ID that is
     * valid as a number but not actually usable for sending -- the
     * createForSubscriptionId() call itself doesn't throw, so this only
     * shows up later as an async GENERIC_FAILURE on every part at once,
     * which matches what was reported. The retry is attempted once per
     * logical message (see RETRIED extra below), never looping.
     */
    fun send(context: Context, to: String, message: String, useSubscriptionTargeting: Boolean = true) {
        withWakeLock(context) {
            try {
                val subId = if (useSubscriptionTargeting) try {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                } catch (e: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                else SubscriptionManager.INVALID_SUBSCRIPTION_ID

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
                val usedSubscriptionTargeting = useSubscriptionTargeting &&
                    subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID

                val parts = sm.divideMessage(message)
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    val intent = Intent(context, SmsSentReceiver::class.java).apply {
                        putExtra(SmsSentReceiver.EXTRA_TO, to)
                        putExtra(SmsSentReceiver.EXTRA_PART, i)
                        putExtra(SmsSentReceiver.EXTRA_TOTAL_PARTS, parts.size)
                        putExtra(SmsSentReceiver.EXTRA_USED_SUB_TARGETING, usedSubscriptionTargeting)
                        if (usedSubscriptionTargeting) putExtra(SmsSentReceiver.EXTRA_MESSAGE, message)
                    }
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            context,
                            requestCodeCounter.incrementAndGet(), // process-wide unique, every call, every part
                            intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                sm.sendMultipartTextMessage(to, null, parts, sentIntents, null)
                Log.i(TAG, "SMS -> $to (${parts.size} part(s), subId=$subId, subTargeting=$usedSubscriptionTargeting)")
            } catch (e: Exception) {
                Log.e(TAG, "SMS send failed to $to: ${e.message}")
                ActivityLog.append(context, "SMS to ..${to.filter { it.isDigit() }.takeLast(4)} failed to queue: ${e.message}")
            }
        }
    }
}
