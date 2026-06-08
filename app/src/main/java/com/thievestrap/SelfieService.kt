package com.thievestrap

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.camera2.*
import android.media.ExifInterface
import android.app.*
import androidx.core.app.NotificationCompat
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class SelfieService : Service() {

    private val TAG = "TT-Selfie"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var photosTaken = 0
    private var maxPhotos = 1  // Fix 2: exactly ONE photo per failed attempt
    private val ABSOLUTE_MAX = 1  // hard ceiling — never captures more than 1
    private var isStopping = false
    private var sensorOrientation = 90

    override fun onBind(intent: Intent?) = null

    private val NOTIF_ID = 12345
    private val CHANNEL_ID = "tt_selfie"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())
        cleanup()
        maxPhotos = (intent?.getIntExtra("count", 1) ?: 1).coerceAtMost(ABSOLUTE_MAX)
        // Fix 1: Absolute timeout — stop after 30 seconds no matter what
        mainHandler.postDelayed({ if (!isStopping) { Log.w(TAG, "Timeout stop"); safeStop() } }, 30_000)
        photosTaken = 0
        isStopping = false
        startBackgroundThread()
        backgroundHandler?.post { openCamera() }  // immediate - no delay
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isStopping = true
        cleanup()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        stopBackgroundThread()
        backgroundThread = HandlerThread("SelfieThread_${System.currentTimeMillis()}").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join(500)
        } catch (e: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (isStopping) return
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager

            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: run { safeStop(); return }

            // Store sensor orientation for use in fixOrientation()
            sensorOrientation = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            // Always create fresh ImageReader
            imageReader?.close()
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                if (isStopping) { reader.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    backgroundHandler?.post { savePhoto(fixMirror(bytes)) }
                } finally { image.close() }
            }, backgroundHandler)

            // Fresh SurfaceTexture each time
            surfaceTexture?.release()
            surfaceTexture = SurfaceTexture(10)
            surfaceTexture!!.setDefaultBufferSize(640, 480)
            previewSurface?.release()
            previewSurface = Surface(surfaceTexture)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isStopping) { camera.close(); return }
                    cameraDevice = camera
                    createSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (!isStopping) safeStop()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error")
                    camera.close()
                    cameraDevice = null
                    safeStop()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "openCamera: ${e.message}")
            safeStop()
        }
    }

    private fun createSession(camera: CameraDevice) {
        if (isStopping) return
        try {
            val ir = imageReader ?: run { safeStop(); return }
            val ps = previewSurface ?: run { safeStop(); return }
            camera.createCaptureSession(listOf(ir.surface, ps),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (isStopping) { session.close(); return }
                        captureSession = session
                        backgroundHandler?.post { capturePhoto(session, camera) }  // zero delay - instant capture
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session config failed"); safeStop()
                    }
                }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "createSession: ${e.message}"); safeStop() }
    }

    private fun capturePhoto(session: CameraCaptureSession, camera: CameraDevice) {
        if (isStopping) return
        try {
            val ir = imageReader ?: run { safeStop(); return }
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(ir.surface)
                // Zero-lag: disable AF wait, use fixed/hyperfocal focus
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_QUALITY, 80.toByte())
            }
            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Capture failed: ${failure.reason}")
                    safeStop()
                }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "capturePhoto: ${e.message}"); safeStop() }
    }

    // Fix orientation: use sensor orientation + front-camera mirror correction
    private fun fixMirror(bytes: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            // Front camera sensor is typically 270° on most phones
            // We need: rotate by sensorOrientation, then flip horizontally (mirror fix)
            val rotation = sensorOrientation.toFloat()
            val matrix = Matrix().apply {
                postRotate(rotation)
                // Mirror horizontally after rotation for front camera
                postScale(-1f, 1f, bmp.width / 2f, bmp.height / 2f)
            }
            val fixed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            // Add watermark
            val watermarked = addWatermark(fixed)
            val out = ByteArrayOutputStream()
            watermarked.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            if (fixed !== watermarked) fixed.recycle()
            watermarked.recycle()
            Log.i(TAG, "Orientation + watermark done: sensor=${sensorOrientation}°")
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "fixMirror failed: ${e.message}")
            bytes
        }
    }

    private fun savePhoto(bytes: ByteArray) {
        if (isStopping) return
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "ThievesTrap"
            )
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "intruder_${ts}_${photosTaken + 1}.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            Log.i(TAG, "Saved: ${file.name}")
            photosTaken++

            // Notify MonitorService
            mainHandler.post {
                startService(Intent(this, MonitorService::class.java).apply {
                    action = "SELFIE_SAVED"
                    putExtra("path", file.absolutePath)
                    putExtra("timestamp", ts)
                    putExtra("count", photosTaken)
                })
            }

            // Fix 2: ONE photo only — stop and release camera immediately
            Log.i(TAG, "Capture complete ($photosTaken). Releasing camera immediately.")
            backgroundHandler?.post { safeStop() }
        } catch (e: Exception) { Log.e(TAG, "savePhoto: ${e.message}"); safeStop() }
    }

    private fun safeStop() {
        isStopping = true
        mainHandler.post { stopSelf() }
    }

    private fun cleanup() {
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = null
        try { previewSurface?.release() } catch (e: Exception) {}
        previewSurface = null
        try { surfaceTexture?.release() } catch (e: Exception) {}
        surfaceTexture = null
    }

    private fun addWatermark(src: Bitmap): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val text = "Captured by Thieves Trap | $ts"

        // ~7.5sp — 25% smaller than 10sp, very subtle
        val dpi = resources.displayMetrics.densityDpi
        val textSizePx = 7.5f * dpi / 160f  // 25% smaller

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(128, 255, 255, 255)  // 50% opacity subtle
            this.textSize = textSizePx
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            isFakeBoldText = false
            // Subtle shadow: 2px blur, black at 70% opacity
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.argb(179, 0, 0, 0))
        }

        val textWidth = textPaint.measureText(text)
        val margin = textSizePx * 0.8f
        val vPad = textSizePx * 0.3f
        val barH = textSizePx + vPad * 2.2f

        // Thin bar behind text — bottom-LEFT corner, 50% opacity
        val barLeft = 0f
        val barRight = textWidth + margin * 2f
        val barTop = result.height - barH - margin * 0.3f
        val bgPaint = Paint().apply {
            color = android.graphics.Color.argb(90, 0, 0, 0)  // subtle dark tint
        }
        canvas.drawRect(barLeft, barTop, barRight, result.height.toFloat(), bgPaint)

        // Text: bottom-LEFT corner (Fix 6)
        val textX = margin  // left-aligned from margin
        val textY = result.height.toFloat() - vPad - margin * 0.3f
        canvas.drawText(text, textX, textY, textPaint)

        return result
    }

    private fun buildNotif(): android.app.Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thieves Trap")
            .setContentText("Capturing intruder photo...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(CHANNEL_ID, "Selfie Capture",
                android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        fun takePhoto(context: Context, count: Int = 1) {
            try {
                context.startService(Intent(context, SelfieService::class.java).apply {
                    putExtra("count", count)
                })
            } catch (e: Exception) { Log.e("TT", "SelfieService start failed: ${e.message}") }
        }
    }
}
