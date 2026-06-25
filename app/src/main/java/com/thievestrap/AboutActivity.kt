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
        try {
            findViewById<TextView>(R.id.tv_about_version).text =
                "${getString(R.string.about_version_label)} ${BuildConfig.VERSION_NAME}"
        } catch (e: Exception) {}

        // Rate
        findViewById<android.view.View>(R.id.item_rate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }

        // Share — v2.8.1: uses localized strings
        findViewById<android.view.View>(R.id.item_share).setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
                    putExtra(Intent.EXTRA_TEXT,
                        "${getString(R.string.share_app_body)}\n" +
                        "https://play.google.com/store/apps/details?id=$packageName")
                }, getString(R.string.share_chooser_title)))
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
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
        }
    }
}
