package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * PackageReplacedReceiver
 *
 * Fires on MY_PACKAGE_REPLACED, right after this app is updated in place.
 * v2.9.15: the legacy license reconciliation was removed with the legacy
 * path (signed licenses live in plain prefs and survive updates by themselves);
 * this receiver now only restarts MonitorService if protection was on.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // An in-place update kills MonitorService. The static SMS receiver
        // survives (manifest-registered) but SMS_COMMAND needs the service
        // alive to be handled cleanly, so bring it back if protection was on.
        try {
            val p = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            if (p.getBoolean("running", false)) {
                ContextCompat.startForegroundService(context,
                    Intent(context, MonitorService::class.java).apply { action = "START" })
            }
        } catch (e: Exception) {
            Log.e("PackageReplacedReceiver", "service restart failed: ${e.message}")
        }
    }
}
