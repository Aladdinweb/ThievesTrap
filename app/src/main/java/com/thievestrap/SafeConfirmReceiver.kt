package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.widget.EditText
import android.text.InputType

/**
 * Receives the "I'm Safe" broadcast from the Survival Timer notification.
 * Shows a PIN dialog, then stops the timer if correct.
 */
class SafeConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SurvivalTimerService.ACTION_IM_SAFE) return

        val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("password", "") ?: ""

        // Build PIN dialog — must run on UI thread via Activity
        val launchIntent = Intent(context, SafeConfirmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launchIntent)
    }
}
