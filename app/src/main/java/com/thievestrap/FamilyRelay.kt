package com.thievestrap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.9.17 (Family Bridge steps 2 and 3): what runs on top of the pairing.
 *
 * RING: the peer sends an end-to-end encrypted "ring" command. This phone checks the signature of the
 * encryption (AES-GCM with the pair key, so the server cannot forge it), the timestamp (+-120 s) and
 * a one-time nonce, then checks its OWN permission for that pair ("Ring my phone"). Only then it starts
 * AlarmService (default RINGTONE sound, alarm audio stream, maximum volume, same as the SMS ALARM).
 *
 * SELFIE: after an intruder photo is saved locally, a WorkManager job shrinks it (about 100-150 KB),
 * encrypts it with the pair key and uploads it for every pair that was granted selfies. The other phone
 * downloads, decrypts, saves it to its gallery and acknowledges: the server then deletes it at once
 * (or after 24 h if nobody picks it up).
 *
 * Delivery: MonitorService polls every 20 s while the phone is ARMED; a 15-minute WorkManager job is
 * the fallback (photos arrive, a ring from it is best effort because Android restricts starting
 * foreground services from the background).
 */
object FamilyRelay {

    private const val TAG = "FamilyRelay"
    private const val CHANNEL = "tt_family"
    private const val NOTIF_BASE = 7800
    private const val MAX_PHOTO_BYTES = 450_000
    const val POLL_MS = 20_000L

    // ------------------------------------------------------------------ polling

    @Synchronized
    fun pollOnce(ctx: Context) {
        val list = FamilyBridge.pairs(ctx)
        if (list.isEmpty()) return
        for (pr in list) {
            val inc = FamilyBridge.pollRoom(pr.pid) ?: continue
            val key = FamilyBridge.pairKey(ctx, pr) ?: continue
            inc.msgs.forEach { handleCommand(ctx, pr, key, it) }
            inc.photos.forEach { receivePhoto(ctx, pr, key, it) }
        }
    }

    internal fun handleCommand(ctx: Context, pr: FamilyBridge.FamilyPair, key: ByteArray, ct: String) {
        val plain = FamilyBridge.open(key, "cmd|" + pr.pid, ct) ?: run { Log.w(TAG, "command failed to decrypt"); return }
        try {
            val o = JSONObject(String(plain))
            val type = o.optString("t")
            val nonce = o.optString("n")
            val age = Math.abs(System.currentTimeMillis() / 1000 - o.optLong("ts", 0L))
            if (age > 120 || nonce.isEmpty() || !rememberNonce(ctx, nonce)) { Log.w(TAG, "stale or replayed command"); return }
            // MY OWN permission for this pair decides every time (the other phone cannot change it)
            when (type) {
                "ring" -> {
                    if (!pr.ring) { Log.i(TAG, "ring refused: not permitted for ${pr.name}"); FamilyAudit.add(ctx, "ring_refused", pr.name); return }
                    FamilyAudit.add(ctx, "ring", pr.name)
                    try { AlarmService.start(ctx, pr.name) } catch (e: Exception) { Log.w(TAG, "ring could not start: ${e.message}") }
                }
                "stop" -> if (pr.ring) try { AlarmService.stop(ctx) } catch (e: Exception) {}
                "track_start" -> {
                    if (!pr.location) { FamilyAudit.add(ctx, "track_refused", pr.name, "not_allowed"); FamilyTracker.status(ctx, pr.pid, "not_allowed"); return }
                    val armed = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)
                    if (!armed) { FamilyAudit.add(ctx, "track_refused", pr.name, "not_armed"); FamilyTracker.status(ctx, pr.pid, "not_armed"); return }
                    FamilyTracker.start(ctx, pr, o.optInt("mins", 60).coerceIn(5, 120))
                }
                "track_stop" -> if (FamilyTracker.activePid(ctx) == pr.pid) FamilyTracker.stop(ctx, "stopped")
                "video" -> {
                    if (!pr.selfie) { FamilyAudit.add(ctx, "video_refused", pr.name, "not_allowed"); return }
                    val armed = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)
                    if (!armed) { FamilyAudit.add(ctx, "video_refused", pr.name, "not_armed"); return }
                    FamilyAudit.add(ctx, "video", pr.name)
                    try { VideoCaptureService.start(ctx) } catch (e: Exception) { Log.w(TAG, "video could not start: ${e.message}") }
                }
                // v2.9.24: remote front selfie, same permission and "armed only" rule as "video". The captured
                // photo rejoins the normal pipeline (SELFIE_SAVED -> FamilyRelay.enqueueSelfie), so it is sent
                // to every pair that was granted selfies, exactly like an intruder photo.
                "selfie" -> {
                    if (!pr.selfie) { FamilyAudit.add(ctx, "selfie_refused", pr.name, "not_allowed"); return }
                    val armed = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)
                    if (!armed) { FamilyAudit.add(ctx, "selfie_refused", pr.name, "not_armed"); return }
                    FamilyAudit.add(ctx, "selfie", pr.name)
                    try { SelfieService.takePhoto(ctx) } catch (e: Exception) { Log.w(TAG, "selfie could not start: ${e.message}") }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "bad command", e) }
    }

    /** True the first time a nonce is seen; remembers the last 30. */
    @Synchronized
    private fun rememberNonce(ctx: Context, nonce: String): Boolean {   // push and poll can deliver the same command at once
        val sp = ctx.getSharedPreferences("tt_family", Context.MODE_PRIVATE)
        val seen = (sp.getString("seen_n", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (nonce in seen) return false
        sp.edit().putString("seen_n", (seen + nonce).takeLast(30).joinToString(",")).apply()
        return true
    }

    // ------------------------------------------------------------------ receiving a selfie

    private fun galleryDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ThievesTrap").also { if (!it.exists()) it.mkdirs() }

    private fun receivePhoto(ctx: Context, pr: FamilyBridge.FamilyPair, key: ByteArray, id: String) {
        val ct = FamilyBridge.getPhoto(pr.pid, id) ?: return                    // not there (any more) or offline: next time
        val plain = FamilyBridge.open(key, "photo|${pr.pid}|$id", ct)
        if (plain == null) { FamilyBridge.ackPhoto(pr.pid, id); return }        // corrupt or foreign: never retry it, delete it
        try {
            val metaLen = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
            val meta = try { JSONObject(String(plain.copyOfRange(2, 2 + metaLen))) } catch (e: Exception) { JSONObject() }
            val media = plain.copyOfRange(2 + metaLen, plain.size)
            val isClip = meta.optString("kind") == "clip"
            val safe = pr.name.filter { it.isLetterOrDigit() && it.code < 128 }.take(12).ifEmpty { "family" }
            if (isClip) {
                // MP4: an 'ftyp' box normally starts at byte 4; accept anything with it in the first 16 bytes.
                val head = String(media.copyOfRange(0, minOf(16, media.size)), Charsets.ISO_8859_1)
                if (!head.contains("ftyp")) { FamilyBridge.ackPhoto(pr.pid, id); return }
                val file = File(galleryDir(ctx), "family_${safe}_${id.take(8)}.mp4")
                if (!file.exists()) file.writeBytes(media)
                if (!file.exists() || file.length() == 0L) return
                // v2.9.23: copy into Movies/ThievesTrap and refresh the media store (MediaScannerConnection)
                // so the clip shows up in the Gallery app straight away; a failure here never blocks the ack.
                // v2.9.24: the returned Uri lets the notification open the native player directly (see notifyPhoto).
                val publishedUri = MediaPublisher.publishVideo(ctx, file)
                FamilyBridge.ackPhoto(pr.pid, id)
                notifyPhoto(ctx, pr.name, id, isClip = true, mediaUri = publishedUri)
            } else {
                if (media.size < 4 || media[0] != 0xFF.toByte() || media[1] != 0xD8.toByte()) { FamilyBridge.ackPhoto(pr.pid, id); return }
                val file = File(galleryDir(ctx), "family_${safe}_${id.take(8)}.jpg")
                if (!file.exists()) file.writeBytes(media)
                if (!file.exists() || file.length() == 0L) return                    // could not save: do NOT acknowledge
                FamilyBridge.ackPhoto(pr.pid, id)                                    // saved safely: server deletes its copy now
                notifyPhoto(ctx, pr.name, id, isClip = false)
            }
        } catch (e: Exception) { Log.w(TAG, "receive failed", e) }
    }

    private fun notifyPhoto(ctx: Context, name: String, id: String, isClip: Boolean, mediaUri: android.net.Uri? = null) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Family alerts", NotificationManager.IMPORTANCE_HIGH))
        // v2.9.24: tapping a video notification opens the system Gallery/player directly on the clip (when it
        // was published successfully); photos and a publish failure still fall back to the in-app gallery.
        val pi = if (isClip && mediaUri != null) {
            PendingIntent.getActivity(ctx, 0, Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(mediaUri, "video/mp4")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(ctx, 0, Intent(ctx, SelfiesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(String.format(Strings.get(ctx, if (isClip) "fam_notif_video_title" else "fam_notif_photo_title"), name))
            .setContentText(Strings.get(ctx, if (isClip) "fam_notif_video_text" else "fam_notif_photo_text"))
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { nm.notify(NOTIF_BASE + (id.hashCode() and 0xff), n) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ sending a selfie

    /** Queues the upload of a freshly saved intruder photo (only if some pair was granted selfies). */
    fun enqueueSelfie(ctx: Context, file: File) {
        if (FamilyBridge.pairs(ctx).none { it.selfie }) return
        queue(ctx, file, "intruder")
    }

    /** Same relay, a short silent video clip instead of a photo; shares the "receive selfies" permission. */
    fun enqueueClip(ctx: Context, file: File) {
        if (FamilyBridge.pairs(ctx).none { it.selfie }) { file.delete(); return }
        queue(ctx, file, "clip")
    }

    private fun queue(ctx: Context, file: File, kind: String) {
        val req = OneTimeWorkRequestBuilder<FamilyPhotoWorker>()
            .setInputData(workDataOf("path" to file.absolutePath, "kind" to kind))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("tt_family_media_" + file.name, ExistingWorkPolicy.KEEP, req)
    }

    /** Shrinks to at most 960 px / 450 KB so a photo is small enough to relay quickly. */
    internal fun compress(file: File): ByteArray? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / sample > 1920) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 960f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        for (q in intArrayOf(70, 60, 50, 40, 30)) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= MAX_PHOTO_BYTES) return out.toByteArray()
        }
        return null
    }

    // ------------------------------------------------------------------ fallback polling job

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (FamilyBridge.pairs(ctx).isEmpty()) { wm.cancelUniqueWork("tt_family_poll"); return }
        wm.enqueueUniquePeriodicWork("tt_family_poll", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FamilyPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

class FamilyPollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { FamilyRelay.pollOnce(applicationContext) } catch (e: Exception) { Log.w("FamilyRelay", "poll failed", e) }
        return Result.success()
    }
}

class FamilyPhotoWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val file = File(inputData.getString("path") ?: return Result.failure())
        val kind = inputData.getString("kind") ?: "intruder"
        if (!file.exists() || file.length() == 0L) return Result.failure()
        val targets = FamilyBridge.pairs(applicationContext).filter { it.selfie }
        if (targets.isEmpty()) { if (kind == "clip") file.delete(); return Result.success() }
        val media = if (kind == "clip") file.readBytes() else (FamilyRelay.compress(file) ?: return Result.failure())
        val meta = JSONObject().put("ts", file.lastModified() / 1000).put("kind", kind).toString().toByteArray()
        val plain = byteArrayOf((meta.size shr 8).toByte(), meta.size.toByte()) + meta + media
        var retry = false
        for (pr in targets) {
            val key = FamilyBridge.pairKey(applicationContext, pr) ?: continue
            val id = FamilyBridge.photoId(pr.pid, file.name)
            val ct = FamilyBridge.seal(key, "photo|${pr.pid}|$id", plain)
            when (FamilyBridge.putPhoto(pr.pid, id, ct)) {
                "ok" -> FamilyAudit.add(applicationContext, "selfie_sent", pr.name)
                "full", "offline", "rate_limited", "error" -> retry = true
                else -> {}                      // room gone / refused for good: do not retry that pair
            }
        }
        return if (retry && runAttemptCount < 6) Result.retry() else Result.success()
    }
}
