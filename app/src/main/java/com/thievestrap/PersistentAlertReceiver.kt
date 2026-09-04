package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * PersistentAlertReceiver
 *
 * FIX 2026-09-03: MonitorService's SIM-change and ringer-mode-change
 * detection was registered dynamically inside onCreate() (registerReceivers()),
 * which only listens while that specific service instance happens to be
 * alive. If the OS kills MonitorService's process (which Samsung One UI does
 * even with battery optimization set to unrestricted, under general memory
 * pressure), those listeners die with it and stay deaf until something else
 * revives the service -- unlike SMS commands and wrong-PIN capture, which
 * both have static, manifest-registered receivers (SmsCommandReceiver,
 * DeviceAdminReceiver) that survive process death and can independently
 * (re)start MonitorService as needed.
 *
 * This is that same missing static backup for SIM/ringer events. It doesn't
 * replace the existing dynamic registration (kept as the fast path while the
 * service is already running) -- it just guarantees these events are never
 * silently missed for the entire lifetime of an OS-killed process.
 */
class PersistentAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, MonitorService::class.java).apply {
            action = "EXTERNAL_STATE_CHECK"
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e("PersistentAlertReceiver", "Failed to forward to MonitorService: ${e.message}")
        }
    }
}
