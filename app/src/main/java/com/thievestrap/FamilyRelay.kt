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

    private fun handleCommand(ctx: Context, pr: FamilyBridge.FamilyPair, key: ByteArray, ct: String) {
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
                    if (!pr.ring) { Log.i(TAG, "ring refused: not permitted for ${pr.name}"); return }
                    try { AlarmService.start(ctx, pr.name) } catch (e: Exception) { Log.w(TAG, "ring could not start: ${e.message}") }
                }
                "stop" -> if (pr.ring) try { AlarmService.stop(ctx) } catch (e: Exception) {}
                "track_start" -> {
                    if (!pr.location) { FamilyTracker.status(ctx, pr.pid, "not_allowed"); return }
                    val armed = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)
                    if (!armed) { FamilyTracker.status(ctx, pr.pid, "not_armed"); return }
                    FamilyTracker.start(ctx, pr, o.optInt("mins", 60).coerceIn(5, 120))
                }
                "track_stop" -> if (FamilyTracker.activePid(ctx) == pr.pid) FamilyTracker.stop(ctx, "stopped")
            }
        } catch (e: Exception) { Log.w(TAG, "bad command", e) }
    }

    /** True the first time a nonce is seen; remembers the last 30. */
    private fun rememberNonce(ctx: Context, nonce: String): Boolean {
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
            val jpeg = plain.copyOfRange(2 + metaLen, plain.size)
            if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) { FamilyBridge.ackPhoto(pr.pid, id); return }
            val safe = pr.name.filter { it.isLetterOrDigit() && it.code < 128 }.take(12).ifEmpty { "family" }
            val file = File(galleryDir(ctx), "family_${safe}_${id.take(8)}.jpg")
            if (!file.exists()) file.writeBytes(jpeg)
            if (!file.exists() || file.length() == 0L) return                    // could not save: do NOT acknowledge
            FamilyBridge.ackPhoto(pr.pid, id)                                    // saved safely: server deletes its copy now
            notifyPhoto(ctx, pr.name, id)
        } catch (e: Exception) { Log.w(TAG, "receive failed", e) }
    }

    private fun notifyPhoto(ctx: Context, name: String, id: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Family alerts", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, SelfiesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(String.format(Strings.get(ctx, "fam_notif_photo_title"), name))
            .setContentText(Strings.get(ctx, "fam_notif_photo_text"))
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { nm.notify(NOTIF_BASE + (id.hashCode() and 0xff), n) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ sending a selfie

    /** Queues the upload of a freshly saved intruder photo (only if some pair was granted selfies). */
    fun enqueueSelfie(ctx: Context, file: File) {
        if (FamilyBridge.pairs(ctx).none { it.selfie }) return
        val req = OneTimeWorkRequestBuilder<FamilyPhotoWorker>()
            .setInputData(workDataOf("path" to file.absolutePath))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("tt_family_photo_" + file.name, ExistingWorkPolicy.KEEP, req)
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
        if (!file.exists() || file.length() == 0L) return Result.failure()
        val targets = FamilyBridge.pairs(applicationContext).filter { it.selfie }
        if (targets.isEmpty()) return Result.success()
        val jpeg = FamilyRelay.compress(file) ?: return Result.failure()
        val meta = JSONObject().put("ts", file.lastModified() / 1000).put("kind", "intruder").toString().toByteArray()
        val plain = byteArrayOf((meta.size shr 8).toByte(), meta.size.toByte()) + meta + jpeg
        var retry = false
        for (pr in targets) {
            val key = FamilyBridge.pairKey(applicationContext, pr) ?: continue
            val id = FamilyBridge.photoId(pr.pid, file.name)
            val ct = FamilyBridge.seal(key, "photo|${pr.pid}|$id", plain)
            when (FamilyBridge.putPhoto(pr.pid, id, ct)) {
                "ok" -> {}
                "full", "offline", "rate_limited", "error" -> retry = true
                else -> {}                      // room gone / refused for good: do not retry that pair
            }
        }
        return if (retry && runAttemptCount < 6) Result.retry() else Result.success()
    }
}
