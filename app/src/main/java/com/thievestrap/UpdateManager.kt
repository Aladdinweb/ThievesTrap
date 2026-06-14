package com.thievestrap

import android.app.AlertDialog
import android.app.DownloadManager
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
 * v2.7.10 — OTA Update Manager (fixed)
 *
 * Fix: validates downloaded file is a real APK (> 1 MB, not a zip wrapper).
 *      Logs the exact download URL so mis-routing is detectable.
 *      Falls back to direct browser install if FileProvider path is wrong.
 */
object UpdateManager {

    private const val TAG          = "TT-Update"
    private const val GITHUB_OWNER = "Aladdinweb"
    private const val GITHUB_REPO  = "ThievesTrap"
    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    // Minimum real APK size — anything smaller is a stub/zip wrapper
    private const val MIN_APK_BYTES = 1_000_000L // 1 MB

    // ── Entry point ────────────────────────────────────────────

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
                    Log.i(TAG, "Current: $currentVersion | Latest: $latestVersion | URL: $downloadUrl")

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

    // ── GitHub Releases API ────────────────────────────────────

    private fun fetchLatestRelease(): Pair<String?, String?> {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ThievesTrap-App")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "GitHub API HTTP ${conn.responseCode}")
                return Pair(null, null)
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "GitHub release JSON: ${body.take(500)}")

            val json    = JSONObject(body)
            val tagName = json.optString("tag_name", "")
                .removePrefix("v").removePrefix("V").trim()

            // Find the first .apk asset — the direct download link to the APK binary
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name  = asset.optString("name", "")
                    val url2  = asset.optString("browser_download_url", "")
                    Log.i(TAG, "Asset[$i]: name=$name url=$url2")
                    if (name.endsWith(".apk", ignoreCase = true) && url2.isNotBlank()) {
                        apkUrl = url2
                        break
                    }
                }
                // Fallback: first asset regardless of name
                if (apkUrl == null && assets.length() > 0) {
                    apkUrl = assets.getJSONObject(0)
                        .optString("browser_download_url", null)
                    Log.w(TAG, "No .apk asset found — falling back to first asset: $apkUrl")
                }
            }

            Log.i(TAG, "Resolved: tag=$tagName apkUrl=$apkUrl")
            return Pair(tagName.ifBlank { null }, apkUrl)

        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestRelease: ${e.message}")
            return Pair(null, null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    // ── Version comparison ─────────────────────────────────────

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
            latest != current
        }
    }

    // ── Update available dialog ────────────────────────────────

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

    // ── Download via DownloadManager ───────────────────────────

    private fun downloadAndInstall(context: Context, apkUrl: String, version: String) {
        try {
            val fileName = "Thieves_Trap_v${version}_Final.apk"

            // Delete any previous (possibly corrupt) download
            val existingFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName)
            if (existingFile.exists()) {
                existingFile.delete()
                Log.i(TAG, "Deleted old download: $fileName")
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            Log.i(TAG, "Starting download: $apkUrl -> $fileName")

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Thieves Trap Update")
                .setDescription("Downloading version $version...")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                // Force correct MIME so DownloadManager doesn't rename it
                .setMimeType("application/vnd.android.package-archive")

            val downloadId = dm.enqueue(request)
            Toast.makeText(context,
                "Downloading update v$version\u2026", Toast.LENGTH_SHORT).show()

            // Register one-shot completion receiver
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return
                    try { ctx.unregisterReceiver(this) } catch (e: Exception) {}
                    onDownloadComplete(ctx, dm, downloadId, fileName)
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall: ${e.message}")
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Download complete handler ──────────────────────────────

    private fun onDownloadComplete(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        fileName: String
    ) {
        try {
            // Check download status
            val query  = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)
            var status = -1
            var reason = -1
            if (cursor != null && cursor.moveToFirst()) {
                status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                reason = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            }
            cursor?.close()

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Log.e(TAG, "Download failed — status=$status reason=$reason")
                Toast.makeText(context,
                    "Download failed (status=$status reason=$reason). " +
                    "Check your internet connection and try again.",
                    Toast.LENGTH_LONG).show()
                return
            }

            // Validate the file is a real APK, not a zip wrapper
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadsDir, fileName)

            if (!apkFile.exists()) {
                Log.e(TAG, "APK file missing after download: ${apkFile.absolutePath}")
                Toast.makeText(context, "Downloaded file not found.", Toast.LENGTH_LONG).show()
                return
            }

            val fileSize = apkFile.length()
            Log.i(TAG, "Downloaded file size: $fileSize bytes (${fileSize / 1024}KB)")

            if (fileSize < MIN_APK_BYTES) {
                // File is too small — likely a zip stub or error page
                Log.e(TAG, "Downloaded file too small ($fileSize bytes) — likely not an APK")
                apkFile.delete()
                Toast.makeText(context,
                    "Download error: file appears corrupt (${fileSize / 1024}KB). " +
                    "Ensure the GitHub Release has the .apk file attached directly as an asset.",
                    Toast.LENGTH_LONG).show()
                return
            }

            // All good — launch installer
            installApk(context, apkFile)

        } catch (e: Exception) {
            Log.e(TAG, "onDownloadComplete: ${e.message}")
            Toast.makeText(context, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── APK installer via FileProvider ────────────────────────

    private fun installApk(context: Context, apkFile: File) {
        try {
            Log.i(TAG, "Launching installer for: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

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
            Log.e(TAG, "installApk FileProvider error: ${e.message}")
            // Fallback: try direct file URI (older devices)
            try {
                val fallbackUri = Uri.fromFile(apkFile)
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fallbackUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "installApk fallback also failed: ${e2.message}")
                Toast.makeText(context,
                    "Could not launch installer. Please install manually from Downloads.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Progress dialog ────────────────────────────────────────

    private fun buildProgressDialog(context: Context, message: String): AlertDialog {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 40)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val spinner = ProgressBar(context).apply { setPadding(0, 0, 32, 0) }
        val text    = android.widget.TextView(context).apply {
            text      = message
            textSize  = 14f
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
