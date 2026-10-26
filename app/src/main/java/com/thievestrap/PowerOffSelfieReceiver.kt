package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * v2.9.9.3: fires on ACTION_SHUTDOWN, a system broadcast sent once the user
 * confirms Power Off from the power menu. Manifest-registered (one of the
 * small set of implicit broadcasts still deliverable to a static receiver on
 * Android 8+). This is NOT power-button or power-menu interception -- Android
 * removed that ability for third-party apps in Android 10 specifically to
 * stop apps hijacking it, and this receiver does not attempt to block or
 * delay the actual shutdown. By the time this fires the device is already
 * shutting down, so the capture + SMS/Telegram dispatch kicked off in
 * MonitorService is best-effort, racing the power-off itself -- the same
 * reliability class as a "last known location" ping in Find My Device-style
 * anti-theft apps, not a guarantee.
 *
 * All real gating (Premium, the Settings toggle, armed state) happens in
 * MonitorService's POWEROFF_SELFIE handler; this receiver only has to get a
 * foreground-service start request out the door as fast as possible.
 */
class PowerOffSelfieReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN) return
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, MonitorService::class.java).apply { action = "POWEROFF_SELFIE" })
        } catch (e: Exception) {
            Log.e("PowerOffSelfieReceiver", "start failed: ${e.message}")
        }
    }
}
