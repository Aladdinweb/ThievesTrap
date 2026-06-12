package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * v2.7.7 — Static, manifest-registered SMS receiver.
 *
 * Unlike a dynamically-registered receiver (which dies with the service's
 * process), this receiver is declared in AndroidManifest.xml with
 * android:priority="999" and android:exported="true", so the system can
 * deliver SMS_RECEIVED broadcasts to it EVEN IF the app process has been
 * killed by the OS. It immediately delegates to MonitorService for
 * processing, starting the service if necessary.
 *
 * "WHERE" is handled with zero premium checks — works in Free and Paid.
 */
class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TT-SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        Log.i(TAG, "SMS_RECEIVED broadcast intercepted (static receiver)")

        // Forward the full intent to MonitorService for processing.
        // MonitorService.onStartCommand("SMS_COMMAND") will parse pdus,
        // run loop guards, and dispatch handleCommand() exactly as before.
        val serviceIntent = Intent(context, MonitorService::class.java).apply {
            action = "SMS_COMMAND"
            putExtras(intent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward SMS to MonitorService: ${e.message}")
        }
    }
}
