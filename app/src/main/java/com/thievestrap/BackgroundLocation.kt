package com.thievestrap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * v2.9.19: "Allow all the time" location.
 *
 * Tracking runs inside MonitorService (a foreground service of type location), so it already works with the
 * screen locked or the app swiped away while the phone is ARMED. What Android blocks without this permission
 * is GPS for a service that was restarted from the background (reboot, the system killing the app, an update):
 * exactly the stolen-phone case. The manifest declared it but nothing ever asked for it.
 * Android 11+ cannot show this as a normal dialog: the user must pick "Allow all the time" in the app's
 * Location permission page, so we explain it and open that page.
 */
object BackgroundLocation {

    fun isGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Explains and opens the right settings page. Without [force] it asks at most 3 times in total (no nagging). */
    fun promptIfNeeded(act: Activity, force: Boolean = false) {
        if (isGranted(act) || act.isFinishing) return
        val sp = act.getSharedPreferences("tt_family", Context.MODE_PRIVATE)
        val n = sp.getInt("bg_prompts", 0)
        if (!force && n >= 3) return
        sp.edit().putInt("bg_prompts", n + 1).apply()
        AlertDialog.Builder(act)
            .setTitle(Strings.get(act, "fam_bg_title"))
            .setMessage(Strings.get(act, "fam_bg_msg"))
            .setPositiveButton(Strings.get(act, "fam_bg_open")) { _, _ -> open(act) }
            .setNegativeButton(Strings.get(act, "cancel"), null)
            .show()
    }

    private fun open(act: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 7711)
        }
    }
}
