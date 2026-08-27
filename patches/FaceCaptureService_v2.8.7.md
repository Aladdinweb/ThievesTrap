# Face Capture Feature — v2.8.7 rebuild

Combined deliverable: full service source + integration instructions.

---

## Part 1 — `FaceCaptureService.kt`

Save this as `app/src/main/java/com/thievestrap/FaceCaptureService.kt`

```kotlin
package com.thievestrap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FaceCaptureService
 *
 * Started by "FACE ON" SMS command, stopped by "FACE OFF".
 * Silently monitors the front camera in the background (no preview UI).
 * Runs each analysis frame through ML Kit's on-device face detector; when a
 * face is confidently detected, grabs that frame as a JPEG and:
 *   1. Sends it via TelegramUploader.sendPhoto()
 *   2. Sends an SMS status update with GPS coordinates to the emergency contact
 *
 * NOTE: adjust the two TODOs below (Telegram call signature + emergency
 * contact / prefs keys) to match your actual TelegramUploader.kt and
 * SettingsActivity prefs — I don't have those files' current content, only
 * their described behavior from STATE.md.
 */
class FaceCaptureService : Service() {

    companion object {
        private const val TAG = "FaceCaptureService"
        private const val NOTIF_ID = 9101
        private const val CHANNEL_ID = "face_capture_channel"

        // Minimum time between two automatic captures, to avoid spamming
        // Telegram/SMS if the intruder keeps the phone pointed at their face.
        private const val CAPTURE_COOLDOWN_MS = 5 * 60 * 1000L

        // Low-res analysis stream keeps ML Kit fast and battery use low.
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480

        @Volatile
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(0.25f)
        .build()
    private val faceDetector by lazy { FaceDetection.getClient(detectorOptions) }

    private val processingLock = AtomicBoolean(false)
    private var lastCaptureTime = 0L

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        isRunning = true
        openFrontCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        closeCamera()
        stopBackgroundThread()
        faceDetector.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- background thread plumbing ----------

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("FaceCaptureBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "background thread join interrupted", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    // ---------- camera setup ----------

    private fun findFrontCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    @SuppressLint("MissingPermission")
    private fun openFrontCamera() {
        val camId = findFrontCameraId()
        if (camId == null) {
            Log.e(TAG, "No front camera available on this device")
            stopSelf()
            return
        }

        imageReader = ImageReader.newInstance(
            ANALYSIS_WIDTH, ANALYSIS_HEIGHT, ImageFormat.YUV_420_888, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleFrame(image)
            }, backgroundHandler)
        }

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                startCaptureSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                device.close()
                cameraDevice = null
                stopSelf()
            }
        }, backgroundHandler)
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val surface = reader.surface

        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }

        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "setRepeatingRequest failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    stopSelf()
                }
            },
            backgroundHandler
        )
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    // ---------- frame -> ML Kit detection ----------

    private fun handleFrame(image: Image) {
        try {
            if (processingLock.get()) return
            val now = System.currentTimeMillis()
            if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return
            if (!processingLock.compareAndSet(false, true)) return

            val inputImage = InputImage.fromMediaImage(image, 0)
            // Snapshot the JPEG bytes now, while the Image is still valid —
            // ML Kit's callback runs async and the Image gets closed in the
            // finally block below.
            val jpegBytes = yuvImageToJpegBytes(image)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        lastCaptureTime = System.currentTimeMillis()
                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        onFaceCaptured(bitmap)
                    }
                }
                .addOnFailureListener { e -> Log.e(TAG, "Face detection failed", e) }
                .addOnCompleteListener { processingLock.set(false) }
        } finally {
            image.close()
        }
    }

    private fun yuvImageToJpegBytes(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        return out.toByteArray()
    }

    // ---------- delivery ----------

    private fun onFaceCaptured(bitmap: Bitmap) {
        serviceScope.launch {
            try {
                val file = saveBitmapToFile(bitmap)

                // TODO: match this to TelegramUploader's real method signature.
                // Based on STATE.md it already exposes sendPhoto() from the
                // earlier (reverted) face-capture attempt.
                TelegramUploader.sendPhoto(applicationContext, file, "Face detected while armed")

                sendGpsStatusSms()
            } catch (e: Exception) {
                Log.e(TAG, "Delivery of captured face failed", e)
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap): File {
        val dir = File(applicationContext.filesDir, "face_captures").apply { mkdirs() }
        val file = File(dir, "face_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        return file
    }

    private fun sendGpsStatusSms() {
        val emergencyNumber = getEmergencyContact()
        if (emergencyNumber.isNullOrBlank()) {
            Log.e(TAG, "No emergency contact configured — skipping SMS")
            return
        }
        val location = getLastKnownLocation()
        val body = if (location != null) {
            "Thieves Trap ALERT: Face detected. Location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            "Thieves Trap ALERT: Face detected. Location unavailable."
        }
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(emergencyNumber, null, parts, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Status SMS send failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in lm.getProviders(true)) {
            val loc = try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.accuracy < best.accuracy) best = loc
        }
        return best
    }

    // TODO: point this at wherever the emergency contact is actually stored
    // (SettingsActivity's SharedPreferences key) — placeholder name below.
    private fun getEmergencyContact(): String? {
        val prefs = getSharedPreferences("thieves_trap_prefs", Context.MODE_PRIVATE)
        return prefs.getString("emergency_contact", null)
    }

    // ---------- notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Security Monitoring", NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // NOTE: swap R.drawable.ic_shield for whatever small icon MonitorService
        // already uses for its own foreground notification, to avoid a missing
        // resource compile error.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thieves Trap")
            .setContentText("Security monitoring active")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
```

---

## Part 2 — Integration Guide


I don't have the live contents of `MonitorService.kt`, `AndroidManifest.xml`, or
`build.gradle`, so below are the exact blocks to drop into each — paste your
current file content if you want me to generate a precise combined-commit
patch instead of you doing it by hand.

## 1. `app/build.gradle` — add the ML Kit dependency

```gradle
dependencies {
    // ... existing dependencies ...
    implementation 'com.google.mlkit:face-detection:16.1.7'
}
```

## 2. `AndroidManifest.xml` — declare the service + permissions

```xml
<!-- Permissions (skip any already present, e.g. from the selfie feature) -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- Inside <application> -->
<service
    android:name=".FaceCaptureService"
    android:foregroundServiceType="camera"
    android:exported="false" />
```

`FOREGROUND_SERVICE_CAMERA` is required on Android 14+ for a camera-type
foreground service — without it `startForeground()` throws at runtime on
those devices even though the build compiles fine.

## 3. `MonitorService.kt` — wire the SMS commands

Add this into whatever `when (command)` block already handles `WHERE`,
`ALARM`, etc. (per STATE.md, that's inside `processSmsPdus()` / the command
dispatch it calls):

```kotlin
"FACE ON" -> {
    val svcIntent = Intent(applicationContext, FaceCaptureService::class.java)
    ContextCompat.startForegroundService(applicationContext, svcIntent)
    replySms(sender, "Face capture monitoring: ON")
}
"FACE OFF" -> {
    applicationContext.stopService(Intent(applicationContext, FaceCaptureService::class.java))
    replySms(sender, "Face capture monitoring: OFF")
}
```

Swap `replySms(sender, ...)` for whatever your actual reply-sending helper is
called (STATE.md references `buildFullInfo` / template building in this file
but not a specific reply-send function name).

Since `FACE ON` / `FACE OFF` are two-word commands, make sure they're matched
*before* any single-token parsing/trim logic that might split on the space —
same class of bug that hit `PING <mins>` and Plan B's `COMMAND PIN` pattern
historically.

## 4. Two TODOs inside `FaceCaptureService.kt` itself

- **`TelegramUploader.sendPhoto(...)`** — I assumed the signature
  `sendPhoto(context, file, caption)` based on STATE.md's description of the
  earlier (reverted) face-capture attempt already calling
  `TelegramUploader.sendPhoto()`. Adjust the call if the real signature
  differs.
- **`getEmergencyContact()`** — currently reads
  `thieves_trap_prefs / emergency_contact`, a placeholder. Point it at
  whatever `SettingsActivity` actually stores the emergency contact under.
- **`R.drawable.ic_shield`** in `buildNotification()` — swap for whatever
  small-icon resource `MonitorService`'s own foreground notification uses, so
  you're not referencing a drawable that doesn't exist.

## 5. Known pitfalls from prior face-capture attempts (v2.8.6/v2.8.7) — avoided here

- **AAPT2 4-byte emoji crash**: no emoji anywhere in this service or its
  notification strings — everything is plain ASCII.
- **Groovy DSL / string escaping issues**: this version has no JSON body
  construction (no GitHub Pages self-destruct viewer this time — delivery is
  just Telegram + SMS), so that failure class doesn't apply.
- **ML Kit OOM**: analysis frames are downscaled to 640x480 via the
  `ImageReader` size rather than running detection on full-resolution camera
  output, and only one frame is in flight at a time
  (`processingLock` + `ImageReader` maxImages=2).

## 6. Testing checklist before release

- [ ] Send `FACE ON` from a registered/premium number (or Plan B `FACE ON <pin>`
      if you want that supported — not wired above, add a `matchPlanBCommand`
      case if so) → confirm foreground notification appears, camera indicator
      (green dot) shows.
- [ ] Have a face appear in front of the camera → confirm Telegram photo
      arrives + SMS with a Maps link arrives at the emergency contact.
- [ ] Confirm no second capture/SMS/Telegram fires within 5 minutes
      (cooldown).
- [ ] Send `FACE OFF` → confirm camera indicator disappears and notification
      is cleared.
- [ ] Kill the app from recents while `FACE ON` is active → confirm the
      foreground service either survives or is cleanly restarted (add to
      `BootReceiver`/task-removed handling if you want persistence across
      task-kill, not included above).
