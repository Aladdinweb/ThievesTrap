package com.thievestrap

import android.content.Context
import android.os.PowerManager

/**
 * v2.9.7.1: wraps an outgoing SMS send in a brief PARTIAL_WAKE_LOCK. On some
 * OEM skins (reported on Infinix/Tecno XOS) the CPU can suspend mid-send when
 * triggered from a background receiver/service while the screen is off and
 * the app isn't exempted from battery optimization, silently dropping the
 * reply. The lock is held only for the few milliseconds the send call takes.
 */
object SmsWake {
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
}
