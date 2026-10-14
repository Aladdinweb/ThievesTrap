package com.thievestrap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/**
 * Uploads one intruder photo to one Telegram chat. Enqueued by
 * TelegramUploader.enqueuePhoto() with a NetworkType.CONNECTED constraint, so
 * WorkManager only runs it when there is a network and retries (exponential
 * backoff) if the upload fails.
 */
class TelegramPhotoWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val path    = inputData.getString(KEY_PATH) ?: return Result.failure()
        val chatId  = inputData.getString(KEY_CHAT) ?: return Result.failure()
        val caption = inputData.getString(KEY_CAPTION).orEmpty()
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return Result.failure()

        val code = TelegramUploader.uploadPhotoBlocking(chatId, file, caption)
        return when {
            code == 200 -> Result.success()
            // network error, rate limit, or Telegram server trouble: try again later
            (code == -1 || code == 429 || code >= 500) && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            // 400/401/403 etc: wrong chat id or token, retrying will not help
            else -> Result.failure()
        }
    }

    // Needed so the expedited job can run as a short foreground service on Android 11 and below.
    override fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alert upload", NotificationManager.IMPORTANCE_MIN))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Thieves Trap")
            .setContentText("Sending alert photo")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val KEY_PATH = "path"
        const val KEY_CHAT = "chat_id"
        const val KEY_CAPTION = "caption"
        private const val CHANNEL = "tt_upload"
        private const val NOTIF_ID = 7731
        private const val MAX_ATTEMPTS = 10
    }
}
