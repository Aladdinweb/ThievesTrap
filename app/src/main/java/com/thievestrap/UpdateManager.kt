package com.thievestrap

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v2.7.9 — OTA Update Manager
 *
 * Checks GitHub Releases API for a newer version, and if found, downloads
 * the APK to Downloads/ via DownloadManager and launches the system
 * package installer via FileProvider.
 *
 * Fully async on Dispatchers.IO — never blocks the UI thread.
 * Does not touch SharedPreferences, security settings, or emergency
 * numbers (in-place upgrade preserves all app data).
 */
object UpdateManager {

    private const val TAG = "TT-Update"

    // ── CONFIGURE THESE TO MATCH YOUR REPO ──
    private const val GITHUB_OWNER = "Aladdinweb"
    private const val GITHUB_REPO  = "ThievesTrap"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * Entry point — call from the "Check for Update" sidebar item.
     * Shows a loading dialog, performs the API call on IO, then either
     * shows "up to date" Toast or an update-available AlertDialog.
     */
    fun checkForUpdate(context: Context) {
        val progressDialog = buildProgressDialog(context, "Checking for updates...")
        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (latestVersion, downloadUrl) = fetchLatestRelease()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()

                    if (latestVersion == null || downloadUrl == null) {
                        Toast.makeText(context,
                            "Could not check for updates. Try again later.",
                            Toast.LENGTH_LONG).show()
                        return@withContext
                    }

                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        showUpdateAvailableDialog(context, latestVersion, downloadUrl)
                    } else {
                        Toast.makeText(context,
                            "Your application is fully up to date!",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context,
                        "Update check failed: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Fetches the latest release JSON from GitHub and extracts:
     *  - tag_name (e.g. "v2.7.9" -> "2.7.9")
     *  - assets[0].browser_download_url (direct APK link)
     */
    private fun fetchLatestRelease(): Pair<String?, String?> {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ThievesTrap-App")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "GitHub API HTTP ${conn.responseCode}")
                return Pair(null, null)
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
                .removePrefix("v").removePrefix("V").trim()

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null)
                        break
                    }
                }
                // Fallback: first asset regardless of extension
                if (apkUrl == null && assets.length() > 0) {
                    apkUrl = assets.getJSONObject(0).optString("browser_download_url", null)
                }
            }

            return Pair(tagName.ifBlank { null }, apkUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestRelease error: ${e.message}")
            return Pair(null, null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /**
     * Compares version strings like "2.7.9" vs "2.7.8".
     * Returns true if `latest` > `current`.
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val maxLen = maxOf(l.size, c.size)
            for (i in 0 until maxLen) {
                val lv = l.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (lv != cv) return lv > cv
            }
            false
        } catch (e: Exception) {
            latest != current // fallback: any difference treated as update
        }
    }

    /**
     * Shows the "New Update Available!" dialog with Cancel / Update Now.
     */
    private fun showUpdateAvailableDialog(
        context: Context, latestVersion: String, downloadUrl: String
    ) {
        AlertDialog.Builder(context)
            .setTitle("\uD83D\uDEA8 New Update Available!")
            .setMessage(
                "Version $latestVersion is ready.\n\n" +
                "Would you like to update now to unlock the latest " +
                "security patches and features?"
            )
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstall(context, downloadUrl, latestVersion)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    /**
     * Downloads the APK to Downloads/ via DownloadManager and registers
     * a BroadcastReceiver to auto-launch the installer on completion.
     */
    private fun downloadAndInstall(context: Context, apkUrl: String, version: String) {
        try {
            val fileName = "Thieves_Trap_v${version}_Final.apk"
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Thieves Trap Update")
                .setDescription("Downloading version $version...")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = dm.enqueue(request)
            Toast.makeText(context,
                "Downloading update v$version\u2026", Toast.LENGTH_SHORT).show()

            // Register receiver for download completion -> launch installer
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == downloadId) {
                        try { ctx.unregisterReceiver(this) } catch (e: Exception) {}
                        installApk(ctx, fileName)
                    }
                }
            }
            val filter = android.content.IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall error: ${e.message}")
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Launches the system package installer via FileProvider — secure
     * implicit intent, ACTION_VIEW with application/vnd.android.package-archive.
     * This performs an in-place upgrade: SharedPreferences, security PIN,
     * and emergency numbers are all preserved by Android automatically.
     */
    private fun installApk(context: Context, fileName: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadsDir, fileName)

            if (!apkFile.exists()) {
                Toast.makeText(context,
                    "Downloaded file not found.", Toast.LENGTH_LONG).show()
                return
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk error: ${e.message}")
            Toast.makeText(context,
                "Could not launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Simple non-blocking "Checking for updates..." dialog with spinner.
     */
    private fun buildProgressDialog(context: Context, message: String): AlertDialog {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 40)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val spinner = ProgressBar(context).apply {
            setPadding(0, 0, 32, 0)
        }
        val text = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        container.addView(spinner)
        container.addView(text)

        return AlertDialog.Builder(context)
            .setView(container)
            .setCancelable(false)
            .create()
    }
}
