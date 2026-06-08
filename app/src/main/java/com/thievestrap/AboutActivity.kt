package com.thievestrap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_about)
        try { findViewById<android.widget.TextView>(R.id.tv_about_version)
            .text = "Version ${BuildConfig.VERSION_NAME}" } catch (e: Exception) {}

        // Rate — Play Store link
        findViewById<android.view.View>(R.id.item_rate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }

        // Share
        findViewById<android.view.View>(R.id.item_share).setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Thieves Trap — Phone Security App")
                    putExtra(Intent.EXTRA_TEXT,
                        "Protect your phone from thieves! Download Thieves Trap:\n" +
                        "https://play.google.com/store/apps/details?id=$packageName")
                }, "Share via"))
        }

        // Terms of Use
        findViewById<android.view.View>(R.id.item_terms).setOnClickListener {
            openUrl("https://iline.tech/thieves-trap/terms")
        }

        // Privacy Policy
        findViewById<android.view.View>(R.id.item_privacy).setOnClickListener {
            openUrl("https://iline.tech/thieves-trap/privacy")
        }

        findViewById<Button>(R.id.btn_back_about).setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }
}
