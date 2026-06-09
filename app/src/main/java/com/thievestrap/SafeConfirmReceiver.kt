package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager

/**
 * FIX 6: "I'm Safe" now stops the timer INSTANTLY — no PIN dialog.
 * Just cancels the notification and stops SurvivalTimerService directly.
 */
class SafeConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SurvivalTimerService.ACTION_IM_SAFE) return

        // Cancel the survival timer notification immediately
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(SurvivalTimerService.NOTIF_ID)
        } catch (e: Exception) {}

        // Stop the service — no PIN required
        context.stopService(Intent(context, SurvivalTimerService::class.java))

        // Update prefs and broadcast refresh so UI syncs
        context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("survival_timer_on", false).apply()

        context.sendBroadcast(Intent("com.thievestrap.SETTINGS_REFRESH").apply {
            setPackage(context.packageName)
        })
    }
}
