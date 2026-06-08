package com.thievestrap

import android.content.*
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        if (p.getBoolean("running", false)) {
            ContextCompat.startForegroundService(context,
                Intent(context, MonitorService::class.java).apply { action = "START" })
        }
    }
}
