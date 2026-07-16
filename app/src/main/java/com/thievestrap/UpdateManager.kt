package com.thievestrap

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val TAG          = "TT-Update"
    private const val GITHUB_OWNER = "Aladdinweb"
    private const val GITHUB_REPO  = "ThievesTrap"
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"

    // OTA notification channel
    private const val OTA_CHANNEL_ID = "tt_ota_update"
    private const val OTA_NOTIF_ID   = 9001

    // Minimum valid APK size — 500 KB is enough to detect wrappers/stubs
    // NOTE: The "corrupt" error was caused by this being 1 MB which rejected real APKs
    // that had already been partially streamed. Now 500 KB.
    private const val MIN_APK_BYTES = 500_000L

    // ── Manual check (sidebar button) ────────────────────────────────────────

    fun checkForUpdate(context: Context) {
        val dlg = buildProgressDialog(context, context.getString(R.string.update_checking))
        dlg.show()
        CoroutineScope(Dispatchers.IO).launch {
            val result = fetchVersionJson()
            withContext(Dispatchers.Main) {
                dlg.dismiss()
                if (result == null) {
                    Toast.makeText(context,
                        context.getString(R.string.update_check_failed),
                        Toast.LENGTH_LONG).show()
                    return@withContext
                }
                val (remoteCode, remoteName, downloadUrl) = result
                Log.i(TAG, "[JSON] local=${BuildConfig.VERSION_CODE} remote=$remoteCode")
                if (remoteCode > BuildConfig.VERSION_CODE)
                    showUpdateDialog(context, remoteName, downloadUrl)
                else
                    Toast.makeText(context,
                        context.getString(R.string.update_up_to_date),
                        Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Background silent check — call from BootReceiver / MonitorService ────
    // Runs silently; fires a notification if an update is found.

    fun checkForUpdateBackground(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = fetchVersionJson() ?: return@launch
                val (remoteCode, remoteName, downloadUrl) = result
                Log.i(TAG, "[BG] local=${BuildConfig.VERSION_CODE} remote=$remoteCode")
                if (remoteCode > BuildConfig.VERSION_CODE) {
                    showUpdateNotification(context, remoteName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "bg check: ${e.message}")
            }
        }
    }

    // ── Show a notification that taps into MainActivity to trigger the update ─

    private fun showUpdateNotification(context: Context, version: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    OTA_CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Thieves Trap update notifications" }
            )
        }

        // Tap notification → open MainActivity which will show the update dialog
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_update", true)
            putExtra("update_version", version)
        }
        val pi = PendingIntent.getActivity(
            context, OTA_NOTIF_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, OTA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Thieves Trap $version — Update Ready!")
            .setContentText("New security update available. Tap to install now.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Version $version is available!

Tap to install the latest Thieves Trap security update."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.stat_sys_download_done, "Update Now", pi)
            .build()

        nm.notify(OTA_NOTIF_ID, notif)
    }

    // ── Fetch version.json ────────────────────────────────────────────────────

    private fun fetchVersionJson(): Triple<Int, String, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(VERSION_JSON_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ThievesTrap-App")
                setRequestProperty("Cache-Control", "no-cache")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "version.json: $body")
            val json = JSONObject(body)
            val code = json.optInt("version_code", -1)
            val name = json.optString("version_name", "")
            val url  = json.optString("download_url", "")
            if (code < 0 || name.isBlank() || url.isBlank()) null
            else Triple(code, name, url)
        } catch (e: Exception) { null }
        finally { try { conn?.disconnect() } catch (e: Exception) {} }
    }

    // ── Update dialog ─────────────────────────────────────────────────────────

    fun showUpdateDialog(context: Context, version: String, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_available_title))
            .setMessage(context.getString(R.string.update_available_body, version))
            .setPositiveButton(context.getString(R.string.update_now_btn)) { _, _ ->
                downloadAndInstall(context, downloadUrl, version)
            }
            .setNegativeButton(context.getString(R.string.update_not_now_btn), null)
            .setCancelable(true).show()
    }

    // ── Download + Install ────────────────────────────────────────────────────

    private fun downloadAndInstall(context: Context, apkUrl: String, version: String) {
        try {
            val fileName = "Thieves_Trap_v${version}_Final.apk"
            val existing = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName)
            if (existing.exists()) existing.delete()

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val dlId = dm.enqueue(
                DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("Thieves Trap Update")
                    .setDescription(context.getString(R.string.update_downloading, version))
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                    .setMimeType("application/vnd.android.package-archive"))

            Toast.makeText(context,
                context.getString(R.string.update_downloading, version),
                Toast.LENGTH_SHORT).show()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != dlId) return
                    try { ctx.unregisterReceiver(this) } catch (e: Exception) {}
                    onDownloadComplete(ctx, dm, dlId, fileName)
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall: ${e.message}")
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onDownloadComplete(
        ctx: Context, dm: DownloadManager, dlId: Long, fileName: String
    ) {
        try {
            // Check download manager status
            val cursor = dm.query(DownloadManager.Query().setFilterById(dlId))
            var status = -1
            var localUri: String? = null
            if (cursor != null && cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                localUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            }
            cursor?.close()

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(ctx, "Download failed (status=$status)", Toast.LENGTH_LONG).show()
                return
            }

            // Resolve APK file — try local URI from DM first, fall back to standard path
            val apkFile: File = if (localUri != null) {
                try {
                    val uri = Uri.parse(localUri)
                    if (uri.scheme == "file") File(uri.path!!)
                    else File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        fileName)
                } catch (e: Exception) {
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        fileName)
                }
            } else {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName)
            }

            Log.i(TAG, "APK path: ${apkFile.absolutePath}, size: ${apkFile.length()}")

            if (!apkFile.exists()) {
                Toast.makeText(ctx, "Downloaded file not found.", Toast.LENGTH_LONG).show()
                return
            }

            // Size check with reduced threshold
            if (apkFile.length() < MIN_APK_BYTES) {
                Log.e(TAG, "APK too small: ${apkFile.length()} bytes — likely a stub/redirect")
                apkFile.delete()
                Toast.makeText(ctx,
                    "Download too small (${apkFile.length()} bytes). Check your connection.",
                    Toast.LENGTH_LONG).show()
                return
            }

            installApk(ctx, apkFile)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(ctx: Context, apkFile: File) {
        try {
            // Try FileProvider first (Android 7+)
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", apkFile)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            // Grant URI permission explicitly to package installer
            ctx.grantUriPermission(
                "com.android.packageinstaller", uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.grantUriPermission(
                "com.google.android.packageinstaller", uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(install)
        } catch (e: Exception) {
            Log.e(TAG, "installApk FileProvider failed: ${e.message}")
            // Fallback: direct file URI (Android 5-6)
            try {
                @Suppress("DEPRECATION")
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(install)
            } catch (e2: Exception) {
                Toast.makeText(ctx,
                    "Could not launch installer. Install manually from Downloads.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildProgressDialog(context: Context, message: String): AlertDialog {
        val ll = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 40)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        ll.addView(ProgressBar(context).apply { setPadding(0, 0, 32, 0) })
        ll.addView(android.widget.TextView(context).apply {
            text = message; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        })
        return AlertDialog.Builder(context).setView(ll).setCancelable(false).create()
    }
}
