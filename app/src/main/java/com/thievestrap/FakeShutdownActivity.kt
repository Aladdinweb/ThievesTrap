package com.thievestrap

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class FakeShutdownActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var volUpCount = 0
    private var lastVolUpTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)

        // Full screen — cover status bar, nav bar, lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val black = View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        setContentView(black)

        hideSystemUI()
        Log.i("TT-FakeShutdown", "Black overlay active")
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val now = System.currentTimeMillis()
                    if (now - lastVolUpTime > 1500L) volUpCount = 0
                    volUpCount++
                    lastVolUpTime = now
                    if (volUpCount >= 3) { volUpCount = 0; triggerExit() }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> return true
                KeyEvent.KEYCODE_BACK -> return true
                KeyEvent.KEYCODE_HOME -> return true
                KeyEvent.KEYCODE_APP_SWITCH -> return true
            }
        }
        return true  // consume everything else
    }

    override fun onBackPressed() { /* blocked */ }

    private fun triggerExit() {
        haptic()
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS) {
            BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = dismiss()
                    override fun onAuthenticationError(code: Int, msg: CharSequence) = showPin()
                    override fun onAuthenticationFailed() = showPin()
                }).authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Emergency Exit — Thieves Trap")
                .setSubtitle("Verify identity to dismiss overlay")
                .setNegativeButtonText("Use PIN").build())
        } else showPin()
    }

    private fun showPin() {
        val input = android.widget.EditText(this).apply {
            hint = "Enter Security PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(40, 24, 40, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Emergency Exit")
            .setMessage("Enter your PIN to dismiss the overlay")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                if (input.text.toString().trim() == prefs.getString("password", ""))
                    dismiss()
                else
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .create().also {
                it.show()
                it.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(android.graphics.Color.parseColor("#CC0000"))
            }
    }

    private fun dismiss() {
        prefs.edit().putBoolean("fake_shutdown_active", false).apply()
        finish()
        Log.i("TT-FakeShutdown", "Dismissed by owner")
    }

    private fun haptic() = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java))
                .defaultVibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(80)
    } catch (e: Exception) {}

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, FakeShutdownActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }
}
