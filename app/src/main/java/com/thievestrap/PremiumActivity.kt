package com.thievestrap

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class PremiumActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private fun s(key: String) = Strings.get(this, key)

    // Facebook page URL for support
    private val FACEBOOK_PAGE_URL = "https://www.facebook.com/share/1MRdfCnNoY/"
    private val FACEBOOK_APP_URL = "fb://facewebmodal/f?href=$FACEBOOK_PAGE_URL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_premium)
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)

        val isPremium = LicenseManager.isPremium(this)
        val androidId = LicenseManager.getAndroidId(this)

        findViewById<TextView>(R.id.tv_device_id).text = "Device ID: $androidId"

        if (isPremium) {
            findViewById<TextView>(R.id.tv_premium_status).apply {
                text = "✅ PREMIUM ACTIVE"
                setTextColor(0xFF00CC44.toInt())
            }
            findViewById<Button>(R.id.btn_buy).apply {
                text = "Premium is Active ✅"
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF003300.toInt())
                setTextColor(0xFF00CC44.toInt())
                isEnabled = false
            }
        } else {
            findViewById<TextView>(R.id.tv_premium_status).apply {
                text = "🔒 FREE VERSION"
                setTextColor(0xFF666666.toInt())
            }
            val etKey = findViewById<EditText>(R.id.et_license_key)
            etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            findViewById<Button>(R.id.btn_buy).setOnClickListener {
                val key = etKey.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "Enter your license key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                when (LicenseManager.validateKey(this, key)) {
                    LicenseManager.ValidationResult.SUCCESS_DEVICE,
                    LicenseManager.ValidationResult.SUCCESS_MASTER -> {
                        Toast.makeText(this, s("license_activated"), Toast.LENGTH_LONG).show()
                        recreate()
                    }
                    LicenseManager.ValidationResult.INVALID ->
                        Toast.makeText(
                            this,
                            "${s("license_invalid")}\nDevice ID: $androidId",
                            Toast.LENGTH_LONG
                        ).show()
                }
            }
        }

        // Visa/MasterCard — coming soon
        findViewById<Button>(R.id.btn_pay_card).setOnClickListener {
            Toast.makeText(this, getString(R.string.coming_soon_badge), Toast.LENGTH_SHORT).show()
        }

        // Contact Support → Facebook page
        findViewById<Button>(R.id.btn_pay_support).setOnClickListener {
            openFacebook()
        }

        // Get Device ID
        try {
            findViewById<Button>(R.id.btn_restore).setOnClickListener {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Device ID", androidId))
                Toast.makeText(this, "Device ID copied: $androidId", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {}

        findViewById<Button>(R.id.btn_back_premium).setOnClickListener { finish() }

        setupRevokeButton(isPremium)
    }

    private fun openFacebook() {
        // Try to open Facebook app first, fall back to browser
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FACEBOOK_APP_URL)))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FACEBOOK_PAGE_URL)))
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRevokeButton(isPremium: Boolean) {
        val btn = try { findViewById<Button>(R.id.btn_revoke_license) }
                  catch (e: Exception) { return }
        btn.visibility = if (isPremium) android.view.View.VISIBLE else android.view.View.GONE
        btn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Revoke License")
                .setMessage("Are you sure? All premium features will be locked.")
                .setPositiveButton("Revoke") { _, _ ->
                    LicenseManager.revokePremium(this)
                    Toast.makeText(this, "License revoked.", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}
