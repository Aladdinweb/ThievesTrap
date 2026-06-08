package com.thievestrap

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class DeviceAdminReceiver : DeviceAdminReceiver() {

    private val TAG = "TT-Admin"

    override fun onPasswordFailed(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("running", false)) return

        val threshold = prefs.getInt("failed_threshold", 3)
        if (threshold == -1) return // "None" — disabled

        // Increment counter
        val count = prefs.getInt("wrong_pin_count", 0) + 1
        prefs.edit().putInt("wrong_pin_count", count).apply()
        Log.i(TAG, "Wrong PIN #$count, threshold=$threshold")

        // Fix 4: Take selfie for Premium users — selfie_enabled switch just controls the UI
        // The photo is always taken silently when premium is active
        if (LicenseManager.isPremium(context)) {
            SelfieService.takePhoto(context, 1)
        }

        // Step 2: Check if count reached threshold
        if (count >= threshold) {
            prefs.edit().putInt("wrong_pin_count", 0).apply()

            // Step 3: Start grace period (only if enabled by user)
            val graceEnabled = prefs.getBoolean("grace_enabled", false)
            val graceSeconds = prefs.getInt("grace_period", 10)
            Log.i(TAG, "Threshold reached. Starting ${graceSeconds}s grace period")

            if (!graceEnabled) {
                // No grace period — send alert immediately
                try {
                    ContextCompat.startForegroundService(context,
                        Intent(context, MonitorService::class.java).apply {
                            action = "FAILED_ATTEMPTS"
                            putExtra("count", count)
                        })
                } catch (e: Exception) {}
                return
            }

            GracePeriodManager.startGrace(
                context = context,
                wrongCount = count,
                photoFile = null,
                onSendAlert = {
                    // Grace expired — send full alert
                    try {
                        ContextCompat.startForegroundService(context,
                            Intent(context, MonitorService::class.java).apply {
                                action = "FAILED_ATTEMPTS"
                                putExtra("count", count)
                            })
                    } catch (e: Exception) {
                        context.startService(Intent(context, MonitorService::class.java).apply {
                            action = "FAILED_ATTEMPTS"
                            putExtra("count", count)
                        })
                    }
                }
            )
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        Log.i(TAG, "Password succeeded — cancelling grace")
        // Owner unlocked — cancel grace period and delete photo
        GracePeriodManager.cancelGrace()
        context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            .edit().putInt("wrong_pin_count", 0).apply()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Enter your PIN in the app first to disable protection."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Admin disabled — no alert sent (removed per requirement)
    }
}
