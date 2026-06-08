package com.thievestrap

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_consent)

        val cbConsent = findViewById<CheckBox>(R.id.cb_consent)
        val btnStart  = findViewById<Button>(R.id.btn_get_started)

        // Globe language button — top corner
        try {
            findViewById<android.widget.TextView>(R.id.btn_consent_language)
                .setOnClickListener { showConsentLanguageDialog() }
        } catch (e: Exception) {}


        // Privacy Policy link
        try {
            findViewById<android.widget.TextView>(R.id.tv_privacy_link).setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Privacy Policy")
                    .setMessage(
                        "PRIVACY POLICY\n\n" +
                        "1. DIRECT TRANSMISSION\n" +
                        "Photos and GPS location captured by Thieves Trap are sent instantly and exclusively to your private Telegram Bot or SMS emergency contact. " +
                        "No data is routed through our servers.\n\n" +
                        "2. ABSOLUTE PRIVACY\n" +
                        "We do not own, store, or host any of your personal data on external servers. " +
                        "Your data stays between you and your device.\n\n" +
                        "3. PERMISSIONS\n" +
                        "The app requires Device Administrator, Camera, and Location access solely to detect and report unauthorized access to your device.\n\n" +
                        "4. DISCLAIMER\n" +
                        "Thieves Trap does not guarantee 100% device recovery in all situations. " +
                        "You are responsible for using this app in compliance with local laws."
                    )
                    .setPositiveButton("Close", null)
                    .show()
            }
        } catch (e: Exception) {}

        // Terms of Use link
        try {
            findViewById<android.widget.TextView>(R.id.tv_terms_link).setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Terms of Use")
                    .setMessage(
                        "TERMS OF USE\n\n" +
                        "By using Thieves Trap, you agree to the following:\n\n" +
                        "\u2022 This app is intended for personal device protection only.\n\n" +
                        "\u2022 You may not use this app to monitor any device without the owner's knowledge and consent.\n\n" +
                        "\u2022 The app may require Device Administrator privileges. Granting these is your choice and responsibility.\n\n" +
                        "\u2022 Premium features require a valid license key obtained through official channels.\n\n" +
                        "\u2022 The developer is not liable for any data loss, device theft, or legal issues arising from use of this app."
                    )
                    .setPositiveButton("Close", null)
                    .show()
            }
        } catch (e: Exception) {}

        // Button disabled until checkbox ticked
        btnStart.isEnabled = false
        btnStart.alpha = 0.4f

        cbConsent.setOnCheckedChangeListener { _, isChecked ->
            btnStart.isEnabled = isChecked
            btnStart.alpha = if (isChecked) 1.0f else 0.4f
        }

        btnStart.setOnClickListener {
            if (!cbConsent.isChecked) return@setOnClickListener
            // Save consent permanently
            getSharedPreferences("tt_prefs", MODE_PRIVATE)
                .edit().putBoolean("consent_given", true).apply()
            // Proceed to Setup or Main
            val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
            val target = if (!prefs.contains("password"))
                SetupActivity::class.java
            else
                MainActivity::class.java
            startActivity(Intent(this, target))
            finish()
        }
    }
    private fun showConsentLanguageDialog() {
        val gb = "\uD83C\uDDEC\uD83C\uDDE7"
        val fr = "\uD83C\uDDEB\uD83C\uDDF7"
        val dz = "\uD83C\uDDE9\uD83C\uDDFF"
        val options = arrayOf("$gb  English", "$fr  Fran\u00e7ais", "$dz  \u0627\u0644\u0639\u0631\u0628\u064a\u0629")
        val codes   = arrayOf("en", "fr", "ar")
        val prefs   = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val cur     = prefs.getString("language", "en") ?: "en"
        val sel     = codes.indexOf(cur).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("\uD83C\uDF10  Select Language")
            .setSingleChoiceItems(options, sel) { dlg, which ->
                prefs.edit().putString("language", codes[which]).apply()
                LocaleHelper.applyLocale(this)
                dlg.dismiss()
                // Restart consent screen to show translated text
                recreate()
            }
            .setNegativeButton("Cancel", null).show()
    }

}
