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

object UpdateManager {

    private const val TAG          = "TT-Update"
    private const val GITHUB_OWNER = "Aladdinweb"
    private const val GITHUB_REPO  = "ThievesTrap"
    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    // v2.8.1: serverless static JSON — update this file in repo on every release
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"
    private const val MIN_APK_BYTES = 1_000_000L

    // ── PATH 1: GitHub Releases API (original, wired to sidebar button) ──

    fun checkForUpdate(context: Context) {
        val dlg = buildProgressDialog(context, context.getString(R.string.update_checking))
        dlg.show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (ver, url) = fetchLatestRelease()
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    if (ver == null || url == null) {
                        Toast.makeText(context,
                            context.getString(R.string.update_check_failed),
                            Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    Log.i(TAG, "[API] current=${BuildConfig.VERSION_NAME} latest=$ver")
                    if (isNewerVersion(ver, BuildConfig.VERSION_NAME))
                        showUpdateDialog(context, ver, url)
                    else
                        Toast.makeText(context,
                            context.getString(R.string.update_up_to_date),
                            Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    Toast.makeText(context,
                        context.getString(R.string.update_check_failed),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchLatestRelease(): Pair<String?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ThievesTrap-App")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK)
                return Pair(null, null)
            val json   = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tagName = json.optString("tag_name","").removePrefix("v").removePrefix("V").trim()
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name","").endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url", null); break
                    }
                }
                if (apkUrl == null && assets.length() > 0)
                    apkUrl = assets.getJSONObject(0).optString("browser_download_url", null)
            }
            Pair(tagName.ifBlank { null }, apkUrl)
        } catch (e: Exception) { Pair(null, null) }
        finally { try { conn?.disconnect() } catch (e: Exception) {} }
    }

    // ── PATH 2: Serverless version.json (v2.8.1, lightweight alternative) ──
    // Use checkForUpdateJson() instead of / alongside checkForUpdate().
    // Compares integer version_code — faster, no API rate limits.
    // Requires version.json at repo root to be updated on every release.

    fun checkForUpdateJson(context: Context) {
        val dlg = buildProgressDialog(context, context.getString(R.string.update_checking))
        dlg.show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    Toast.makeText(context,
                        context.getString(R.string.update_check_failed),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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

    // ── Shared: update dialog, download, install ──────────────────

    private fun showUpdateDialog(context: Context, version: String, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_available_title))
            .setMessage(context.getString(R.string.update_available_body, version))
            .setPositiveButton(context.getString(R.string.update_now_btn)) { _, _ ->
                downloadAndInstall(context, downloadUrl, version)
            }
            .setNegativeButton(context.getString(R.string.update_not_now_btn), null)
            .setCancelable(true).show()
    }

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
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L) != dlId) return
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
            val cursor = dm.query(DownloadManager.Query().setFilterById(dlId))
            var status = -1
            if (cursor != null && cursor.moveToFirst())
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            cursor?.close()
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(ctx, "Download failed (status=$status)", Toast.LENGTH_LONG).show()
                return
            }
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName)
            if (!apkFile.exists()) {
                Toast.makeText(ctx, "Downloaded file not found.", Toast.LENGTH_LONG).show()
                return
            }
            if (apkFile.length() < MIN_APK_BYTES) {
                apkFile.delete()
                Toast.makeText(ctx,
                    "Download appears corrupt. Check the Release asset is a direct .apk file.",
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
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Log.e(TAG, "installApk: ${e.message}")
            Toast.makeText(ctx,
                "Could not launch installer. Install manually from Downloads.",
                Toast.LENGTH_LONG).show()
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

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(l.size, c.size)) {
                val d = (l.getOrElse(i){0}) - (c.getOrElse(i){0})
                if (d != 0) return d > 0
            }
            false
        } catch (e: Exception) { latest != current }
    }
}
