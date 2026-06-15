package com.thievestrap

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * RemoteGuideActivity — v2.7.9b
 *
 * Retained: full command list display, back button.
 * v2.7.9b: btn_plan_b_info ℹ️ badge in header launches "Secure Plan B
 * Commands" AlertDialog explaining the [COMMAND] [PIN] mechanism.
 */
class RemoteGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_remote_guide)

        // Back button
        try {
            findViewById<android.widget.Button>(R.id.btn_back_guide).setOnClickListener {
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
        } catch (e: Exception) {}

        // v2.7.9b: Plan B ℹ️ badge — explains dynamic PIN mechanism
        try {
            findViewById<android.view.View>(R.id.btn_plan_b_info).setOnClickListener {
                showPlanBInfoDialog()
            }
        } catch (e: Exception) {}

        // Wire up command rows (existing logic retained)
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

    // ── Command rows setup (retained) ──────────────────────────

    private fun setupCommandRows() {
        // LOCATION section
        try {
            setCommandRow(R.id.cmd_where,
                "WHERE", "Send GPS location + Maps link to sender")
            setCommandRow(R.id.cmd_history,
                "HISTORY", "Send last 5 recorded locations (Premium)")
        } catch (e: Exception) {}

        // DEVICE section
        try {
            setCommandRow(R.id.cmd_info,
                "INFO", "Full device report — model, battery, SIM, IMEI (Premium)")
            setCommandRow(R.id.cmd_status,
                "STATUS", "Current armed/theft mode status")
            setCommandRow(R.id.cmd_battery,
                "BATTERY", "Battery percentage + charging status (Premium)")
            setCommandRow(R.id.cmd_imei,
                "IMEI", "Device IMEI + Android ID (Premium)")
            setCommandRow(R.id.cmd_sim,
                "SIM", "Current SIM carrier, country, number (Premium)")
        } catch (e: Exception) {}

        // SECURITY section
        try {
            setCommandRow(R.id.cmd_alarm,
                "ALARM", "Trigger max-volume siren remotely (Premium)")
            setCommandRow(R.id.cmd_stop_alarm,
                "STOP ALARM", "Silence the alarm (Premium)")
            setCommandRow(R.id.cmd_lock,
                "LOCK", "Lock screen immediately via Device Admin (Premium)")
            setCommandRow(R.id.cmd_selfie,
                "SELFIE", "Capture 3 front camera photos silently (Premium)")
        } catch (e: Exception) {}

        // TRACKING section
        try {
            setCommandRow(R.id.cmd_ping2,
                "PING 2", "Send GPS location every 2 minutes (Premium)")
            setCommandRow(R.id.cmd_ping5,
                "PING 5", "Send GPS location every 5 minutes (Premium)")
            setCommandRow(R.id.cmd_stop_ping,
                "STOP PING", "Stop periodic location updates (Premium)")
        } catch (e: Exception) {}

        // CONTROL section
        try {
            setCommandRow(R.id.cmd_active,
                "ACTIVE", "Enable Full Protection / Theft Mode (Premium)")
            setCommandRow(R.id.cmd_deactivate,
                "DEACTIVATE", "Disable all theft alerts remotely (Premium)")
            setCommandRow(R.id.cmd_disarm,
                "DISARM [PIN]", "Remotely stop monitoring (Premium)")
        } catch (e: Exception) {}
    }

    /**
     * Sets the command name and description on an item_command row.
     * item_command layout is expected to have:
     *   R.id.tv_command_name  — the SMS command text
     *   R.id.tv_command_desc  — the description text
     */
    private fun setCommandRow(rowId: Int, command: String, description: String) {
        try {
            val row = findViewById<android.view.View>(rowId)
            row?.let {
                it.findViewById<TextView>(R.id.tv_command_name)?.text = command
                it.findViewById<TextView>(R.id.tv_command_desc)?.text = description
            }
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
