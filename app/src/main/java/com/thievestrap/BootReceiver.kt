package com.thievestrap

import android.content.*
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        // v2.9.23: the background BLE scan (helper role) does not survive a reboot; re-arm it for every install
        try { BleMesh.syncScan(context); BleMesh.schedule(context) } catch (e: Exception) {}
        if (p.getBoolean("running", false)) {
            ContextCompat.startForegroundService(context,
                Intent(context, MonitorService::class.java).apply { action = "START" })
        }
    }
}
