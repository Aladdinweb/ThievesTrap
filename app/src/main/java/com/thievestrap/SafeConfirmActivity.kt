package com.thievestrap

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.widget.*

/**
 * Dialog-themed activity launched when user taps "I'm Safe" on the Survival Timer notification.
 * Verifies PIN and stops the timer.
 */
class SafeConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs   = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val savedPin = prefs.getString("password", "") ?: ""

        val etPin = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter Security PIN"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✅ Confirm — I'm Safe")
            .setMessage("Enter your Security PIN to stop the Survival Timer and dismiss all alerts.")
            .setView(etPin)
            .setPositiveButton("I'm Safe — Stop Timer") { _, _ ->
                if (etPin.text.toString() == savedPin) {
                    // Stop timer service
                    SurvivalTimerService.stop(this)
                    prefs.edit().putBoolean("survival_timer_on", false).apply()
                    // Dismiss notification
                    getSystemService(NotificationManager::class.java)
                        ?.cancel(SurvivalTimerService.NOTIF_ID)
                    // Broadcast to update UI
                    sendBroadcast(android.content.Intent("com.thievestrap.SETTINGS_REFRESH"))
                    Toast.makeText(this, "✅ Timer stopped. Stay safe!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }
}
