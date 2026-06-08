package com.thievestrap

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

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

    private var settingsRefreshReceiver: android.content.BroadcastReceiver? = null

    override fun onResume() {
        super.onResume()
        // Sync survival timer switch with prefs
        try {
            val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
            val sw = findViewById<android.widget.Switch>(R.id.sw_survival_timer)
            val on = prefs.getBoolean("survival_timer_on", false)
            sw?.isChecked = on
            try { findViewById<android.view.View>(R.id.survival_options_panel)
                ?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            } catch(e:Exception){}
        } catch(e:Exception){}
        // Fix 1: Listen for SETTINGS_REFRESH to sync switch after "I'm Safe"
        settingsRefreshReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, i: android.content.Intent) {
                val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
                val on = prefs.getBoolean("survival_timer_on", false)
                try {
                    val sw = findViewById<android.widget.Switch>(R.id.sw_survival_timer)
                    sw?.isChecked = on
                    val tv = findViewById<android.widget.TextView>(R.id.tv_survival_status)
                    tv?.text = if (on) "Active" else "Off"
                    findViewById<android.view.View>(R.id.survival_options_panel)
                        ?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
                } catch(e:Exception){}
            }
        }
        registerReceiver(settingsRefreshReceiver,
            android.content.IntentFilter("com.thievestrap.SETTINGS_REFRESH"))
        // Post to next frame so UI renders first, then we update state
        window.decorView.post { updateStatus() }
    }
    override fun onDestroy() { super.onDestroy(); mediaPlayer?.release() }

    private fun s(key: String) = Strings.get(this, key)

    private fun setupButtons() {
        // About icon (top left) — slides in from left to right
        try {
            findViewById<android.widget.ImageView>(R.id.btn_about).setOnClickListener {
                startActivity(Intent(this, AboutActivity::class.java))
                overridePendingTransition(
                    R.anim.slide_in_right,   // new screen slides in from right
                    R.anim.slide_out_left    // current screen slides out to left
                )
            }
        } catch (e: Exception) {}

        // ≡ Menu icon opens right-to-left navigation drawer
        try {
            findViewById<TextView>(R.id.btn_menu).setOnClickListener {
                hapticFeedback(20)
                drawerLayout.openDrawer(android.view.Gravity.END)
            }
        } catch (e: Exception) {}

        // Nav drawer item handlers
        try {
            fun close() = drawerLayout.closeDrawer(android.view.Gravity.END)
            findViewById<android.view.View>(R.id.nav_settings).setOnClickListener {
                close(); startActivity(Intent(this, SettingsActivity::class.java)) }
            findViewById<android.view.View>(R.id.nav_premium).setOnClickListener {
                close(); startActivity(Intent(this, PremiumActivity::class.java)) }
            findViewById<android.view.View>(R.id.nav_change_pin).setOnClickListener {
                close(); showPinChangeDialog() }
            try { findViewById<android.view.View>(R.id.nav_language)
                .setOnClickListener { close(); showLanguageDialog() } } catch(e:Exception){}
            // Survival Timer
            val tvSurvivalStatus = try { findViewById<android.widget.TextView>(R.id.tv_survival_status) } catch(e:Exception){null}
            val swSurvival = try { findViewById<android.widget.Switch>(R.id.sw_survival_timer) } catch(e:Exception){null}
            val optionsPanel = try { findViewById<android.view.View>(R.id.survival_options_panel) } catch(e:Exception){null}
            val survivalOn = prefs.getBoolean("survival_timer_on", false)
            swSurvival?.isChecked = survivalOn
            optionsPanel?.visibility = if (survivalOn) android.view.View.VISIBLE else android.view.View.GONE
            val savedDurMs = prefs.getLong("survival_duration", 0L)
            tvSurvivalStatus?.text = when {
                survivalOn && savedDurMs > 0 -> "Set to ${savedDurMs/60000} min"
                survivalOn -> "Active"
                else -> "Off"
            }
            swSurvival?.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    optionsPanel?.visibility = android.view.View.VISIBLE
                    showSurvivalTimerPicker(tvSurvivalStatus)
                } else {
                    // Fix 2&3: No PIN required for local stop — instant UI update
                    prefs.edit().putBoolean("survival_timer_on", false).apply()
                    SurvivalTimerService.stop(this)
                    tvSurvivalStatus?.text = "Off"
                    optionsPanel?.visibility = android.view.View.GONE
                    swSurvival.isChecked = false  // Fix 2: immediate visual update
                }
            }
            // Info icon
            try { findViewById<android.widget.TextView>(R.id.nav_survival_info)
                .setOnClickListener { showSurvivalInfo() } } catch(e:Exception){}
            // Set Duration button
            try { findViewById<android.view.View>(R.id.nav_survival_duration)
                .setOnClickListener { showSurvivalTimerPicker(tvSurvivalStatus) } } catch(e:Exception){}
            // Emergency Recipient
            try { findViewById<android.view.View>(R.id.nav_survival_recipient)
                .setOnClickListener { close(); showRecipientDialog() } } catch(e:Exception){}
            // Fingerprint toggle in drawer
            val swBioDrawer = findViewById<android.widget.Switch>(R.id.sw_biometric_drawer)
            swBioDrawer.isChecked = prefs.getBoolean("biometric_unlock", false)
            swBioDrawer.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("biometric_unlock", checked).apply()
            }
            findViewById<android.view.View>(R.id.nav_fingerprint).setOnClickListener {
                swBioDrawer.isChecked = !swBioDrawer.isChecked
            }
        } catch (e: Exception) {}

        // ARM / DISARM
        findViewById<Button>(R.id.btn_arm).setOnClickListener {
            hapticFeedback()
            if (prefs.getBoolean("running", false)) showDisarmDialog()
            else {
                checkPermissionsAndStart()  // No popup — removed volume/accessibility dialog
            }
        }

        // Theft mode
        try {
            findViewById<Button>(R.id.btn_theft_mode).setOnClickListener {
                hapticFeedback()
                if (!prefs.getBoolean("running", false)) {
                    Toast.makeText(this, "Arm the app first!", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                if (!LicenseManager.isPremium(this)) { showUpgradeDialog("Theft Mode"); return@setOnClickListener }
                val on = prefs.getBoolean("theft_mode", false)
                prefs.edit().putBoolean("theft_mode", !on).apply()
                // Task 4: When theft turned OFF, sync premium features OFF
                if (on) {
                    prefs.edit().putBoolean("location_ping", false).apply()
                }
                startService(Intent(this, MonitorService::class.java).apply {
                    action = if (!on) "THEFT_ON" else "THEFT_OFF"
                })
                updateStatus()
            }
        } catch (e: Exception) {}


        // Gallery (bottom left)
        try {
            findViewById<android.view.View>(R.id.btn_selfies).setOnClickListener {
                hapticFeedback()
                startActivity(Intent(this, SelfiesActivity::class.java))
            }
        } catch (e: Exception) {}

        // Remote guide icon (bottom right — replaces SOS)
        try {
            findViewById<android.view.View>(R.id.btn_remote_guide_icon).setOnClickListener {
                hapticFeedback()
                startActivity(Intent(this, RemoteGuideActivity::class.java))
            }
        } catch (e: Exception) {}



        // Premium
        try {
            findViewById<Button>(R.id.btn_premium).setOnClickListener {
                startActivity(Intent(this, PremiumActivity::class.java))
            }
        } catch (e: Exception) {}
    }

    // Fix 3: SOS sends to custom sos_number from settings
    // Task 5: Instant SOS — no dialog, fires immediately
    private fun triggerSOS() {
        val target = prefs.getString("sos_number", "").orEmpty()
            .ifBlank { prefs.getString("phone", "").orEmpty() }
        if (target.isBlank()) {
            Toast.makeText(this, "Set an SOS number in Settings first!", Toast.LENGTH_LONG).show()
            return
        }
        hapticFeedback(200)
        startService(Intent(this, MonitorService::class.java).apply {
            action = "TAMPER"
            putExtra("reason", "SOS EMERGENCY ALERT - I need urgent help! Battery/location info will be added.")
            putExtra("sos_target", target)
        })
        Toast.makeText(this, "SOS sent to $target", Toast.LENGTH_SHORT).show()
    }


    private fun showUpgradeDialog(feature: String) {
        AlertDialog.Builder(this)
            .setTitle("Premium Required")
            .setMessage("$feature is available in the Full Protection plan.")
            .setPositiveButton("Upgrade Now") { _, _ -> startActivity(Intent(this, PremiumActivity::class.java)) }
            .setNegativeButton("Not now", null).show()
    }

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
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { stopMonitoring() }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // Task 6: ANY error → immediate PIN fallback
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

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.CAMERA
        ).apply { if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
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

    private fun showPinChangeDialog() {
        val oldInput = android.widget.EditText(this).apply {
            hint = "Current PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val newInput = android.widget.EditText(this).apply {
            hint = "New PIN (4-6 digits)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(oldInput); addView(newInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change Security PIN")
            .setView(container)
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
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun hapticFeedback(durationMs: Long = 40) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(durationMs)
            }
        } catch (e: Exception) {}
    }

    private fun updateStatus() {
        val running = prefs.getBoolean("running", false)
        val theft = prefs.getBoolean("theft_mode", false)
        val isPremium = LicenseManager.isPremium(this)
        val adminActive = dpm.isAdminActive(adminComponent)
        val armTime = prefs.getLong("arm_time", 0)
        val lastAlert = prefs.getString("last_alert", null)

        // Task 3: RED=Unprotected, ORANGE=Armed(standby), GREEN=Fully Protected(theft ON)
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

        // Fix 2: Sub-status label (Medium Protection / Full Protection)
        try {
            val sub = findViewById<TextView>(R.id.tv_sub_status)
            when {
                running && theft -> { sub.text = "Full Protection"; sub.setTextColor(0xFF00FF66.toInt()) }
                running          -> { sub.text = "Medium Protection"; sub.setTextColor(0xFFFF8C00.toInt()) }
                else             -> sub.text = ""
            }
        } catch (e: Exception) {}

        // Dynamic mode description
        try {
            val desc = when {
                running && theft -> "Full Theft Mode — All alerts active"
                running          -> "Your Phone is Currently Protected"
                else             -> "Tap to Activate Protection"
            }
            findViewById<TextView>(R.id.tv_mode_desc).text = desc
        } catch (e: Exception) {}

        // Fix 1: Live activity log update
        try {
            val logText = when {
                !running -> "System idle — tap ARM to protect this phone"
                theft    -> "Full protection active\n${lastAlert ?: "Monitoring all events"}"
                else     -> "Normal mode — ${lastAlert ?: "Monitoring password attempts"}"
            }
            findViewById<TextView>(R.id.tv_activity_log).text = logText
        } catch (e: Exception) {}

        try { findViewById<TextView>(R.id.tv_alert_count).text =
            prefs.getInt("total_alerts", 0).toString() } catch (e: Exception) {}

        try { findViewById<TextView>(R.id.tv_contact_log).text =
            (prefs.getString("phone", null) ?: s("not_set")) } catch (e: Exception) {}

        try {
            val adminTv = findViewById<TextView>(R.id.tv_admin_log)
            adminTv.text = if (adminActive) "Active" else "Inactive"
            adminTv.setTextColor(if (adminActive) 0xFF00AA44.toInt() else 0xFFCC0000.toInt())
        } catch (e: Exception) {}

        // Uptime
        try {
            if (running && armTime > 0) {
                val mins = (System.currentTimeMillis() - armTime) / 60000
                val hrs = mins / 60
                findViewById<TextView>(R.id.tv_uptime).text =
                    if (hrs > 0) "Armed ${hrs}h ${mins % 60}m" else "Armed ${mins}m"
            } else {
                try { findViewById<TextView>(R.id.tv_uptime).text = "" } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        // Theft mode button
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
            premiumBtn.visibility = if (isPremium) android.view.View.GONE else android.view.View.VISIBLE
        } catch (e: Exception) {}
    }
    private fun showSurvivalTimerPicker(statusTv: android.widget.TextView? = null) {
        val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val isPremium = LicenseManager.isPremium(this)
        // All durations — premium ones marked with star
        val allLabels = arrayOf(
            "5 min","10 min","15 min","20 min","30 min",
            "40 min ⭐","45 min ⭐","50 min ⭐","55 min ⭐",
            "1 hr ⭐","2 hr ⭐","3 hr ⭐","4 hr ⭐","6 hr ⭐","12 hr ⭐","24 hr ⭐"
        )
        val allMs = longArrayOf(5,10,15,20,30,40,45,50,55,60,120,180,240,360,720,1440)
            .map{it*60_000L}.toLongArray()
        // Build scrollable list
        val lv = android.widget.ListView(this)
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, allLabels) {
            override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent)
                val tv = v as android.widget.TextView
                val isPrem = pos >= 5
                tv.isEnabled = !isPrem || isPremium
                tv.setTextColor(when {
                    isPrem && !isPremium -> 0xFF555555.toInt()
                    isPrem -> 0xFFFFD700.toInt()
                    else -> 0xFFFFFFFF.toInt()
                })
                return v
            }
        }
        lv.adapter = adapter
        lv.setBackgroundColor(0xFF0D0D10.toInt())
        var dialog: androidx.appcompat.app.AlertDialog? = null
        lv.setOnItemClickListener { _, _, which, _ ->
            val isPrem = which >= 5
            if (isPrem && !isPremium) {
                android.widget.Toast.makeText(this, "⭐ Premium feature", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            val dur = allMs[which]
            val label = allLabels[which].replace(" ⭐","")
            prefs.edit().putLong("survival_duration", dur)
                .putBoolean("survival_timer_on", true).apply()
            SurvivalTimerService.start(this, dur)
            // Fix 5: Immediate subtitle update
            statusTv?.text = "Set to $label"
            try {
                val sw = findViewById<android.widget.Switch>(R.id.sw_survival_timer)
                sw?.isChecked = true
                findViewById<android.view.View>(R.id.survival_options_panel)?.visibility = android.view.View.VISIBLE
            } catch(e:Exception){}
            dialog?.dismiss()
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Survival Timer Duration")
            .setView(lv)
            .setNegativeButton("Cancel") { _, _ ->
                if (!prefs.getBoolean("survival_timer_on", false)) {
                    try {
                        findViewById<android.widget.Switch>(R.id.sw_survival_timer)?.isChecked = false
                        findViewById<android.view.View>(R.id.survival_options_panel)?.visibility = android.view.View.GONE
                    } catch(e:Exception){}
                }
            }.create()
        dialog.show()
    }

    private fun showPinForSurvivalStop(onSuccess: () -> Unit) {
        val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter Security PIN"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm PIN to Stop Timer")
            .setView(et)
            .setPositiveButton("Confirm") { _, _ ->
                if (et.text.toString() == prefs.getString("password","")) onSuccess()
                else android.widget.Toast.makeText(this,"Incorrect PIN",android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showRecipientDialog() {
        val prefs      = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val isPremium  = LicenseManager.isPremium(this)
        val saved      = prefs.getString("survival_recipient", "") ?: ""
        val useCustom  = saved.isNotBlank() && isPremium

        val ll = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val rb1 = android.widget.RadioButton(this).apply {
            text = "Default Emergency Contact  (Free)"
            isChecked = !useCustom
            setTextColor(0xFFFFFFFF.toInt())
        }
        val rb2 = android.widget.RadioButton(this).apply {
            text = "Custom Number  ⭐ Premium"
            isChecked = useCustom && isPremium
            setTextColor(if (isPremium) 0xFFFFD700.toInt() else 0xFF555555.toInt())
        }
        // Custom input — appears inline under rb2
        val etCustom = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "+213 XXX XXX XXX"
            setText(if (isPremium) saved else "")
            isEnabled = isPremium
            setPadding(16, 8, 16, 8)
            visibility = if (useCustom && isPremium) android.view.View.VISIBLE else android.view.View.GONE
        }
        rb1.setOnClickListener {
            etCustom.visibility = android.view.View.GONE
        }
        rb2.setOnClickListener {
            if (!isPremium) {
                rb2.isChecked = false; rb1.isChecked = true
                android.widget.Toast.makeText(this, "⭐ Premium required", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                etCustom.visibility = android.view.View.VISIBLE
                etCustom.requestFocus()
            }
        }
        // Auto-save as user types
        etCustom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isPremium && rb2.isChecked)
                    prefs.edit().putString("survival_recipient", s?.toString()?.trim() ?: "").apply()
            }
        })
        ll.addView(rb1); ll.addView(rb2); ll.addView(etCustom)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Emergency Recipient")
            .setView(ll)
            .setPositiveButton("Save") { _, _ ->
                if (isPremium && rb2.isChecked)
                    prefs.edit().putString("survival_recipient", etCustom.text.toString().trim()).apply()
                else if (!rb2.isChecked)
                    prefs.edit().putString("survival_recipient", "").apply()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSurvivalInfo() {
        val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val lang  = prefs.getString("language","en") ?: "en"
        val title = getString(R.string.survival_info_title)
        val msg   = getString(R.string.survival_info_body)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun showLanguageDialog() {
        val gb = "\uD83C\uDDEC\uD83C\uDDE7"  // GB flag
        val fr = "\uD83C\uDDEB\uD83C\uDDF7"  // FR flag
        val dz = "\uD83C\uDDE9\uD83C\uDDFF"  // DZ flag
        val options = arrayOf("$gb  English", "$fr  Fran\u00e7ais", "$dz  \u0627\u0644\u0639\u0631\u0628\u064a\u0629")
        val codes  = arrayOf("en", "fr", "ar")
        val cur    = LocaleHelper.getLang(this)
        val sel    = codes.indexOf(cur).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("\uD83C\uDF10  Select Language")
            .setSingleChoiceItems(options, sel) { dlg, which ->
                getSharedPreferences("tt_prefs", MODE_PRIVATE)
                    .edit().putString("language", codes[which]).apply()
                LocaleHelper.applyLocale(this)
                dlg.dismiss()
                val i = packageManager.getLaunchIntentForPackage(packageName)
                    ?: Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                           Intent.FLAG_ACTIVITY_NEW_TASK or
                           Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i); finish()
            }
            .setNegativeButton("Cancel", null).show()
    }

}
