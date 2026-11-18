package com.thievestrap

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * v2.9.23: makes a video received from a family phone show up in the Gallery app at once.
 *
 * The clip is always kept in the app gallery folder (SelfiesActivity reads it there). That folder is
 * app-private storage, which Gallery apps do not index, so a COPY is also published to the shared
 * Movies/ThievesTrap folder:
 *   - Android 10+: inserted through MediaStore (no storage permission needed), then
 *     MediaScannerConnection.scanFile() is called on the stored path so the media store refreshes now.
 *   - Android 8-9: copied into the public Movies folder (needs the storage permission the app already
 *     declares; skipped quietly when it was not granted) and scanned the same way.
 */
object MediaPublisher {

    private const val TAG = "TT-MediaPublisher"
    private const val FOLDER = "ThievesTrap"

    /**
     * Never throws. Returns the published MediaStore Uri on success (so a notification can open the native
     * player directly -- v2.9.24), or null if nothing could be published (the clip still exists in the
     * app's own gallery either way).
     */
    fun publishVideo(ctx: Context, src: File): Uri? {
        val app = ctx.applicationContext
        return try {
            if (!src.exists() || src.length() == 0L) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishQ(app, src) else publishLegacy(app, src)
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: ${e.message}")
            null
        }
    }

    private fun publishQ(app: Context, src: File): Uri? {
        val resolver = app.contentResolver
        val relative = Environment.DIRECTORY_MOVIES + "/" + FOLDER
        // already published (a retry of the same download)? do not add a second copy, just hand back its uri
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf(src.name, "$relative%"), null)?.use {
                if (it.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
            }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relative)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: run { resolver.delete(uri, null, null); return null }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (x: Exception) {}
            throw e
        }
        // explicit refresh of the media store for the stored file (the published path under Movies/)
        val published = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "$FOLDER/${src.name}")
        scan(app, published.absolutePath)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(app: Context, src: File): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val dst = File(dir, src.name)
        if (!dst.exists()) src.copyTo(dst)
        if (!dst.exists() || dst.length() == 0L) return null
        // Android 8-9: MediaScannerConnection's own callback is the only way to get a content:// uri back;
        // publish a best-effort file:// uri now (most gallery apps on these versions still accept it) and
        // let the scan refresh the media store in the background.
        scan(app, dst.absolutePath)
        return Uri.fromFile(dst)
    }

    private fun scan(app: Context, path: String) {
        MediaScannerConnection.scanFile(app, arrayOf(path), arrayOf("video/mp4")) { p, uri ->
            Log.i(TAG, "media store refreshed: $p -> $uri")
        }
    }
}
