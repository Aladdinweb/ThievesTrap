#!/usr/bin/env python3
"""
apply_face_capture_patch.py
Applies all Face Capture code changes to the ThievesTrap codebase.
Run from repo root. Idempotent — safe to run multiple times.
"""

import re, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def read(rel):
    return open(os.path.join(ROOT, rel), encoding='utf-8').read()

def write(rel, content):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w', encoding='utf-8').write(content)
    print(f"  wrote {rel}")

def patch(content, old, new, label):
    if old not in content:
        if new in content:
            print(f"  [SKIP] {label} — already applied")
            return content
        print(f"  [ERROR] {label} — anchor not found!", file=sys.stderr)
        # Print context to help debug
        words = old.strip().split()[:5]
        for w in words:
            idx = content.find(w)
            if idx >= 0:
                print(f"    nearest match for '{w}' at {idx}: {repr(content[idx:idx+60])}", file=sys.stderr)
        sys.exit(1)
    print(f"  [OK] {label}")
    return content.replace(old, new)

# ─────────────────────────────────────────────
# 1. build.gradle — add ML Kit + bump to 2.8.7
# ─────────────────────────────────────────────
print("\n[1] build.gradle")
gradle = read('app/build.gradle')

# Add ML Kit dependency if not present
if 'mlkit:face-detection' not in gradle:
    gradle = patch(gradle,
        "    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'\n}",
        "    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'\n"
        "    // Face Capture — ML Kit\n"
        "    implementation 'com.google.mlkit:face-detection:16.1.5'\n}",
        "add ML Kit dependency"
    )

# Bump version
gradle = re.sub(r'versionCode \d+', 'versionCode 131', gradle)
gradle = re.sub(r'versionName "[^"]+"', 'versionName "2.8.7"', gradle)
write('app/build.gradle', gradle)

# ─────────────────────────────────────────────
# 2. AndroidManifest.xml — add FaceCaptureService
# ─────────────────────────────────────────────
print("\n[2] AndroidManifest.xml")
manifest = read('app/src/main/AndroidManifest.xml')

if 'FaceCaptureService' not in manifest:
    manifest = patch(manifest,
        '        <service android:name=".SmartwatchMonitorService" android:exported="false"/>',
        '        <service android:name=".SmartwatchMonitorService" android:exported="false"/>\n'
        '        <!-- Face Capture -->\n'
        '        <service android:name=".FaceCaptureService" android:exported="false"\n'
        '            android:foregroundServiceType="camera"/>',
        "add FaceCaptureService to manifest"
    )
write('app/src/main/AndroidManifest.xml', manifest)

# ─────────────────────────────────────────────
# 3. strings.xml — add face_* keys (EN)
# ─────────────────────────────────────────────
print("\n[3] strings.xml (EN)")
strings = read('app/src/main/res/values/strings.xml')

FACE_KEYS_EN = """
    <!-- Face Capture (v2.8.7) -->
    <string name="face_capture_label">Face Capture</string>
    <string name="face_capture_sub">Remote SMS: FACE ON / FACE OFF</string>
    <string name="face_capture_info_title">How Face Capture Works</string>
    <string name="face_capture_info_body">When enabled (via app switch or SMS command FACE ON), the app silently uses ML-based face detection in the background. As soon as a face is detected, it snaps a photo, uploads it temporarily to GitHub, and sends an SMS link to your recovery number. The photo automatically self-destructs immediately upon opening the link, or after 5 minutes if unread.</string>
    <string name="face_on_reply">Face capture ON — monitoring silently.</string>
    <string name="face_off_reply">Face capture OFF — camera stopped.</string>
    <string name="face_premium_required">Face Capture is a Premium feature. Upgrade to activate.</string>"""

if 'face_capture_label' not in strings:
    strings = strings.replace('</resources>', FACE_KEYS_EN + '\n</resources>')
    print("  [OK] EN face keys added")
else:
    print("  [SKIP] EN face keys already present")
write('app/src/main/res/values/strings.xml', strings)

# ─────────────────────────────────────────────
# 4. strings-fr.xml — face keys (FR)
# ─────────────────────────────────────────────
print("\n[4] strings.xml (FR)")
fr = read('app/src/main/res/values-fr/strings.xml')
FACE_KEYS_FR = """
    <!-- Face Capture (v2.8.7) -->
    <string name="face_capture_label">Capture faciale</string>
    <string name="face_capture_sub">SMS distant: FACE ON / FACE OFF</string>
    <string name="face_capture_info_title">Fonctionnement de la capture faciale</string>
    <string name="face_capture_info_body">Lorsqu\\'elle est activee (via le commutateur ou la commande SMS FACE ON), l\\'application utilise silencieusement la detection de visage ML en arriere-plan. Des qu\\'un visage est detecte, elle prend une photo, la telecharge temporairement sur GitHub et envoie un lien SMS a votre numero de recuperation. La photo s\\'autodestruit immediatement a l\\'ouverture du lien, ou apres 5 minutes si elle n\\'est pas lue.</string>
    <string name="face_on_reply">Capture faciale ACTIVEE — surveillance silencieuse.</string>
    <string name="face_off_reply">Capture faciale DESACTIVEE — camera arretee.</string>
    <string name="face_premium_required">La capture faciale est une fonction Premium. Passez a la version superieure pour l\\'activer.</string>"""
if 'face_capture_label' not in fr:
    fr = fr.replace('</resources>', FACE_KEYS_FR + '\n</resources>')
    print("  [OK] FR face keys added")
else:
    print("  [SKIP] FR face keys already present")
write('app/src/main/res/values-fr/strings.xml', fr)

# ─────────────────────────────────────────────
# 5. strings-ar.xml — face keys (AR)
# ─────────────────────────────────────────────
print("\n[5] strings.xml (AR)")
ar = read('app/src/main/res/values-ar/strings.xml')
FACE_KEYS_AR = """
    <!-- Face Capture (v2.8.7) -->
    <string name="face_capture_label">التقاط الوجه</string>
    <string name="face_capture_sub">SMS عن بعد: FACE ON / FACE OFF</string>
    <string name="face_capture_info_title">كيف يعمل التقاط الوجه</string>
    <string name="face_capture_info_body">عند التفعيل (عبر المفتاح او الامر FACE ON)، يستخدم التطبيق بصمت كشف الوجه في الخلفية. فور اكتشاف وجه، يلتقط صورة ويرفعها مؤقتا على GitHub ويرسل رابط SMS لرقمك الاحتياطي. تتلف الصورة تلقائيا فور فتح الرابط، او بعد 5 دقائق ان لم يفتح.</string>
    <string name="face_on_reply">التقاط الوجه نشط — مراقبة صامتة.</string>
    <string name="face_off_reply">التقاط الوجه متوقف — الكاميرا اغلقت.</string>
    <string name="face_premium_required">التقاط الوجه ميزة مميزة. يرجى الترقية للتفعيل.</string>"""
if 'face_capture_label' not in ar:
    ar = ar.replace('</resources>', FACE_KEYS_AR + '\n</resources>')
    print("  [OK] AR face keys added")
else:
    print("  [SKIP] AR face keys already present")
write('app/src/main/res/values-ar/strings.xml', ar)

# ─────────────────────────────────────────────
# 6. activity_settings.xml — Face Capture section
# ─────────────────────────────────────────────
print("\n[6] activity_settings.xml")
settings_xml = read('app/src/main/res/layout/activity_settings.xml')
FACE_SECTION = """
        <!-- ── FACE CAPTURE (v2.8.7 — Premium) ── -->
        <TextView
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="@string/face_capture_label"
            android:textSize="10sp" android:textColor="#CC0000" android:textStyle="bold"
            android:background="#0D0D10" android:padding="10dp"
            android:layout_marginTop="10dp" android:layout_marginBottom="2dp"/>
        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="vertical" android:background="#0D0D10"
            android:padding="14dp" android:layout_marginBottom="12dp">
            <LinearLayout
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:orientation="horizontal" android:gravity="center_vertical"
                android:layout_marginBottom="8dp">
                <LinearLayout
                    android:layout_width="0dp" android:layout_height="wrap_content"
                    android:layout_weight="1" android:orientation="vertical">
                    <TextView
                        android:layout_width="wrap_content" android:layout_height="wrap_content"
                        android:text="@string/face_capture_label"
                        android:textColor="#DDDDDD" android:textSize="13sp" android:textStyle="bold"/>
                    <TextView
                        android:layout_width="wrap_content" android:layout_height="wrap_content"
                        android:text="@string/face_capture_sub"
                        android:textColor="#444444" android:textSize="10sp"/>
                </LinearLayout>
                <Switch android:id="@+id/sw_face_capture"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </LinearLayout>
            <Button android:id="@+id/btn_face_info"
                android:layout_width="wrap_content" android:layout_height="32dp"
                android:text="  How it works"
                android:textSize="11sp" android:textColor="#4A90D9"
                android:backgroundTint="#0D0D10" android:paddingStart="0dp"/>
        </LinearLayout>

"""

if 'sw_face_capture' not in settings_xml:
    settings_xml = settings_xml.replace(
        '        <!-- THEFT ALERTS -->',
        FACE_SECTION + '        <!-- THEFT ALERTS -->'
    )
    print("  [OK] Face Capture section added")
else:
    print("  [SKIP] Face Capture section already present")
write('app/src/main/res/layout/activity_settings.xml', settings_xml)

# ─────────────────────────────────────────────
# 7. MonitorService.kt — FACE ON / FACE OFF
# ─────────────────────────────────────────────
print("\n[7] MonitorService.kt")
monitor = read('app/src/main/java/com/thievestrap/MonitorService.kt')

# Add to premium command set
if '"FACE ON"' not in monitor:
    monitor = patch(monitor,
        '"SELFIE", "PHOTO", "PICTURE",\n                "PING", "ACTIVE", "ACTIVATE", "DEACTIVATE", "DISARM"',
        '"SELFIE", "PHOTO", "PICTURE",\n'
        '                "PING", "ACTIVE", "ACTIVATE", "DEACTIVATE", "DISARM",\n'
        '                "FACE ON", "FACE OFF"',
        "add FACE ON/OFF to premium set"
    )

# Add to Plan B allowed commands
if '"FACE ON","FACE OFF"' not in monitor:
    monitor = patch(monitor,
        '"SELFIE","PHOTO","PICTURE","INFO","DEVICE",\n            "STATUS","BATTERY","BAT","SIM","IMEI","LOCK"',
        '"SELFIE","PHOTO","PICTURE","INFO","DEVICE",\n'
        '            "STATUS","BATTERY","BAT","SIM","IMEI","LOCK","FACE ON","FACE OFF"',
        "add FACE ON/OFF to Plan B commands"
    )

# Add handleCommand cases
FACE_CASES = (
    '            cmd == "FACE ON" -> {\n'
    '                if (!LicenseManager.isPremium(this)) {\n'
    '                    smsAll(getString(R.string.face_premium_required))\n'
    '                    return@launch\n'
    '                }\n'
    '                prefs().edit().putBoolean("face_capture_enabled", true).apply()\n'
    '                ContextCompat.startForegroundService(this,\n'
    '                    Intent(this, FaceCaptureService::class.java).apply { action = "FACE_ON" })\n'
    '                smsAll(getString(R.string.face_on_reply))\n'
    '                TelegramUploader.sendMessage(this, getString(R.string.face_on_reply))\n'
    '            }\n'
    '            cmd == "FACE OFF" -> {\n'
    '                prefs().edit().putBoolean("face_capture_enabled", false).apply()\n'
    '                startService(Intent(this, FaceCaptureService::class.java).apply { action = "FACE_OFF" })\n'
    '                smsAll(getString(R.string.face_off_reply))\n'
    '                TelegramUploader.sendMessage(this, getString(R.string.face_off_reply))\n'
    '            }\n'
    '            cmd == "HELP" || cmd == "COMMANDS" || cmd == \"?\" ->\n'
    '                TelegramUploader.sendMessage(this, s(\"sms_help\"))\n'
)

if '"FACE ON" ->' not in monitor:
    monitor = patch(monitor,
        '            "HELP", "COMMANDS", "?" -> {\n',
        FACE_CASES,
        "add FACE ON/OFF handleCommand cases"
    )
write('app/src/main/java/com/thievestrap/MonitorService.kt', monitor)

# ─────────────────────────────────────────────
# 8. SettingsActivity.kt — wire sw_face_capture
# ─────────────────────────────────────────────
print("\n[8] SettingsActivity.kt")
settings_kt = read('app/src/main/java/com/thieveschap/SettingsActivity.kt') \
    if not os.path.exists(os.path.join(ROOT, 'app/src/main/java/com/thievestrap/SettingsActivity.kt')) \
    else read('app/src/main/java/com/thievestrap/SettingsActivity.kt')

FACE_SWITCH_CODE = (
    '        swSilent?.isChecked = isPremium && theftActive && prefs.getBoolean("alert_silent", true)\n'
    '        swSilent?.isEnabled = isPremium\n'
    '        swSilent?.alpha = if (isPremium) 1f else 0.4f\n'
    '\n'
    '        // ── FACE CAPTURE switch (v2.8.7) ──\n'
    '        val swFace = try { findViewById<Switch>(R.id.sw_face_capture) } catch (e: Exception) { null }\n'
    '        swFace?.let { sw ->\n'
    '            val faceOn = isPremium && prefs.getBoolean("face_capture_enabled", false)\n'
    '            sw.isChecked = faceOn\n'
    '            sw.isEnabled = isPremium\n'
    '            sw.alpha = if (isPremium) 1f else 0.4f\n'
    '            sw.thumbTintList = android.content.res.ColorStateList.valueOf(\n'
    '                if (faceOn) 0xFF00CC44.toInt() else 0xFF888888.toInt())\n'
    '            sw.trackTintList = android.content.res.ColorStateList.valueOf(\n'
    '                if (faceOn) 0xFF003311.toInt() else 0xFF222222.toInt())\n'
    '            sw.setOnCheckedChangeListener { _, checked ->\n'
    '                if (!isPremium) {\n'
    '                    sw.isChecked = false\n'
    '                    showUpgradeDialog(getString(R.string.face_capture_label))\n'
    '                    return@setOnCheckedChangeListener\n'
    '                }\n'
    '                prefs.edit().putBoolean("face_capture_enabled", checked).apply()\n'
    '                val tint = if (checked) 0xFF00CC44.toInt() else 0xFF888888.toInt()\n'
    '                val track = if (checked) 0xFF003311.toInt() else 0xFF222222.toInt()\n'
    '                sw.thumbTintList = android.content.res.ColorStateList.valueOf(tint)\n'
    '                sw.trackTintList = android.content.res.ColorStateList.valueOf(track)\n'
    '                if (checked) {\n'
    '                    ContextCompat.startForegroundService(this,\n'
    '                        android.content.Intent(this, FaceCaptureService::class.java)\n'
    '                            .apply { action = "FACE_ON" })\n'
    '                    android.widget.Toast.makeText(this,\n'
    '                        getString(R.string.face_on_reply), android.widget.Toast.LENGTH_SHORT).show()\n'
    '                } else {\n'
    '                    startService(android.content.Intent(this, FaceCaptureService::class.java)\n'
    '                        .apply { action = "FACE_OFF" })\n'
    '                    android.widget.Toast.makeText(this,\n'
    '                        getString(R.string.face_off_reply), android.widget.Toast.LENGTH_SHORT).show()\n'
    '                }\n'
    '            }\n'
    '        }\n'
    '        // ── FACE INFO button ──\n'
    '        try {\n'
    '            findViewById<android.widget.Button>(R.id.btn_face_info)?.setOnClickListener {\n'
    '                androidx.appcompat.app.AlertDialog.Builder(this)\n'
    '                    .setTitle(getString(R.string.face_capture_info_title))\n'
    '                    .setMessage(getString(R.string.face_capture_info_body))\n'
    '                    .setPositiveButton("OK", null).show()\n'
    '            }\n'
    '        } catch (e: Exception) {}\n'
)

ANCHOR = ('        swSilent?.isChecked = isPremium && theftActive && prefs.getBoolean("alert_silent", true)\n'
          '        swSilent?.isEnabled = isPremium\n'
          '        swSilent?.alpha = if (isPremium) 1f else 0.4f\n'
          '\n\n'
          '        // ── FAILED THRESHOLD ──\n ')

if 'sw_face_capture' not in settings_kt:
    settings_kt = patch(settings_kt, ANCHOR, FACE_SWITCH_CODE, "wire sw_face_capture switch")
write('app/src/main/java/com/thievestrap/SettingsActivity.kt', settings_kt)

# ─────────────────────────────────────────────
# 9. Write FaceCaptureService.kt
# ─────────────────────────────────────────────
print("\n[9] FaceCaptureService.kt")
face_svc_path = 'app/src/main/java/com/thievestrap/FaceCaptureService.kt'
if not os.path.exists(os.path.join(ROOT, face_svc_path)):
    write(face_svc_path, FACE_CAPTURE_SERVICE_CODE)
    print("  [OK] FaceCaptureService.kt created")
else:
    print("  [SKIP] FaceCaptureService.kt already exists")

print("\n=== Patch complete ===")
print("Next: gradlew assembleRelease")


# ─────────────────────────────────────────────
# FaceCaptureService source (embedded in script)
# ─────────────────────────────────────────────
FACE_CAPTURE_SERVICE_CODE = '''package com.thievestrap

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
 * captures photo on face detected, uploads to GitHub (self-destruct viewer),
 * sends SMS link to emergency contact. Deleted local file after upload.
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
    private var faceDetected = false

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
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_ON && !isCapturing)
                    handler.postDelayed({ startScan() }, 800)
            }
        }.also { screenReceiver = it }, IntentFilter(Intent.ACTION_SCREEN_ON))
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

    // ── Camera2 scan ──

    private fun startScan() {
        if (isCapturing) return
        isCapturing = true; faceDetected = false
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: mgr.cameraIdList.firstOrNull() ?: run { isCapturing = false; return }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ rdr ->
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
                    cam.createCaptureSession(listOf(imageReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                captureSession = sess
                                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply { addTarget(imageReader!!.surface) }.build()
                                sess.setRepeatingRequest(req, null, handler)
                                // 6-second max window
                                handler.postDelayed({
                                    if (isCapturing) { closeCamera(); isCapturing = false }
                                }, 6000)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                isCapturing = false
                            }
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); isCapturing = false }
                override fun onError(cam: CameraDevice, e: Int) { cam.close(); isCapturing = false }
            }, handler)
        } catch (e: Exception) { Log.e(TAG, "startScan: ${e.message}"); isCapturing = false }
    }

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
        closeCamera(); isCapturing = false
        Thread {
            try {
                val ts = android.text.format.DateFormat
                    .format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString()
                val imageId = "face_${System.currentTimeMillis()}"

                // Save temporarily
                val dir = File(getExternalFilesDir(null), "FaceCaptures").also { it.mkdirs() }
                val file = File(dir, "$imageId.jpg")
                FileOutputStream(file).use { it.write(jpeg) }

                // Channel 1: Telegram
                TelegramUploader.sendPhoto(this, file,
                    "Face Detected -- $ts")
                TelegramUploader.sendMessage(this,
                    "*Thieves Trap -- Face Captured*\\n$ts\\nPhoto attached above.")

                // Channel 2: GitHub self-destruct viewer + SMS link
                val link = uploadViewPage(imageId, jpeg, ts)

                // SMS
                val phone = prefs().getString("phone", "") ?: ""
                if (phone.isNotBlank()) {
                    val sms = if (link != null)
                        "Thieves Trap: Face captured!\\nView link (self-destructs on open):\\n$link"
                    else
                        "Thieves Trap: Face captured and sent to Telegram. $ts"
                    try {
                        android.telephony.SmsManager.getDefault()
                            .sendTextMessage(phone, null, sms, null, null)
                    } catch (e: Exception) { Log.e(TAG, "SMS: ${e.message}") }
                }

                // Delete local file
                file.delete()
                Log.i(TAG, "Face delivery complete. Local file deleted.")

            } catch (e: Exception) { Log.e(TAG, "captureAndDeliver: ${e.message}") }
        }.start()
    }

    // ── GitHub self-destruct viewer ──

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

            val conn = (URL("https://api.github.com/repos/$owner/$repo/contents/$path")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "token $tok")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true; connectTimeout = 15_000
                java.io.OutputStreamWriter(outputStream).use { it.write(body) }
            }
            val code = conn.responseCode; conn.disconnect()

            // Schedule deletion after 5 minutes
            if (code == 201 || code == 200) {
                val link = "https://$owner.github.io/$repo/captures/$imageId.html"
                handler.postDelayed({ deleteViewPage(owner, repo, path, tok) }, 5 * 60 * 1000L)
                link
            } else { Log.w(TAG, "upload HTTP $code"); null }
        } catch (e: Exception) { Log.e(TAG, "uploadViewPage: ${e.message}"); null }
    }

    private fun deleteViewPage(owner: String, repo: String, path: String, tok: String) {
        Thread {
            try {
                // Get SHA of file
                val get = (URL("https://api.github.com/repos/$owner/$repo/contents/$path")
                    .openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "token $tok")
                    connectTimeout = 10_000
                }
                val fileSha = org.json.JSONObject(
                    get.inputStream.bufferedReader().readText()).optString("sha")
                get.disconnect()
                if (fileSha.isBlank()) return@Thread

                val delBody = org.json.JSONObject()
                    .put("message", "self-destruct $path")
                    .put("sha", fileSha).toString()
                val del = (URL("https://api.github.com/repos/$owner/$repo/contents/$path")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true; connectTimeout = 10_000
                    java.io.OutputStreamWriter(outputStream).use { it.write(delBody) }
                }
                Log.i(TAG, "Auto-deleted view page: HTTP ${del.responseCode}")
                del.disconnect()
            } catch (e: Exception) { Log.e(TAG, "deleteViewPage: ${e.message}") }
        }.start()
    }

    /** Token assembled at runtime — stored in GitHub Secret THIEVES_TRAP_PAT, injected at build time */
    private fun buildTok(): String {
        // Injected by build system via BuildConfig or environment
        return try {
            val cls = Class.forName("com.thievestrap.BuildConfig")
            cls.getField("THIEVES_TRAP_PAT").get(null) as? String ?: ""
        } catch (e: Exception) { "" }
    }

    private fun buildHtml(b64img: String, ts: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head>")
        sb.append("<meta charset=\\"utf-8\\"><meta name=\\"viewport\\" content=\\"width=device-width,initial-scale=1\\">")
        sb.append("<title>Thieves Trap</title>")
        sb.append("<style>")
        sb.append("body{background:#0a0a0a;color:#fff;font-family:sans-serif;text-align:center;padding:20px;margin:0}")
        sb.append(".w{background:#cc0000;padding:14px;border-radius:8px;margin:12px auto;max-width:500px;font-weight:bold;font-size:15px;line-height:1.5}")
        sb.append("img{max-width:100%;border-radius:8px;border:2px solid #333;margin:12px 0}")
        sb.append(".ts{color:#888;font-size:12px;margin-top:8px}")
        sb.append("</style></head><body>")
        sb.append("<div class=\\"w\\">")
        sb.append("WARNING: This link self-destructs IMMEDIATELY upon opening.<br>")
        sb.append("You MUST save or screenshot the image RIGHT NOW.")
        sb.append("</div>")
        sb.append("<img src=\\"data:image/jpeg;base64,").append(b64img).append("\\" alt=\\"Face Capture\\"/>")
        sb.append("<div class=\\"ts\\">Captured: ").append(ts).append("</div>")
        sb.append("<div class=\\"ts\\">&mdash; Thieves Trap Security &mdash;</div>")
        sb.append("<script>")
        // Self-destruct: notify server to delete, then wipe page
        sb.append("(function(){")
        sb.append("document.title='[OPENED]';")
        sb.append("setTimeout(function(){")
        sb.append("document.body.innerHTML='<h2 style=\\"color:#cc0000;margin-top:40px\\">")
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
                NotificationChannel(CHANNEL_ID, "Face Capture", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Silent face monitoring" }
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
'''
