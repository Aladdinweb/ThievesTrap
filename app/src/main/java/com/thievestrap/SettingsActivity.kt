package com.thievestrap
// BUILD v11.2.0 - v2.7.5 fixes applied

import android.content.*
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var isPremium = false

    // FIX 5: Silent mode grace period countdown state
    private var silentGraceHandler: android.os.Handler? = null
    private var silentGraceRunnable: Runnable? = null
    private var silentGraceNotifId = 8001

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
        silentGraceHandler?.removeCallbacksAndMessages(null)
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
        if (view is android.view.ViewGroup) (0 until view.childCount).forEach {
            result.addAll(getAllTextViews(view.getChildAt(it)))
        }
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
        findViewById<EditText>(R.id.et_phone).setText(prefs.getString("phone", ""))

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

        // ── THEFT ALERTS ──
        val swSelfie = findViewById<Switch>(R.id.sw_selfie)
        swSelfie.isChecked = isPremium && prefs.getBoolean("selfie_enabled", false)
        swSelfie.isEnabled = isPremium
        swSelfie.alpha = if (isPremium) 1f else 0.4f

        findViewById<Switch>(R.id.sw_failed).isChecked = prefs.getBoolean("alert_failed", true)

        val swSim = findViewById<Switch>(R.id.sw_sim)
        swSim.isChecked = isPremium && prefs.getBoolean("alert_sim", true)
        swSim.isEnabled = isPremium
        swSim.alpha = if (isPremium) 1f else 0.4f

        // ── SMART GRACE PERIOD ──
        val swGrace = findViewById<Switch>(R.id.sw_grace_enabled)
        swGrace.isChecked = prefs.getBoolean("grace_enabled", false)

        val seekBar = findViewById<SeekBar>(R.id.seekbar_grace)
        val savedGrace = prefs.getInt("grace_period", 10)
        seekBar.progress = (savedGrace - 5).coerceIn(0, 55)
        seekBar.isEnabled = swGrace.isChecked
        seekBar.alpha = if (swGrace.isChecked) 1f else 0.4f
        updateGraceLabel(savedGrace, swGrace.isChecked)

        // ── DEVICE STATUS ALERTS — FIX 4: Airplane mode removed, only Silent remains ──
        val theftActive = prefs.getBoolean("theft_mode", false)
        val swSilent = try { findViewById<Switch>(R.id.sw_silent) } catch (e: Exception) { null }
        swSilent?.isChecked = isPremium && theftActive && prefs.getBoolean("alert_silent", true)
        swSilent?.isEnabled = isPremium
        swSilent?.alpha = if (isPremium) 1f else 0.4f

        // ── FAILED THRESHOLD ──
        val spinnerFailed = findViewById<Spinner>(R.id.spinner_failed)
        spinnerFailed.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("None", "1", "2", "3", "4", "5"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerFailed.setSelection(when (prefs.getInt("failed_threshold", 3)) {
            -1 -> 0; 1 -> 1; 2 -> 2; 4 -> 4; 5 -> 5; else -> 3
        })

        // ── TELEGRAM section ──
        try {
            val etTg = findViewById<android.widget.EditText>(R.id.et_telegram_ids)
            val btnBot = findViewById<Button>(R.id.btn_setup_bot)
            val btnIdBot = try { findViewById<Button>(R.id.btn_open_id_bot) } catch (e: Exception) { null }
            val btnShareApp = try { findViewById<Button>(R.id.btn_share_app_tg) } catch (e: Exception) { null }
            val btnShareBot = try { findViewById<Button>(R.id.btn_share_bot) } catch (e: Exception) { null }
            val badge = try {
                findViewById<android.widget.TextView>(R.id.tv_telegram_premium_badge)
            } catch (e: Exception) { null }

            // "Open Bot" button — opens @ThievesTrap_Alert_bot, shows start message with ID retrieval link
            btnIdBot?.setOnClickListener {
                val tgUri = android.net.Uri.parse("tg://resolve?domain=ThievesTrap_Alert_bot&start=getid")
                val tgIntent = Intent(Intent.ACTION_VIEW, tgUri)
                if (tgIntent.resolveActivity(packageManager) != null) {
                    startActivity(tgIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/ThievesTrap_Alert_bot?start=getid")))
                }
            }

            // "Share App Link" — shares app download link via any app
            btnShareApp?.setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
                    putExtra(Intent.EXTRA_TEXT,
                        "${getString(R.string.share_app_body)}\nhttps://play.google.com/store/apps/details?id=$packageName")
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
            }

            // "Share Bot Link" — shares the Telegram bot link with a contact
            btnShareBot?.setOnClickListener {
                val botShareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.tg_bot_share_text))
                }
                startActivity(Intent.createChooser(botShareIntent, getString(R.string.share_chooser_title)))
            }

            if (isPremium) {
                etTg.isFocusable = true
                etTg.isFocusableInTouchMode = true
                etTg.isEnabled = true
                etTg.alpha = 1f
                etTg.setText(prefs.getString("telegram_chat_ids", ""))
                etTg.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) prefs.edit()
                        .putString("telegram_chat_ids", etTg.text.toString().trim()).apply()
                }
                etTg.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        prefs.edit().putString("telegram_chat_ids", s?.toString()?.trim() ?: "").apply()
                    }
                })
                btnBot.text = getString(R.string.connect_bot)
                btnBot.setTextColor(android.graphics.Color.parseColor("#4A90D9"))
                btnBot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#1A1A2E"))
                btnBot.alpha = 1f
                btnBot.isEnabled = true
                btnBot.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/ThievesTrap_Alert_bot")))
                }
                btnIdBot?.alpha = 1f
                btnIdBot?.isEnabled = true
                btnShareApp?.alpha = 1f
                btnShareApp?.isEnabled = true
                btnShareBot?.alpha = 1f
                btnShareBot?.isEnabled = true
                badge?.visibility = android.view.View.GONE
            } else {
                etTg.isFocusable = false
                etTg.isEnabled = false
                etTg.alpha = 0.35f
                etTg.hint = "Premium required"
                btnBot.alpha = 0.35f
                btnBot.isEnabled = false
                btnBot.setOnClickListener { showUpgradeDialog("Telegram Alerts") }
                btnIdBot?.alpha = 0.35f
                btnIdBot?.isEnabled = false
                btnShareApp?.alpha = 0.6f
                btnShareApp?.isEnabled = true  // Share app link works for everyone
                btnShareBot?.alpha = 0.35f
                btnShareBot?.isEnabled = false
                badge?.visibility = android.view.View.VISIBLE
            }
        } catch (e: Exception) {}

        // ── APP INFO ──
        val label = if (isPremium) s("premium_active") else s("free_version")
        findViewById<TextView>(R.id.tv_version).text =
            "${s("app_name")} v${BuildConfig.VERSION_NAME} — $label"
        findViewById<TextView>(R.id.tv_total_alerts).text =
            "${s("total_alerts")}: ${prefs.getInt("total_alerts", 0)}"
        findViewById<TextView>(R.id.tv_last_alert_info).text =
            "${s("last_alert")}: ${prefs.getString("last_alert", s("never")) ?: s("never")}"
    }

    private fun updateGraceLabel(seconds: Int, enabled: Boolean) {
        val tv = findViewById<TextView>(R.id.tv_grace_value)
        tv.text = if (enabled) "${s("grace_toggle_label")}: $seconds s"
        else s("grace_off_label")
        tv.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
    }

    // ── FIX 5: Silent mode grace period — 1 minute countdown with Cancel action ──
    private fun startSilentGracePeriod() {
        val GRACE_MS = 60_000L
        val startMs = System.currentTimeMillis()
        val chanId = "tt_silent_grace"

        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                chanId, "Silent Mode Alert", android.app.NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(android.app.NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }

        // Cancel PendingIntent — sends broadcast to cancel grace
        val cancelIntent = Intent("com.thievestrap.CANCEL_SILENT_GRACE").apply {
            setPackage(packageName)
        }
        val cancelPi = android.app.PendingIntent.getBroadcast(
            this, silentGraceNotifId, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        fun buildGraceNotif(secsLeft: Int): android.app.Notification {
            return androidx.core.app.NotificationCompat.Builder(this, chanId)
                .setContentTitle("⚠️ Silent Mode Detected")
                .setContentText("Emergency SMS in ${secsLeft}s — tap Cancel to stop")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel / Stop", cancelPi)
                .build()
        }

        val nm = getSystemService(android.app.NotificationManager::class.java)
        silentGraceHandler?.removeCallbacksAndMessages(null)
        silentGraceHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // Register cancel receiver
        val cancelReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "com.thievestrap.CANCEL_SILENT_GRACE") {
                    silentGraceHandler?.removeCallbacksAndMessages(null)
                    nm?.cancel(silentGraceNotifId)
                    try { unregisterReceiver(this) } catch (e: Exception) {}
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver,
                IntentFilter("com.thievestrap.CANCEL_SILENT_GRACE"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cancelReceiver,
                IntentFilter("com.thievestrap.CANCEL_SILENT_GRACE"))
        }

        silentGraceRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startMs
                val remaining = GRACE_MS - elapsed
                if (remaining <= 0) {
                    nm?.cancel(silentGraceNotifId)
                    try { unregisterReceiver(cancelReceiver) } catch (e: Exception) {}
                    // Fire the alert after grace expires
                    startService(Intent(this@SettingsActivity, MonitorService::class.java).apply {
                        action = "TAMPER"
                        putExtra("reason", "Silent mode activated")
                    })
                    return
                }
                val secsLeft = (remaining / 1000).toInt()
                nm?.notify(silentGraceNotifId, buildGraceNotif(secsLeft))
                silentGraceHandler?.postDelayed(this, 1000)
            }
        }
        nm?.notify(silentGraceNotifId, buildGraceNotif(60))
        silentGraceHandler?.postDelayed(silentGraceRunnable!!, 1000)
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
            prefs.edit().putString("phone",
                findViewById<EditText>(R.id.et_phone).text.toString().trim()).apply()
            if (isPremium) {
                prefs.edit().putString("phone2",
                    findViewById<EditText>(R.id.et_phone2).text.toString().trim()).apply()
            }
            Toast.makeText(this, s("saved"), Toast.LENGTH_SHORT).show()
        }
        if (!isPremium) {
            findViewById<EditText>(R.id.et_phone2).setOnClickListener {
                showUpgradeDialog("Emergency Contact 2")
            }
        }

        // Selfie toggle
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

        // SIM
        val swSim = findViewById<Switch>(R.id.sw_sim)
        swSim.setOnClickListener {
            if (!isPremium) { showUpgradeDialog("SMS on SIM Change"); swSim.isChecked = false }
        }
        swSim.setOnCheckedChangeListener { _, c ->
            if (isPremium) prefs.edit().putBoolean("alert_sim", c).apply()
        }

        // Grace Period Toggle
        val swGrace = findViewById<Switch>(R.id.sw_grace_enabled)
        val seekBar = findViewById<SeekBar>(R.id.seekbar_grace)
        swGrace.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("grace_enabled", enabled).apply()
            seekBar.isEnabled = enabled
            seekBar.alpha = if (enabled) 1f else 0.4f
            updateGraceLabel(prefs.getInt("grace_period", 10), enabled)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val secs = p + 5
                updateGraceLabel(secs, prefs.getBoolean("grace_enabled", false))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val secs = sb.progress + 5
                prefs.edit().putInt("grace_period", secs).apply()
                try {
                    val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        v.vibrate(android.os.VibrationEffect.createOneShot(25,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") v.vibrate(25)
                } catch (e: Exception) {}
            }
        })

        // ── FIX 4: Airplane mode row removed — only Silent mode listener wired ──
        val swSilent = try { findViewById<Switch>(R.id.sw_silent) } catch (e: Exception) { null }
        swSilent?.setOnClickListener {
            if (!isPremium) { showUpgradeDialog("Device Alerts"); swSilent.isChecked = false }
        }
        swSilent?.setOnCheckedChangeListener { _, c ->
            if (isPremium) prefs.edit().putBoolean("alert_silent", c).apply()
        }

        // Failed threshold
        findViewById<Spinner>(R.id.spinner_failed).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
                ) {
                    prefs.edit().putInt("failed_threshold",
                        when (pos) { 0 -> -1; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 5; else -> 3 }
                    ).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // FIX 7: Language icon at bottom of sidebar — handled in MainActivity nav drawer
        // (sidebar language item removed; icon wired in MainActivity.showLanguageDialog)

        // Notification switch
        try {
            val swNotif = findViewById<Switch>(R.id.sw_notif_enabled)
            swNotif.isChecked = prefs.getBoolean("notif_enabled", true)
            swNotif.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("notif_enabled", checked).apply()
                startService(Intent(this, MonitorService::class.java).apply {
                    action = "UPDATE_NOTIF"
                })
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20)
        }
        val etOld = EditText(this).apply {
            hint = s("current_pin")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val etNew = EditText(this).apply {
            hint = s("new_pin")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        layout.addView(etOld); layout.addView(etNew)
        AlertDialog.Builder(this).setTitle(s("change_pin")).setView(layout)
            .setPositiveButton(s("change")) { _, _ ->
                val old = etOld.text.toString().trim()
                val new = etNew.text.toString().trim()
                if (old != (prefs.getString("password", "") ?: ""))
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
