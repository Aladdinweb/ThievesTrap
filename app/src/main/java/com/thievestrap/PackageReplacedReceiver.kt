package com.thievestrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * PackageReplacedReceiver
 *
 * FIX 2026-09-04: fires on MY_PACKAGE_REPLACED, immediately after this app
 * is updated in place. App updates can invalidate the Keystore-backed
 * master key behind LicenseManager's EncryptedSharedPreferences, silently
 * reverting a legitimately-activated Premium license to Free with zero
 * visible error. This proactively calls the reconciliation logic right when
 * an update completes, so the very first isPremium() check afterward is
 * already correct rather than relying on that call's own reactive self-heal
 * path. MainActivity.onCreate() also calls the same reconcile function as a
 * second safety net, in case this broadcast doesn't fire reliably on some
 * OEM/launcher combinations.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            LicenseManager.reconcileAfterUpdate(context)
        } catch (e: Exception) {
            Log.e("PackageReplacedReceiver", "reconcile failed: ${e.message}")
        }
    }
}
