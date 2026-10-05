package com.thievestrap

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.random.Random

/**
 * v2.9.3: Family Security Network, UI PREVIEW ONLY.
 *
 * Every action on this screen shows the "coming soon" dialog. Nothing here
 * opens a socket, calls a relay, reads GPS, or starts a service. The real
 * Cloudflare-backed implementation is scheduled for v3.0.0.
 */
class FamilyNetworkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_family_network)

        findViewById<TextView>(R.id.tv_pair_code).text = previewPairCode()

        findViewById<Button>(R.id.btn_back_family).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_family_info).setOnClickListener { showInfoDialog() }

        val comingSoon = View.OnClickListener { showComingSoon() }
        intArrayOf(
            R.id.btn_family_connect,
            R.id.btn_family_start,
            R.id.btn_family_stop,
            R.id.btn_family_alarm,
            R.id.card_family_history
        ).forEach { findViewById<View>(it).setOnClickListener(comingSoon) }
    }

    /** Local-only placeholder code, generated once per install. Not registered anywhere. */
    private fun previewPairCode(): String {
        val prefs = getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("family_pair_code_preview", null)
        if (!saved.isNullOrBlank()) return saved
        val code = "TRAP-" + Random.nextInt(1000, 10000)
        prefs.edit().putString("family_pair_code_preview", code).apply()
        return code
    }

    private fun showComingSoon() {
        AlertDialog.Builder(this)
            .setTitle(R.string.fn_soon_title)
            .setMessage(R.string.fn_soon_msg)
            .setPositiveButton(R.string.fn_ok, null)
            .show()
    }

    private fun showInfoDialog() {
        val message = getString(R.string.fn_info_how) + "\n\n" + getString(R.string.fn_info_net)
        AlertDialog.Builder(this)
            .setTitle(R.string.fn_info_title)
            .setMessage(message)
            .setPositiveButton(R.string.fn_ok, null)
            .show()
    }
}
