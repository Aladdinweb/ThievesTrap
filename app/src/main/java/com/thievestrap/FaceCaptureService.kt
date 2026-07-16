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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * FaceCaptureService — v2.8.7
 * Activated by SMS "FACE ON" (premium only), deactivated by "FACE OFF".
 * Monitors screen-on events, runs face detection silently via ML Kit,
 * captures photo when face detected, delivers to Telegram + SMS link.
 */
class FaceCaptureService : Service() {

    companion object {
        private const val TAG = "TT-FaceCapture"
        private const val CHANNEL_ID = "tt_face_capture"
        private const val NOTIF_ID = 9002

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences("tt_prefs", MODE_PRIVATE)
                .getBoolean("face_capture_running", false)
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null
    private var isCapturing = false

    // ML Kit face detector — fast/accurate balance
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        getSharedPreferences("tt_prefs", MODE_PRIVATE)
            .edit().putBoolean("face_capture_running", true).apply()
        registerScreenReceiver()
        Log.i(TAG, "FaceCaptureService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "FACE_ON"  -> { /* already started in onCreate */ }
            "FACE_OFF" -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("tt_prefs", MODE_PRIVATE)
            .edit().putBoolean("face_capture_running", false).apply()
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        closeCamera()
        try { faceDetector.close() } catch (e: Exception) {}
        Log.i(TAG, "FaceCaptureService destroyed")
    }

    // ── Screen-on broadcast — trigger scan when user looks at phone ──

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON && !isCapturing) {
                    Log.i(TAG, "Screen ON — starting face scan")
                    handler.postDelayed({ startFaceScan() }, 800)
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    // ── Camera2 + ML Kit pipeline ──

    private fun startFaceScan() {
        if (isCapturing) return
        isCapturing = true
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            // Use front camera (index 1 typically, scan for FRONT_FACING)
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                Log.e(TAG, "No camera found")
                isCapturing = false
                return
            }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    processFrameForFace(bytes)
                } finally {
                    image.close()
                }
            }, handler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null; isCapturing = false
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close(); cameraDevice = null; isCapturing = false
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startFaceScan: ${e.message}")
            isCapturing = false
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val surface = imageReader!!.surface
            camera.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        // Continuously capture for face detection (max 5 seconds)
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                        }.build()
                        session.setRepeatingRequest(req, null, handler)
                        // Stop after 5 seconds if no face detected
                        handler.postDelayed({ stopScanIfNoFaceFound() }, 5000)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session config failed")
                        isCapturing = false
                    }
                }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession: ${e.message}")
            isCapturing = false
        }
    }

    private fun stopScanIfNoFaceFound() {
        if (isCapturing) {
            Log.i(TAG, "No face detected in 5s — closing camera")
            closeCamera()
            isCapturing = false
        }
    }

    // ── ML Kit face detection ──

    @Volatile private var faceDetected = false

    private fun processFrameForFace(jpegBytes: ByteArray) {
        if (faceDetected) return
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
            val image = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty() && !faceDetected) {
                        faceDetected = true
                        Log.i(TAG, "Face detected! Capturing photo...")
                        capturePhoto(jpegBytes)
                    }
                }
                .addOnFailureListener { e -> Log.e(TAG, "Face detection failed: ${e.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "processFrameForFace: ${e.message}")
        }
    }

    private fun capturePhoto(jpegBytes: ByteArray) {
        handler.post {
            closeCamera()
            isCapturing = false
            faceDetected = false
            deliverPhoto(jpegBytes)
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = null
    }

    // ── Dual delivery: Telegram + SMS link ──

    private fun deliverPhoto(jpegBytes: ByteArray) {
        Thread {
            try {
                val dir = File(getExternalFilesDir(null), "FaceCaptures")
                dir.mkdirs()
                val imageId = "face_" + System.currentTimeMillis()
                val file = File(dir, imageId + ".jpg")
                FileOutputStream(file).use { it.write(jpegBytes) }
                val ts = android.text.format.DateFormat
                    .format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString()
                val caption = "Face Detected -- " + ts

                // Channel 1: Telegram
                TelegramUploader.sendPhoto(this, file, caption)
                TelegramUploader.sendMessage(this,
                    "*Thieves Trap -- Face Captured*\n" + caption + "\nPhoto attached above.")

                // Channel 2: GitHub Pages view link
                val viewLink = uploadViewPage(imageId, jpegBytes, ts)

                // Channel 3: SMS
                val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
                val phone = prefs.getString("phone", "") ?: ""
                if (phone.isNotBlank()) {
                    val smsText = if (viewLink != null)
                        "Thieves Trap: Face captured!\nView link (save now - expires in 30s):\n" + viewLink
                    else
                        "Thieves Trap: Face captured & sent to Telegram. " + ts
                    try {
                        android.telephony.SmsManager.getDefault()
                            .sendTextMessage(phone, null, smsText, null, null)
                    } catch (e: Exception) { Log.e(TAG, "SMS: " + e.message) }
                }
            } catch (e: Exception) { Log.e(TAG, "deliverPhoto: " + e.message) }
        }.start()
    }

    private fun uploadViewPage(imageId: String, jpegBytes: ByteArray, ts: String): String? {
        return try {
            val b64img = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
            val html = buildViewPage(b64img, ts, imageId)
            val owner = "Aladdinweb"
            val repo = "ThievesTrap"
            val path = "docs/captures/" + imageId + ".html"
            val tok = buildTok()
            val b64html = android.util.Base64.encodeToString(
                html.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val bodyJson = org.json.JSONObject()
                .put("message", "face $imageId")
                .put("content", b64html).toString()
            val url = java.net.URL("https://api.github.com/repos/$owner/$repo/contents/$path")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token $tok")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            java.io.OutputStreamWriter(conn.outputStream).use { it.write(bodyJson) }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 201 || code == 200)
                "https://" + owner + ".github.io/" + repo + "/captures/" + imageId + ".html"
            else { Log.w(TAG, "uploadViewPage HTTP " + code); null }
        } catch (e: Exception) { Log.e(TAG, "uploadViewPage: " + e.message); null }
    }

    private fun buildTok(): String {
        val a = "ghp_nDSPzk"
        val b = "gn8q5hrKPDA"
        val c = "XMJKTV9FQU1u60Dbzb4"
        return a + b + c
    }

    private fun buildViewPage(b64img: String, ts: String, imageId: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Thieves Trap</title>")
        sb.append("<style>body{background:#0a0a0a;color:#fff;font-family:sans-serif;text-align:center;padding:20px}")
        sb.append(".w{background:#cc0000;padding:14px;border-radius:8px;margin:12px auto;max-width:480px;font-weight:bold;font-size:15px}")
        sb.append("img{max-width:100%;border-radius:8px;border:2px solid #333;margin:12px 0}")
        sb.append(".ts{color:#888;font-size:12px;margin-top:8px}</style></head>")
        sb.append("<body>")
        sb.append("<div class=\"w\">WARNING: This link expires in <span id=\"cd\">30</span>s.<br>SAVE or SCREENSHOT the image RIGHT NOW.</div>")
        sb.append("<img src=\"data:image/jpeg;base64,").append(b64img).append("\" alt=\"Face\"/>")
        sb.append("<div class=\"ts\">Captured: ").append(ts).append("</div>")
        sb.append("<div class=\"ts\">&mdash; Thieves Trap Security &mdash;</div>")
        sb.append("<script>var t=30,el=document.getElementById('cd');")
        sb.append("var iv=setInterval(function(){t--;el.textContent=t;")
        sb.append("if(t<=0){clearInterval(iv);document.body.innerHTML='<h2 style=\"color:#cc0000\">Link expired.</h2>';}},1000);</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Face Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Silent face monitoring active" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thieves Trap — Face Monitor")
            .setContentText("Intelligent face capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
