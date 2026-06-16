package com.thievestrap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * RemoteGuideActivity — v2.8.0
 *
 * Fix: uses correct item_command.xml IDs (tv_cmd_text, tv_cmd_desc).
 *      Each row now shows its own unique command keyword — no more
 *      every row showing "WHERE".
 *      Plan B ℹ️ badge retained.
 */
class RemoteGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_remote_guide)

        // Back button
        try {
            findViewById<Button>(R.id.btn_back_guide).setOnClickListener {
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
        } catch (e: Exception) {}

        // Plan B ℹ️ badge
        try {
            val infoId = resources.getIdentifier("btn_plan_b_info", "id", packageName)
            if (infoId != 0) {
                findViewById<android.view.View>(infoId)?.setOnClickListener {
                    showPlanBInfoDialog()
                }
            }
        } catch (e: Exception) {}

        setupCommandRows()
    }

    // ── Plan B info dialog ─────────────────────────────────────

    private fun showPlanBInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("\uD83D\uDD10 Secure Plan B Commands")
            .setMessage(
                "By default, commands without a PIN send alerts only to your " +
                "registered emergency contacts.\n\n" +
                "However, if you append your live APP PIN (e.g., \"WHERE 2026\"), " +
                "the app will bypass all registration limits and reply directly to " +
                "that unknown sender's phone!\n\n" +
                "This PIN updates dynamically in real-time whenever you modify it " +
                "inside the application settings, keeping your remote access 100% " +
                "customized and protected."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    // ── Command rows ───────────────────────────────────────────
    // Uses the real IDs from item_command.xml:
    //   tv_cmd_text  — the command keyword (bold monospace)
    //   tv_cmd_desc  — the description text
    //   btn_copy_cmd — copy button

    private fun setupCommandRows() {
        // LOCATION
        setCmd(R.id.cmd_where,   "WHERE",    "Get current GPS location + Google Maps link")
        setCmd(R.id.cmd_history, "HISTORY",  "Last 5 known locations with timestamps (Premium)")

        // DEVICE
        setCmd(R.id.cmd_info,    "INFO",     "Full device info: brand, model, IMEI, Android version")
        setCmd(R.id.cmd_status,  "STATUS",   "Quick status: battery, location, mode")
        setCmd(R.id.cmd_battery, "BATTERY",  "Current battery level and charging status")
        setCmd(R.id.cmd_imei,    "IMEI",     "Get device IMEI number (Premium)")
        setCmd(R.id.cmd_sim,     "SIM",      "Current SIM card details and operator (Premium)")

        // SECURITY
        setCmd(R.id.cmd_alarm,      "ALARM",      "Trigger loud alarm — forces phone out of silent mode")
        setCmd(R.id.cmd_stop_alarm, "STOP ALARM", "Stop the alarm remotely")
        setCmd(R.id.cmd_lock,       "LOCK",       "Lock the screen immediately (Premium)")
        setCmd(R.id.cmd_selfie,     "SELFIE",     "Silently capture 3 front-camera photos (Premium)")

        // TRACKING
        setCmd(R.id.cmd_ping2,     "PING 2",    "Send GPS location every 2 minutes (Premium)")
        setCmd(R.id.cmd_ping5,     "PING 5",    "Send GPS location every 5 minutes (Premium)")
        setCmd(R.id.cmd_stop_ping, "STOP PING", "Stop periodic location updates (Premium)")

        // CONTROL
        setCmd(R.id.cmd_active,     "ACTIVE",       "Enable Full Protection / Theft Mode (Premium)")
        setCmd(R.id.cmd_deactivate, "DEACTIVATE",   "Disable all theft alerts remotely (Premium)")
        setCmd(R.id.cmd_disarm,     "DISARM [PIN]", "Remotely stop monitoring (Premium)")
    }

    /**
     * Populates one item_command row.
     * tv_cmd_text  = the SMS command keyword
     * tv_cmd_desc  = description
     * btn_copy_cmd = copies the command to clipboard on tap
     */
    private fun setCmd(rowId: Int, command: String, description: String) {
        try {
            val row = findViewById<android.view.View>(rowId) ?: return
            row.findViewById<TextView>(R.id.tv_cmd_text)?.text = command
            row.findViewById<TextView>(R.id.tv_cmd_desc)?.text = description
            row.findViewById<Button>(R.id.btn_copy_cmd)?.setOnClickListener {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cmd", command))
                    Toast.makeText(this, "\"$command\" copied!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
