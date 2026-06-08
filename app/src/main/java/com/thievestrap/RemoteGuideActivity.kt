package com.thievestrap

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class RemoteGuideActivity : AppCompatActivity() {

    data class Command(val text: String, val description: String, val premiumOnly: Boolean = true)

    private val commands = listOf(
        Command("WHERE", "Get current GPS location + Google Maps link", premiumOnly = false),
        Command("HISTORY", "Last 5 known locations with timestamps"),
        Command("INFO", "Full device info: brand, model, IMEI, Android version"),
        Command("STATUS", "Quick status: battery, location, mode"),
        Command("BATTERY", "Current battery level and charging status"),
        Command("IMEI", "Get device IMEI number"),
        Command("SIM", "Current SIM card details and operator"),
        Command("ALARM", "Trigger loud alarm — forces phone out of silent mode"),
        Command("STOP ALARM", "Silence the alarm remotely"),
        Command("LOCK", "Lock the phone screen immediately"),
        Command("SELFIE", "Take 3 silent photos of the thief"),
        Command("PING 2", "Send location every 2 minutes automatically"),
        Command("PING 5", "Send location every 5 minutes automatically"),
        Command("STOP PING", "Stop automatic location pings"),
        Command("ACTIVE", "Activate THEFT MODE — all alerts enabled"),
        Command("DEACTIVATE", "Deactivate theft mode — back to normal"),
        Command("DISARM [PIN]", "Stop monitoring (replace [PIN] with your PIN)")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_remote_guide)
        val isPremium = LicenseManager.isPremium(this)

        findViewById<Button>(R.id.btn_back_guide).setOnClickListener { finish() }

        commands.forEach { cmd ->
            val id = when(cmd.text) {
                "WHERE" -> R.id.cmd_where
                "HISTORY" -> R.id.cmd_history
                "INFO" -> R.id.cmd_info
                "STATUS" -> R.id.cmd_status
                "BATTERY" -> R.id.cmd_battery
                "IMEI" -> R.id.cmd_imei
                "SIM" -> R.id.cmd_sim
                "ALARM" -> R.id.cmd_alarm
                "STOP ALARM" -> R.id.cmd_stop_alarm
                "LOCK" -> R.id.cmd_lock
                "SELFIE" -> R.id.cmd_selfie
                "PING 2" -> R.id.cmd_ping2
                "PING 5" -> R.id.cmd_ping5
                "STOP PING" -> R.id.cmd_stop_ping
                "ACTIVE" -> R.id.cmd_active
                "DEACTIVATE" -> R.id.cmd_deactivate
                "DISARM [PIN]" -> R.id.cmd_disarm
                else -> return@forEach
            }
            val view = try { findViewById<android.view.View>(id) } catch (e: Exception) { return@forEach }
                ?: return@forEach

            val locked = cmd.premiumOnly && !isPremium
            val tvText = view.findViewById<TextView>(R.id.tv_cmd_text)
            val tvDesc = view.findViewById<TextView>(R.id.tv_cmd_desc)
            val btnCopy = view.findViewById<Button>(R.id.btn_copy_cmd)

            tvText?.text = cmd.text
            tvDesc?.text = cmd.description

            if (locked) {
                tvText?.alpha = 0.35f
                tvDesc?.alpha = 0.35f
                btnCopy?.visibility = android.view.View.GONE
            } else {
                tvText?.alpha = 1f
                tvDesc?.alpha = 1f
                // No copy buttons — read only guide
                btnCopy?.visibility = android.view.View.GONE
            }
        }
    }
}
