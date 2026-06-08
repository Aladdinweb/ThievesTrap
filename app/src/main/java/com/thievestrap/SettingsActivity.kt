package com.thievestrap
// BUILD v11.1.3 - verified clean structure

import android.content.*
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var isPremium = false
    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            isPremium = LicenseManager.isPremium(this@SettingsActivity)
            loadAll()
        }
    }

    override fun onResume() {
        super.onResume()
        isPremium = LicenseManager.isPremium(this)
        loadAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(refreshReceiver) } catch (e: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        isPremium = LicenseManager.isPremium(this)
        registerReceiver(refreshReceiver,
            android.content.IntentFilter("com.thievestrap.SETTINGS_REFRESH"))
        loadAll()
        setupListeners()
    }

    private fun getAllTextViews(view: android.view.View): List<android.widget.TextView> {
        val result = mutableListOf<android.widget.TextView>()
        if (view is android.widget.TextView) result.add(view)
        if (view is android.view.ViewGroup) (0 until view.childCount).forEach { result.addAll(getAllTextViews(view.getChildAt(it))) }
        return result
    }
    private fun s(key: String) = Strings.get(this, key)

    private fun showUpgradeDialog(feature: String) {
        AlertDialog.Builder(this)
            .setTitle("Premium Required")
            .setMessage("$feature is available in the Full Protection plan.")
            .setPositiveButton("Upgrade Now") { _, _ ->
                startActivity(Intent(this@SettingsActivity, PremiumActivity::class.java))
            }
            .setNegativeButton("Not now", null).show()
    }

    private fun loadAll() {
        // ── IDENTITY ──
        findViewById<EditText>(R.id.et_imei).setText(prefs.getString("imei", ""))

        // ── CONTACTS ──
        // Contact 1 — always free
        findViewById<EditText>(R.id.et_phone).setText(prefs.getString("phone", ""))

        // Contact 2 — premium lock (lock icon in hint when locked)
        val et2 = findViewById<EditText>(R.id.et_phone2)
        if (isPremium) {
            et2.setText(prefs.getString("phone2", ""))
            et2.hint = s("contact2")
            et2.isEnabled = true
            et2.alpha = 1f
        } else {
            et2.setText("")
            et2.hint = s("contact2_locked")
            et2.isEnabled = false
            et2.alpha = 0.4f
        }

        // Email feature removed — using Firebase SMS-Link system

        // ── THEFT ALERTS ──
        // Selfie — PREMIUM only (Fix 4)
        val swSelfie = findViewById<Switch>(R.id.sw_selfie)
        swSelfie.isChecked = isPremium && prefs.getBoolean("selfie_enabled", false)
        swSelfie.isEnabled = isPremium
        swSelfie.alpha = if (isPremium) 1f else 0.4f
        // Failed attempts — free
        findViewById<Switch>(R.id.sw_failed).isChecked = prefs.getBoolean("alert_failed", true)
        // SIM change — premium
        val swSim = findViewById<Switch>(R.id.sw_sim)
        swSim.isChecked = isPremium && prefs.getBoolean("alert_sim", true)
        swSim.isEnabled = isPremium
        swSim.alpha = if (isPremium) 1f else 0.4f

        // ── SMART GRACE PERIOD ──
        // Toggle — available to all
        val swGrace = findViewById<Switch>(R.id.sw_grace_enabled)
        swGrace.isChecked = prefs.getBoolean("grace_enabled", false)

        // Slider — available to all (no lock, no fixed 15s)
        val seekBar = findViewById<SeekBar>(R.id.seekbar_grace)
        val savedGrace = prefs.getInt("grace_period", 10)
        seekBar.progress = (savedGrace - 5).coerceIn(0, 55) // 5-60 seconds
        seekBar.isEnabled = swGrace.isChecked
        seekBar.alpha = if (swGrace.isChecked) 1f else 0.4f
        updateGraceLabel(savedGrace, swGrace.isChecked)

        // ── DEVICE STATUS ALERTS — premium + theft mode dependency ──
        val theftActive = prefs.getBoolean("theft_mode", false)
        listOf(
            R.id.sw_airplane to "alert_airplane",
            R.id.sw_silent to "alert_silent"
        ).forEach { (id, key) ->
            val sw = try { findViewById<Switch>(id) } catch (e: Exception) { return@forEach }
            sw.isChecked = isPremium && theftActive && prefs.getBoolean(key, true)
            sw.isEnabled = isPremium
            sw.alpha = if (isPremium) 1f else 0.4f
        }

        // ── REMOTE CONTROL — premium (ping is remote-only, no UI switch) ──

        // ── FAILED THRESHOLD ──
        val spinnerFailed = findViewById<Spinner>(R.id.spinner_failed)
        spinnerFailed.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("None", "1", "2", "3", "4", "5"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerFailed.setSelection(when(prefs.getInt("failed_threshold", 3)){-1->0;1->1;2->2;4->4;5->5;else->3})

        // ── SOS NUMBER ──
        // SOS info icon

        // Fix 1+4: Telegram — hard-locked for free users
        try {
            val etTg = findViewById<android.widget.EditText>(R.id.et_telegram_ids)
            val btnBot = findViewById<Button>(R.id.btn_setup_bot)
            val badge = try { findViewById<android.widget.TextView>(R.id.tv_telegram_premium_badge) } catch (e: Exception) { null }
            if (isPremium) {
                // Unlock
                etTg.isFocusable = true
                etTg.isFocusableInTouchMode = true
                etTg.isEnabled = true
                etTg.alpha = 1f
                etTg.setText(prefs.getString("telegram_chat_ids", ""))
                etTg.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) prefs.edit().putString("telegram_chat_ids", etTg.text.toString().trim()).apply()
                }
                etTg.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        prefs.edit().putString("telegram_chat_ids", s?.toString()?.trim() ?: "").apply()
                    }
                })
                btnBot.text = "Setup Bot / Get Chat ID"
                btnBot.setTextColor(android.graphics.Color.parseColor("#4A90D9"))
                btnBot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#1A1A1A"))
                btnBot.alpha = 1f
                btnBot.isEnabled = true
                btnBot.setOnClickListener {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/ThievesTrap_Alert_bot")))
                }
                badge?.visibility = android.view.View.GONE
            } else {
                // Hard lock — nothing works
                etTg.isFocusable = false
                etTg.isEnabled = false
                etTg.alpha = 0.35f
                etTg.hint = "Premium required"
                btnBot.alpha = 0.35f
                btnBot.isEnabled = false
                btnBot.setOnClickListener { showUpgradeDialog("Telegram Alerts") }
                badge?.visibility = android.view.View.VISIBLE
            }
        } catch (e: Exception) {}

        // ── APP INFO ──
        val label = if (isPremium) s("premium_active") else s("free_version")
        // No lock icons or price shown when premium
        // Premium: UI is clean, no lock icons shown
        findViewById<TextView>(R.id.tv_version).text = "${s("app_name")} v${BuildConfig.VERSION_NAME} — $label"
        findViewById<TextView>(R.id.tv_total_alerts).text = "${s("total_alerts")}: ${prefs.getInt("total_alerts", 0)}"
        findViewById<TextView>(R.id.tv_last_alert_info).text =
            "${s("last_alert")}: ${prefs.getString("last_alert", s("never")) ?: s("never")}"
    }

    private fun updateGraceLabel(seconds: Int, enabled: Boolean) {
        val tv = findViewById<TextView>(R.id.tv_grace_value)
        tv.text = if (enabled) "${s("grace_toggle_label")}: $seconds s"
                  else s("grace_off_label")
        tv.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
    }

    private fun setupListeners() {
        // Save identity
        findViewById<Button>(R.id.btn_save_identity).setOnClickListener {
            prefs.edit()
                .putString("imei", findViewById<EditText>(R.id.et_imei).text.toString().trim())
                .apply()
            Toast.makeText(this, s("saved"), Toast.LENGTH_SHORT).show()
        }
        // IMEI hint
        findViewById<TextView>(R.id.tv_imei_hint).setOnClickListener {
            Toast.makeText(this, s("imei_hint"), Toast.LENGTH_LONG).show()
        }
        // Save contacts
        findViewById<Button>(R.id.btn_save_contacts).setOnClickListener {
            prefs.edit().putString("phone", findViewById<EditText>(R.id.et_phone).text.toString().trim()).apply()
            if (isPremium) {
                prefs.edit()
                    .putString("phone2", findViewById<EditText>(R.id.et_phone2).text.toString().trim())
                    .apply()
            }
            Toast.makeText(this, s("saved"), Toast.LENGTH_SHORT).show()
        }
        // Contact 2 click when locked
        if (!isPremium) {
            findViewById<EditText>(R.id.et_phone2).setOnClickListener { showUpgradeDialog("Emergency Contact 2") }
        }

        // Selfie toggle — premium only
        findViewById<Switch>(R.id.sw_selfie).setOnClickListener {
            if (!isPremium) {
                showUpgradeDialog("Intruder Selfie Capture")
                (it as Switch).isChecked = false
            }
        }
        findViewById<Switch>(R.id.sw_selfie).setOnCheckedChangeListener { _, c ->
            if (isPremium) prefs.edit().putBoolean("selfie_enabled", c).apply()
        }
        // Failed toggle
        findViewById<Switch>(R.id.sw_failed).setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("alert_failed", c).apply()
        }
        // SIM — premium
        val swSim = findViewById<Switch>(R.id.sw_sim)
        swSim.setOnClickListener { if (!isPremium) { showUpgradeDialog("SMS on SIM Change"); swSim.isChecked = false } }
        swSim.setOnCheckedChangeListener { _, c -> if (isPremium) prefs.edit().putBoolean("alert_sim", c).apply() }

        // ── Grace Period Toggle — all users ──
        val swGrace = findViewById<Switch>(R.id.sw_grace_enabled)
        val seekBar = findViewById<SeekBar>(R.id.seekbar_grace)
        swGrace.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("grace_enabled", enabled).apply()
            seekBar.isEnabled = enabled
            seekBar.alpha = if (enabled) 1f else 0.4f
            updateGraceLabel(prefs.getInt("grace_period", 10), enabled)
        }
        // Grace seekbar — editable for all
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val secs = p + 5
                updateGraceLabel(secs, prefs.getBoolean("grace_enabled", false))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val secs = sb.progress + 5
                prefs.edit().putInt("grace_period", secs).apply()
                // Haptic feedback
                try {
                    val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        v.vibrate(android.os.VibrationEffect.createOneShot(25, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") v.vibrate(25)
                } catch (e: Exception) {}
            }
        })

        // Device status alerts — premium
        listOf(
            R.id.sw_airplane to "alert_airplane",
            R.id.sw_silent to "alert_silent"
        ).forEach { (id, key) ->
            val sw = findViewById<Switch>(id)
            sw.setOnClickListener { if (!isPremium) { showUpgradeDialog("Device Alerts"); sw.isChecked = false } }
            sw.setOnCheckedChangeListener { _, c -> if (isPremium) prefs.edit().putBoolean(key, c).apply() }
        }

        // Auto location ping — remote-only (no switch)

        // Failed threshold
        findViewById<Spinner>(R.id.spinner_failed).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    prefs.edit().putInt("failed_threshold", when(pos){0->-1;1->1;2->2;3->3;4->4;5->5;else->3}).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // Language button removed — moved to sidebar nav drawer

        // Fix 5: Notification switch — immediately updates service
        try {
            val swNotif = findViewById<Switch>(R.id.sw_notif_enabled)
            swNotif.isChecked = prefs.getBoolean("notif_enabled", true)
            swNotif.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("notif_enabled", checked).apply()
                // Tell service to update its notification state right now
                startService(android.content.Intent(this, MonitorService::class.java)
                    .apply { action = "UPDATE_NOTIF" })
            }
        } catch (e: Exception) {}


        // Test SMS
        findViewById<Button>(R.id.btn_test_sms).setOnClickListener {
            if (prefs.getString("phone", "").isNullOrBlank())
                Toast.makeText(this, s("no_contact"), Toast.LENGTH_LONG).show()
            else {
                startService(Intent(this, MonitorService::class.java).apply { action = "TEST" })
                Toast.makeText(this, s("test_sent"), Toast.LENGTH_SHORT).show()
            }
        }

        // Reset stats
        findViewById<Button>(R.id.btn_reset_stats).setOnClickListener {
            prefs.edit().putInt("total_alerts", 0).putString("last_alert", s("never")).apply()
            loadAll()
            Toast.makeText(this, s("reset_done"), Toast.LENGTH_SHORT).show()
        }

        // Back
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun showChangePinDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }
        val etOld = EditText(this).apply {
            hint = s("current_pin")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val etNew = EditText(this).apply {
            hint = s("new_pin")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        layout.addView(etOld); layout.addView(etNew)
        AlertDialog.Builder(this).setTitle(s("change_pin")).setView(layout)
            .setPositiveButton(s("change")) { _, _ ->
                val old = etOld.text.toString().trim()
                val new = etNew.text.toString().trim()
                if (old != (prefs.getString("password","") ?: ""))
                    Toast.makeText(this, s("wrong_pin"), Toast.LENGTH_LONG).show()
                else if (new.length < 4)
                    Toast.makeText(this, s("pin_too_short"), Toast.LENGTH_LONG).show()
                else {
                    prefs.edit().putString("password", new).apply()
                    Toast.makeText(this, s("pin_changed"), Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(s("cancel"), null).show()
    }
}
