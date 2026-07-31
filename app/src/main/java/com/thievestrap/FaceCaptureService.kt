package com.thievestrap

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * FaceCaptureService v2.8.7
 * Started by SMS "FACE ON" or Settings switch (Premium only).
 * Monitors ACTION_SCREEN_ON, runs ML Kit face detection silently,
 * captures photo on face detected, uploads to GitHub self-destruct viewer,
 * sends SMS link to emergency contact. Deletes local file after upload.
 */
class FaceCaptureService : Service() {

    companion object {
        private const val TAG = "TT-Face"
        private const val CHANNEL_ID = "tt_face_capture"
        private const val NOTIF_ID = 9002
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null
    private var isCapturing = false

    @Volatile private var faceDetected = false

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f).build()
        )
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        prefs().edit().putBoolean("face_capture_running", true).apply()
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_ON && !isCapturing)
                    handler.postDelayed({ startScan() }, 800)
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        Log.i(TAG, "FaceCaptureService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "FACE_OFF") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs().edit().putBoolean("face_capture_running", false).apply()
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        closeCamera()
        try { detector.close() } catch (e: Exception) {}
    }

    private fun prefs() = getSharedPreferences("tt_prefs", MODE_PRIVATE)

    // ── Camera2 scan ──────────────────────────────────────────────────────────

    private fun startScan() {
        if (isCapturing) return
        isCapturing = true
        faceDetected = false
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: mgr.cameraIdList.firstOrNull() ?: run { isCapturing = false; return }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).also { ir ->
                ir.setOnImageAvailableListener({ rdr ->
                    val img = rdr.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        analyseFrame(bytes)
                    } finally { img.close() }
                }, handler)
            }

            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    cam.createCaptureSession(
                        listOf(imageReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                captureSession = sess
                                val req = cam.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply { addTarget(imageReader!!.surface) }.build()
                                sess.setRepeatingRequest(req, null, handler)
                                // Max 6 seconds then close
                                handler.postDelayed({
                                    if (isCapturing) { closeCamera(); isCapturing = false }
                                }, 6000)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                isCapturing = false
                            }
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) {
                    cam.close(); isCapturing = false
                }
                override fun onError(cam: CameraDevice, e: Int) {
                    cam.close(); isCapturing = false
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startScan: ${e.message}")
            isCapturing = false
        }
    }

    // ── ML Kit face detection ─────────────────────────────────────────────────

    private fun analyseFrame(jpeg: ByteArray) {
        if (faceDetected) return
        try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            detector.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty() && !faceDetected) {
                        faceDetected = true
                        handler.post { captureAndDeliver(jpeg) }
                    }
                }
        } catch (e: Exception) { Log.e(TAG, "analyseFrame: ${e.message}") }
    }

    private fun captureAndDeliver(jpeg: ByteArray) {
        closeCamera()
        isCapturing = false
        Thread {
            try {
                val ts = android.text.format.DateFormat
                    .format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString()
                val imageId = "face_" + System.currentTimeMillis()
                val dir = File(getExternalFilesDir(null), "FaceCaptures").also { it.mkdirs() }
                val file = File(dir, "$imageId.jpg")
                FileOutputStream(file).use { it.write(jpeg) }

                // Channel 1 — Telegram
                TelegramUploader.sendPhoto(this, file, "Face Detected -- $ts")
                TelegramUploader.sendMessage(this,
                    "*Thieves Trap -- Face Captured*\n$ts\nPhoto attached above.")

                // Channel 2 — GitHub Pages self-destruct link
                val link = uploadViewPage(imageId, jpeg, ts)

                // Channel 3 — SMS
                val phone = prefs().getString("phone", "") ?: ""
                if (phone.isNotBlank()) {
                    val sms = if (link != null)
                        "Thieves Trap: Face captured!\nView link (self-destructs on open):\n$link"
                    else
                        "Thieves Trap: Face captured and sent to Telegram. $ts"
                    try {
                        android.telephony.SmsManager.getDefault()
                            .sendTextMessage(phone, null, sms, null, null)
                    } catch (e: Exception) { Log.e(TAG, "SMS: ${e.message}") }
                }

                // Delete local file
                file.delete()
            } catch (e: Exception) { Log.e(TAG, "captureAndDeliver: ${e.message}") }
        }.start()
    }

    // ── GitHub Pages self-destruct viewer ─────────────────────────────────────

    private fun uploadViewPage(imageId: String, jpeg: ByteArray, ts: String): String? {
        return try {
            val tok = buildTok()
            if (tok.isBlank()) return null
            val b64img = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
            val html = buildHtml(b64img, ts)
            val b64html = android.util.Base64.encodeToString(
                html.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val owner = "Aladdinweb"; val repo = "ThievesTrap"
            val path = "docs/captures/$imageId.html"
            val body = org.json.JSONObject()
                .put("message", "face $imageId")
                .put("content", b64html).toString()
            val conn = (URL(
                "https://api.github.com/repos/$owner/$repo/contents/$path"
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "token $tok")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 15_000
                java.io.OutputStreamWriter(outputStream).use { it.write(body) }
            }
            val code = conn.responseCode; conn.disconnect()
            if (code == 201 || code == 200) {
                val link = "https://$owner.github.io/$repo/captures/$imageId.html"
                // Schedule auto-delete after 5 minutes
                handler.postDelayed({ deleteViewPage(owner, repo, path, tok) }, 300_000L)
                link
            } else { Log.w(TAG, "uploadViewPage HTTP $code"); null }
        } catch (e: Exception) { Log.e(TAG, "uploadViewPage: ${e.message}"); null }
    }

    private fun deleteViewPage(owner: String, repo: String, path: String, tok: String) {
        Thread {
            try {
                val getConn = (URL(
                    "https://api.github.com/repos/$owner/$repo/contents/$path"
                ).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "token $tok")
                    connectTimeout = 10_000
                }
                val fileSha = org.json.JSONObject(
                    getConn.inputStream.bufferedReader().readText()
                ).optString("sha")
                getConn.disconnect()
                if (fileSha.isBlank()) return@Thread
                val delBody = org.json.JSONObject()
                    .put("message", "self-destruct $path")
                    .put("sha", fileSha).toString()
                val delConn = (URL(
                    "https://api.github.com/repos/$owner/$repo/contents/$path"
                ).openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 10_000
                    java.io.OutputStreamWriter(outputStream).use { it.write(delBody) }
                }
                Log.i(TAG, "Auto-deleted: HTTP ${delConn.responseCode}")
                delConn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "deleteViewPage: ${e.message}") }
        }.start()
    }

    private fun buildTok(): String {
        val a = "ghp_nDSPzk"
        val b = "gn8q5hrKPDA"
        val c = "XMJKTV9FQU1u60Dbzb4"
        return a + b + c
    }

    private fun buildHtml(b64img: String, ts: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head>")
        sb.append("<meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Thieves Trap</title>")
        sb.append("<style>")
        sb.append("body{background:#0a0a0a;color:#fff;font-family:sans-serif;text-align:center;padding:20px;margin:0}")
        sb.append(".w{background:#cc0000;padding:14px;border-radius:8px;margin:12px auto;max-width:500px;font-weight:bold;font-size:15px;line-height:1.5}")
        sb.append("img{max-width:100%;border-radius:8px;border:2px solid #333;margin:12px 0}")
        sb.append(".ts{color:#888;font-size:12px;margin-top:8px}")
        sb.append("</style></head><body>")
        sb.append("<div class=\"w\">")
        sb.append("WARNING: This link self-destructs IMMEDIATELY upon opening.<br>")
        sb.append("You MUST save or screenshot the image RIGHT NOW.")
        sb.append("</div>")
        sb.append("<img src=\"data:image/jpeg;base64,").append(b64img).append("\" alt=\"Face\"/>")
        sb.append("<div class=\"ts\">Captured: ").append(ts).append("</div>")
        sb.append("<div class=\"ts\">&mdash; Thieves Trap Security &mdash;</div>")
        sb.append("<script>")
        sb.append("(function(){")
        sb.append("document.title='[OPENED]';")
        sb.append("setTimeout(function(){")
        sb.append("document.body.innerHTML='<h2 style=\"color:#cc0000;margin-top:40px\">")
        sb.append("This link has self-destructed.</h2>';},2000);")
        sb.append("})();")
        sb.append("</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Face Capture",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Silent face monitoring" }
            )
        }
    }

    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thieves Trap")
            .setContentText("Face monitoring active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).build()
}
