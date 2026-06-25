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

class RemoteGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_remote_guide)

        try {
            findViewById<Button>(R.id.btn_back_guide).setOnClickListener {
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
        } catch (e: Exception) {}

        // Plan B ℹ️ badge — v2.8.1: pulls from string resources
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

    private fun showPlanBInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plan_b_info_title))
            .setMessage(getString(R.string.plan_b_info_body))
            .setPositiveButton(getString(R.string.plan_b_info_button), null)
            .show()
    }

    private fun setupCommandRows() {
        setCmd(R.id.cmd_where,      "WHERE",        "Get current GPS location + Google Maps link")
        setCmd(R.id.cmd_history,    "HISTORY",      "Last 5 known locations with timestamps (Premium)")
        setCmd(R.id.cmd_info,       "INFO",         "Full device info: brand, model, IMEI, Android version")
        setCmd(R.id.cmd_status,     "STATUS",       "Quick status: battery, location, mode")
        setCmd(R.id.cmd_battery,    "BATTERY",      "Current battery level and charging status")
        setCmd(R.id.cmd_imei,       "IMEI",         "Get device IMEI number (Premium)")
        setCmd(R.id.cmd_sim,        "SIM",          "Current SIM card details and operator (Premium)")
        setCmd(R.id.cmd_alarm,      "ALARM",        "Trigger loud alarm — forces phone out of silent mode")
        setCmd(R.id.cmd_stop_alarm, "STOP ALARM",   "Stop the alarm remotely")
        setCmd(R.id.cmd_lock,       "LOCK",         "Lock the screen immediately (Premium)")
        setCmd(R.id.cmd_selfie,     "SELFIE",       "Silently capture 3 front-camera photos (Premium)")
        setCmd(R.id.cmd_ping2,      "PING 2",       "Send GPS location every 2 minutes (Premium)")
        setCmd(R.id.cmd_ping5,      "PING 5",       "Send GPS location every 5 minutes (Premium)")
        setCmd(R.id.cmd_stop_ping,  "STOP PING",    "Stop periodic location updates (Premium)")
        setCmd(R.id.cmd_active,     "ACTIVE",       "Enable Full Protection / Theft Mode (Premium)")
        setCmd(R.id.cmd_deactivate, "DEACTIVATE",   "Disable all theft alerts remotely (Premium)")
        setCmd(R.id.cmd_disarm,     "DISARM [PIN]", "Remotely stop monitoring (Premium)")
    }

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
