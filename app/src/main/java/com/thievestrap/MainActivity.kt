package com.thievestrap

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var drawerLayout: DrawerLayout

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) requestAdminIfNeeded()
        else Toast.makeText(this, s("permissions_required"), Toast.LENGTH_LONG).show()
    }
    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startMonitoring() }

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        drawerLayout = findViewById(R.id.drawer_layout)
        if (!prefs.contains("password")) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish(); return
        }
        setupButtons()
    }

    private var settingsRefreshReceiver: BroadcastReceiver? = null

    override fun onResume() {
        super.onResume()
        try {
            val sw = findViewById<Switch>(R.id.sw_survival_timer)
            val on = prefs.getBoolean("survival_timer_on", false)
            sw?.isChecked = on
            findViewById<android.view.View>(R.id.survival_options_panel)
                ?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        } catch (e: Exception) {}

        try {
            val swWatch = findViewById<Switch>(R.id.sw_watch_tether)
            val watchOn = prefs.getBoolean("watch_tether_on", false)
            swWatch?.isChecked = watchOn
            updateWatchTetherStatus(watchOn)
        } catch (e: Exception) {}

        settingsRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val survivalOn = prefs.getBoolean("survival_timer_on", false)
                try {
                    findViewById<Switch>(R.id.sw_survival_timer)?.isChecked = survivalOn
                    findViewById<TextView>(R.id.tv_survival_status)?.text =
                        if (survivalOn) "Active" else "Off"
                    findViewById<android.view.View>(R.id.survival_options_panel)
                        ?.visibility = if (survivalOn) android.view.View.VISIBLE
                        else android.view.View.GONE
                } catch (e: Exception) {}
            }
        }
        registerReceiver(settingsRefreshReceiver,
            IntentFilter("com.thievestrap.SETTINGS_REFRESH"))

        window.decorView.post { updateStatus() }
    }

    override fun onPause() {
        super.onPause()
        try { settingsRefreshReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        settingsRefreshReceiver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    private fun s(key: String) = Strings.get(this, key)

    // ── Setup all buttons ──────────────────────────────────────

    private fun setupButtons() {

        try {
            findViewById<android.widget.ImageView>(R.id.btn_about).setOnClickListener {
                startActivity(Intent(this, AboutActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        } catch (e: Exception) {}

        try {
            findViewById<TextView>(R.id.btn_menu).setOnClickListener {
                hapticFeedback(20)
                drawerLayout.openDrawer(android.view.Gravity.END)
            }
        } catch (e: Exception) {}

        // ── Nav drawer ────────────────────────────────────────
        try {
            fun close() = drawerLayout.closeDrawer(android.view.Gravity.END)

            findViewById<android.view.View>(R.id.nav_settings).setOnClickListener {
                close(); startActivity(Intent(this, SettingsActivity::class.java))
            }
            findViewById<android.view.View>(R.id.nav_premium).setOnClickListener {
                close(); startActivity(Intent(this, PremiumActivity::class.java))
            }
            findViewById<android.view.View>(R.id.nav_change_pin).setOnClickListener {
                close(); showPinChangeDialog()
            }
            try {
                findViewById<android.view.View>(R.id.nav_language)
                    .setOnClickListener { close(); showLanguageDialog() }
            } catch (e: Exception) {}

            val swBioDrawer = findViewById<Switch>(R.id.sw_biometric_drawer)
            swBioDrawer.isChecked = prefs.getBoolean("biometric_unlock", false)
            swBioDrawer.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("biometric_unlock", checked).apply()
            }
            findViewById<android.view.View>(R.id.nav_fingerprint).setOnClickListener {
                swBioDrawer.isChecked = !swBioDrawer.isChecked
            }

            // ── Survival Timer ────────────────────────────────
            val tvSurvivalStatus = try {
                findViewById<TextView>(R.id.tv_survival_status)
            } catch (e: Exception) { null }
            val swSurvival = try {
                findViewById<Switch>(R.id.sw_survival_timer)
            } catch (e: Exception) { null }
            val optionsPanel = try {
                findViewById<android.view.View>(R.id.survival_options_panel)
            } catch (e: Exception) { null }

            val survivalOn = prefs.getBoolean("survival_timer_on", false)
            swSurvival?.isChecked = survivalOn
            optionsPanel?.visibility =
                if (survivalOn) android.view.View.VISIBLE else android.view.View.GONE
            val savedDurMs = prefs.getLong("survival_duration", 0L)
            tvSurvivalStatus?.text = when {
                survivalOn && savedDurMs > 0 -> "Set to ${savedDurMs / 60000} min"
                survivalOn -> "Active"
                else -> "Off"
            }
            swSurvival?.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    optionsPanel?.visibility = android.view.View.VISIBLE
                    showSurvivalTimerPicker(tvSurvivalStatus)
                } else {
                    prefs.edit().putBoolean("survival_timer_on", false).apply()
                    SurvivalTimerService.stop(this)
                    tvSurvivalStatus?.text = "Off"
                    optionsPanel?.visibility = android.view.View.GONE
                    swSurvival.isChecked = false
                }
            }
            try {
                findViewById<TextView>(R.id.nav_survival_info)
                    .setOnClickListener { showSurvivalInfo() }
            } catch (e: Exception) {}
            try {
                findViewById<android.view.View>(R.id.nav_survival_duration)
                    .setOnClickListener { showSurvivalTimerPicker(tvSurvivalStatus) }
            } catch (e: Exception) {}
            try {
                findViewById<android.view.View>(R.id.nav_survival_recipient)
                    .setOnClickListener { close(); showRecipientDialog() }
            } catch (e: Exception) {}

            // ── WATCH TETHER — v2.7.9b: info badge + responsive switch ──
            try {
                // ℹ️ Info badge — explains the mechanism
                try {
                    findViewById<android.view.View>(R.id.nav_watch_tether_info)
                        .setOnClickListener { showWatchTetherInfoDialog() }
                } catch (e: Exception) {}

                val swWatch = findViewById<Switch>(R.id.sw_watch_tether)
                val watchOn = prefs.getBoolean("watch_tether_on", false)
                swWatch.isChecked = watchOn
                updateWatchTetherStatus(watchOn)

                swWatch.setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)
                            ?.adapter
                        if (btAdapter == null || !btAdapter.isEnabled) {
                            Toast.makeText(this,
                                "Enable Bluetooth and pair your smartwatch first.",
                                Toast.LENGTH_LONG).show()
                            swWatch.isChecked = false
                            return@setOnCheckedChangeListener
                        }
                        val hasBonded = try {
                            btAdapter.bondedDevices?.isNotEmpty() == true
                        } catch (e: Exception) { false }
                        if (!hasBonded) {
                            Toast.makeText(this,
                                "No paired Bluetooth device found. Pair your watch first.",
                                Toast.LENGTH_LONG).show()
                            swWatch.isChecked = false
                            return@setOnCheckedChangeListener
                        }
                        prefs.edit().putBoolean("watch_tether_on", true).apply()
                        SmartwatchMonitorService.start(this)
                        updateWatchTetherStatus(true)
                        hapticFeedback(40)
                        Toast.makeText(this, "Watch Tether ON — monitoring Bluetooth",
                            Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit().putBoolean("watch_tether_on", false).apply()
                        SmartwatchMonitorService.stop(this)
                        updateWatchTetherStatus(false)
                        hapticFeedback(20)
                    }
                }
            } catch (e: Exception) {}

            // ── CHECK FOR UPDATE — v2.7.9 ──
            try {
                findViewById<android.view.View>(R.id.nav_check_update).setOnClickListener {
                    hapticFeedback(30)
                    close()
                    UpdateManager.checkForUpdate(this)
                }
            } catch (e: Exception) {}

        } catch (e: Exception) {}

        // ── ARM / DISARM — v2.7.9b: first-arm guidance dialog ──
        findViewById<Button>(R.id.btn_arm).setOnClickListener {
            hapticFeedback()
            if (prefs.getBoolean("running", false)) {
                showDisarmDialog()
            } else {
                // Step 1: Validate mandatory fields
                val phone = prefs.getString("phone", "") ?: ""
                val imei  = prefs.getString("imei",  "") ?: ""
                if (phone.isBlank()) {
                    Toast.makeText(this,
                        "Please set your Emergency Contact in Settings first.",
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return@setOnClickListener
                }

                // Step 2: Check isFirstArm
                val isFirstArm = prefs.getBoolean("isFirstArm", true)
                if (isFirstArm) {
                    // Step 3: Show guidance dialog
                    showFirstArmGuidanceDialog()
                } else {
                    checkPermissionsAndStart()
                }
            }
        }

        try {
            findViewById<Button>(R.id.btn_theft_mode).setOnClickListener {
                hapticFeedback()
                if (!prefs.getBoolean("running", false)) {
                    Toast.makeText(this, "Arm the app first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!LicenseManager.isPremium(this)) {
                    showUpgradeDialog("Theft Mode"); return@setOnClickListener
                }
                val on = prefs.getBoolean("theft_mode", false)
                if (on) prefs.edit().putBoolean("location_ping", false).apply()
                startService(Intent(this, MonitorService::class.java).apply {
                    action = if (!on) "THEFT_ON" else "THEFT_OFF"
                })
                updateStatus()
            }
        } catch (e: Exception) {}

        try {
            findViewById<android.view.View>(R.id.btn_selfies).setOnClickListener {
                hapticFeedback()
                startActivity(Intent(this, SelfiesActivity::class.java))
            }
        } catch (e: Exception) {}

        try {
            findViewById<android.view.View>(R.id.btn_remote_guide_icon).setOnClickListener {
                hapticFeedback()
                startActivity(Intent(this, RemoteGuideActivity::class.java))
            }
        } catch (e: Exception) {}

        try {
            findViewById<Button>(R.id.btn_premium).setOnClickListener {
                startActivity(Intent(this, PremiumActivity::class.java))
            }
        } catch (e: Exception) {}
    }

    // ── v2.7.9b: First-ARM guidance dialog ───────────────────────
    // Shows once before the user ever arms the app.
    // Step 3 of the ARM flow.

    private fun showFirstArmGuidanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("\uD83D\uDCCB Important: Save Your Commands")
            .setMessage(
                "Before locking your protection, please read the remote SMS control " +
                "layout so you can track your device if it goes missing."
            )
            .setPositiveButton("View Commands Guide") { _, _ ->
                // Go to guide — user can come back and arm afterward
                startActivity(Intent(this, RemoteGuideActivity::class.java))
            }
            .setNegativeButton("Got It, Activate Protection") { _, _ ->
                // Mark as seen, then proceed with arm flow
                prefs.edit().putBoolean("isFirstArm", false).apply()
                checkPermissionsAndStart()
            }
            .setCancelable(true)
            .show()
    }

    // ── v2.7.9b: Watch Tether info dialog ────────────────────────

    private fun showWatchTetherInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("\u231A Watch Tether — How It Works")
            .setMessage(
                "When enabled, Thieves Trap monitors the Bluetooth connection " +
                "to your paired smartwatch in the background.\n\n" +
                "\uD83D\uDD12 If the watch disconnects (phone separated):\n" +
                "   \u2022 Screen locks immediately via Device Admin\n" +
                "   \u2022 Repeating vibration alert fires\n" +
                "   \u2022 A 5-minute silent countdown begins\n\n" +
                "\u2705 During the countdown you can tap:\n" +
                "   \u2022 \"I'm Safe\" — cancels, no PIN required\n" +
                "   \u2022 \"\uD83D\uDEA8 TRIGGER ALARM\" — max-volume siren\n" +
                "   \u2022 \"\uD83D\uDD15 DISARM / MUTE\" — stops alarm + cancels\n\n" +
                "\uD83D\uDCE9 If the countdown expires without confirmation, one " +
                "emergency SMS with your GPS location is sent automatically " +
                "to your registered Emergency Contact."
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    // ── Watch tether status text ───────────────────────────────

    private fun updateWatchTetherStatus(on: Boolean) {
        try {
            val tvStatus = findViewById<TextView>(R.id.tv_watch_tether_status)
            val tvHint   = findViewById<TextView>(R.id.tv_watch_tether_hint)
            if (on) {
                tvStatus?.text = "ON \u2014 Bluetooth connection monitored"
                tvStatus?.setTextColor(0xFF00CC44.toInt())
                tvHint?.text = "Active: phone locks + alarm + 5-min SMS on disconnect."
                tvHint?.setTextColor(0xFF1A4D1A.toInt())
            } else {
                tvStatus?.text = "Off \u2014 Bluetooth monitoring inactive"
                tvStatus?.setTextColor(0xFF444444.toInt())
                tvHint?.text = "Pair watch via Bluetooth. If disconnected: phone locks + 5-min SMS countdown."
                tvHint?.setTextColor(0xFF333333.toInt())
            }
        } catch (e: Exception) {}
    }

    // ── Upgrade dialog ─────────────────────────────────────────

    private fun showUpgradeDialog(feature: String) {
        AlertDialog.Builder(this)
            .setTitle("Premium Required")
            .setMessage("$feature is available in the Full Protection plan.")
            .setPositiveButton("Upgrade Now") { _, _ ->
                startActivity(Intent(this, PremiumActivity::class.java))
            }
            .setNegativeButton("Not now", null).show()
    }

    // ── Disarm flow ────────────────────────────────────────────

    private fun showDisarmDialog() {
        if (prefs.getBoolean("biometric_unlock", false)) {
            val bm = BiometricManager.from(this)
            if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS) {
                showBiometricPrompt(); return
            }
        }
        showPinDialog()
    }

    private fun showBiometricPrompt() {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    r: BiometricPrompt.AuthenticationResult) { stopMonitoring() }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    showPinDialog()
                }
            }).authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Disarm Thieves Trap")
            .setSubtitle("Use fingerprint to stop monitoring")
            .setNegativeButtonText("Use PIN instead").build())
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            hint = s("enter_pin")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this).setTitle(s("disarm_title")).setView(input)
            .setPositiveButton(s("disarm")) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin == prefs.getString("password", "")) stopMonitoring()
                else {
                    Toast.makeText(this, s("wrong_pin"), Toast.LENGTH_LONG).show()
                    startService(Intent(this, MonitorService::class.java).apply {
                        action = "TAMPER"; putExtra("reason", "Wrong PIN in UI!")
                    })
                }
            }.setNegativeButton(s("cancel"), null).show()
    }

    // ── PIN change dialog ──────────────────────────────────────

    private fun showPinChangeDialog() {
        val oldInput = EditText(this).apply {
            hint = "Current PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val newInput = EditText(this).apply {
            hint = "New PIN (4+ digits)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(oldInput); addView(newInput)
        }
        AlertDialog.Builder(this).setTitle("Change Security PIN").setView(container)
            .setPositiveButton("Change") { _, _ ->
                val old = oldInput.text.toString().trim()
                val new = newInput.text.toString().trim()
                if (old != prefs.getString("password", ""))
                    Toast.makeText(this, "Wrong current PIN!", Toast.LENGTH_SHORT).show()
                else if (new.length < 4)
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                else {
                    prefs.edit().putString("password", new).apply()
                    Toast.makeText(this, "PIN changed!", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ── Language dialog ────────────────────────────────────────

    private fun showLanguageDialog() {
        val gb = "\uD83C\uDDEC\uD83C\uDDE7"
        val fr = "\uD83C\uDDEB\uD83C\uDDF7"
        val dz = "\uD83C\uDDE9\uD83C\uDDFF"
        val options = arrayOf("$gb  English", "$fr  Français", "$dz  العربية")
        val codes   = arrayOf("en", "fr", "ar")
        val cur     = LocaleHelper.getLang(this)
        val sel     = codes.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("\uD83C\uDF10  Select Language")
            .setSingleChoiceItems(options, sel) { dlg, which ->
                prefs.edit().putString("language", codes[which]).apply()
                LocaleHelper.applyLocale(this)
                dlg.dismiss()
                val i = packageManager.getLaunchIntentForPackage(packageName)
                    ?: Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i); finish()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Permissions & monitoring ───────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestAdminIfNeeded()
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun requestAdminIfNeeded() {
        if (!dpm.isAdminActive(adminComponent)) {
            adminLauncher.launch(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, s("admin_explanation"))
            })
        } else startMonitoring()
    }

    private fun startMonitoring() {
        val phone = prefs.getString("phone", "") ?: ""
        if (phone.isBlank()) {
            Toast.makeText(this, s("no_contact"), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java)); return
        }
        playLockSound()
        hapticFeedback(60)
        ContextCompat.startForegroundService(this,
            Intent(this, MonitorService::class.java).apply { action = "START" })
        prefs.edit()
            .putBoolean("running", true)
            .putLong("arm_time", System.currentTimeMillis())
            .apply()
        Toast.makeText(this, s("armed"), Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitorService::class.java))
        prefs.edit().putBoolean("running", false).putBoolean("theft_mode", false).apply()
        Toast.makeText(this, s("disarmed"), Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun playLockSound() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.lock_click)
            mediaPlayer?.setOnCompletionListener { it.release(); mediaPlayer = null }
            mediaPlayer?.start()
        } catch (e: Exception) {}
    }

    // ── Status UI update ───────────────────────────────────────

    private fun updateStatus() {
        val running     = prefs.getBoolean("running", false)
        val theft       = prefs.getBoolean("theft_mode", false)
        val isPremium   = LicenseManager.isPremium(this)
        val adminActive = dpm.isAdminActive(adminComponent)
        val armTime     = prefs.getLong("arm_time", 0)
        val lastAlert   = prefs.getString("last_alert", null)

        try {
            val shieldView = findViewById<android.widget.ImageView>(R.id.iv_shield_main)
            when {
                running && theft -> {
                    shieldView.setColorFilter(android.graphics.Color.parseColor("#00FF66"))
                    findViewById<TextView>(R.id.tv_status).apply {
                        text = "FULLY PROTECTED"; setTextColor(0xFF00FF66.toInt())
                    }
                }
                running -> {
                    shieldView.setColorFilter(android.graphics.Color.parseColor("#FF8C00"))
                    findViewById<TextView>(R.id.tv_status).apply {
                        text = "ARMED"; setTextColor(0xFFFF8C00.toInt())
                    }
                }
                else -> {
                    shieldView.setColorFilter(android.graphics.Color.parseColor("#CC0000"))
                    findViewById<TextView>(R.id.tv_status).apply {
                        text = "UNPROTECTED"; setTextColor(0xFFCC0000.toInt())
                    }
                }
            }
        } catch (e: Exception) {}

        try {
            val sub = findViewById<TextView>(R.id.tv_sub_status)
            when {
                running && theft -> { sub.text = "Full Protection"; sub.setTextColor(0xFF00FF66.toInt()) }
                running          -> { sub.text = "Medium Protection"; sub.setTextColor(0xFFFF8C00.toInt()) }
                else             -> sub.text = ""
            }
        } catch (e: Exception) {}

        try {
            findViewById<TextView>(R.id.tv_mode_desc).text = when {
                running && theft -> "Full Theft Mode — All alerts active"
                running          -> "Your Phone is Currently Protected"
                else             -> "Tap to Activate Protection"
            }
        } catch (e: Exception) {}

        try {
            findViewById<TextView>(R.id.tv_activity_log).text = when {
                !running -> "System idle — tap ARM to protect this phone"
                theft    -> "Full protection active\n${lastAlert ?: "Monitoring all events"}"
                else     -> "Normal mode — ${lastAlert ?: "Monitoring password attempts"}"
            }
        } catch (e: Exception) {}

        try {
            findViewById<TextView>(R.id.tv_alert_count).text =
                prefs.getInt("total_alerts", 0).toString()
        } catch (e: Exception) {}
        try {
            findViewById<TextView>(R.id.tv_contact_log).text =
                (prefs.getString("phone", null) ?: s("not_set"))
        } catch (e: Exception) {}
        try {
            val adminTv = findViewById<TextView>(R.id.tv_admin_log)
            adminTv.text = if (adminActive) "Active" else "Inactive"
            adminTv.setTextColor(if (adminActive) 0xFF00AA44.toInt() else 0xFFCC0000.toInt())
        } catch (e: Exception) {}
        try {
            if (running && armTime > 0) {
                val mins = (System.currentTimeMillis() - armTime) / 60000
                val hrs  = mins / 60
                findViewById<TextView>(R.id.tv_uptime).text =
                    if (hrs > 0) "Armed ${hrs}h ${mins % 60}m" else "Armed ${mins}m"
            } else {
                try { findViewById<TextView>(R.id.tv_uptime).text = "" } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        try {
            val theftBtn = findViewById<Button>(R.id.btn_theft_mode)
            when {
                !isPremium -> {
                    theftBtn.text = "Theft Mode — Premium Only"
                    theftBtn.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFF111111.toInt())
                    theftBtn.setTextColor(0xFF444444.toInt())
                }
                !running -> {
                    theftBtn.text = "THEFT MODE (arm first)"
                    theftBtn.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFF111111.toInt())
                    theftBtn.setTextColor(0xFF333333.toInt())
                }
                theft -> {
                    theftBtn.text = "THEFT MODE: ON — Tap to deactivate"
                    theftBtn.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFFCC0000.toInt())
                    theftBtn.setTextColor(0xFFFFFFFF.toInt())
                }
                else -> {
                    theftBtn.text = "ACTIVATE THEFT MODE"
                    theftBtn.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFF2A1A00.toInt())
                    theftBtn.setTextColor(0xFFFFAA00.toInt())
                }
            }
        } catch (e: Exception) {}
        try {
            findViewById<Button>(R.id.btn_arm).text = if (running) s("disarm") else s("arm")
        } catch (e: Exception) {}
        try {
            val premiumBtn = findViewById<Button>(R.id.btn_premium)
            premiumBtn.visibility =
                if (isPremium) android.view.View.GONE else android.view.View.VISIBLE
        } catch (e: Exception) {}
    }

    // ── Survival timer picker ──────────────────────────────────

    private fun showSurvivalTimerPicker(statusTv: TextView? = null) {
        val isPremium = LicenseManager.isPremium(this)
        val allLabels = arrayOf(
            "5 min","10 min","15 min","20 min","30 min",
            "40 min ⭐","45 min ⭐","50 min ⭐","55 min ⭐",
            "1 hr ⭐","2 hr ⭐","3 hr ⭐","4 hr ⭐","6 hr ⭐","12 hr ⭐","24 hr ⭐"
        )
        val allMs = longArrayOf(5,10,15,20,30,40,45,50,55,60,120,180,240,360,720,1440)
            .map { it * 60_000L }.toLongArray()
        val lv = ListView(this)
        val adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_list_item_1, allLabels) {
            override fun getView(pos: Int, cv: android.view.View?,
                                 parent: android.view.ViewGroup): android.view.View {
                val v  = super.getView(pos, cv, parent)
                val tv = v as TextView
                val isPrem = pos >= 5
                tv.isEnabled = !isPrem || isPremium
                tv.setTextColor(when {
                    isPrem && !isPremium -> 0xFF555555.toInt()
                    isPrem               -> 0xFFFFD700.toInt()
                    else                 -> 0xFFFFFFFF.toInt()
                })
                return v
            }
        }
        lv.adapter = adapter
        lv.setBackgroundColor(0xFF0D0D10.toInt())
        var dialog: AlertDialog? = null
        lv.setOnItemClickListener { _, _, which, _ ->
            if (which >= 5 && !isPremium) {
                Toast.makeText(this, "⭐ Premium feature", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            val dur   = allMs[which]
            val label = allLabels[which].replace(" ⭐", "")
            prefs.edit().putLong("survival_duration", dur)
                .putBoolean("survival_timer_on", true).apply()
            SurvivalTimerService.start(this, dur)
            statusTv?.text = "Set to $label"
            try {
                findViewById<Switch>(R.id.sw_survival_timer)?.isChecked = true
                findViewById<android.view.View>(R.id.survival_options_panel)
                    ?.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {}
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Set Survival Timer Duration")
            .setView(lv)
            .setNegativeButton("Cancel") { _, _ ->
                if (!prefs.getBoolean("survival_timer_on", false)) {
                    try {
                        findViewById<Switch>(R.id.sw_survival_timer)?.isChecked = false
                        findViewById<android.view.View>(R.id.survival_options_panel)
                            ?.visibility = android.view.View.GONE
                    } catch (e: Exception) {}
                }
            }.create()
        dialog.show()
    }

    // ── Recipient dialog ───────────────────────────────────────

    private fun showRecipientDialog() {
        val isPremium = LicenseManager.isPremium(this)
        val saved     = prefs.getString("survival_recipient", "") ?: ""
        val useCustom = saved.isNotBlank() && isPremium

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8)
        }
        val rb1 = RadioButton(this).apply {
            text = "Default Emergency Contact  (Free)"
            isChecked = !useCustom; setTextColor(0xFFFFFFFF.toInt())
        }
        val rb2 = RadioButton(this).apply {
            text = "Custom Number  ⭐ Premium"
            isChecked = useCustom && isPremium
            setTextColor(if (isPremium) 0xFFFFD700.toInt() else 0xFF555555.toInt())
        }
        val etCustom = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "+213 XXX XXX XXX"
            setText(if (isPremium) saved else "")
            isEnabled = isPremium; setPadding(16, 8, 16, 8)
            visibility = if (useCustom && isPremium) android.view.View.VISIBLE
            else android.view.View.GONE
        }
        rb1.setOnClickListener { etCustom.visibility = android.view.View.GONE }
        rb2.setOnClickListener {
            if (!isPremium) {
                rb2.isChecked = false; rb1.isChecked = true
                Toast.makeText(this, "⭐ Premium required", Toast.LENGTH_SHORT).show()
            } else {
                etCustom.visibility = android.view.View.VISIBLE; etCustom.requestFocus()
            }
        }
        etCustom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isPremium && rb2.isChecked)
                    prefs.edit().putString("survival_recipient",
                        s?.toString()?.trim() ?: "").apply()
            }
        })
        ll.addView(rb1); ll.addView(rb2); ll.addView(etCustom)
        AlertDialog.Builder(this).setTitle("Emergency Recipient").setView(ll)
            .setPositiveButton("Save") { _, _ ->
                if (isPremium && rb2.isChecked)
                    prefs.edit().putString("survival_recipient",
                        etCustom.text.toString().trim()).apply()
                else if (!rb2.isChecked)
                    prefs.edit().putString("survival_recipient", "").apply()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSurvivalInfo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.survival_info_title))
            .setMessage(getString(R.string.survival_info_body))
            .setPositiveButton("OK", null).show()
    }

    // ── Haptic ─────────────────────────────────────────────────

    private fun hapticFeedback(durationMs: Long = 40) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createOneShot(
                        durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createOneShot(durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(durationMs)
            }
        } catch (e: Exception) {}
    }
}
