package com.thievestrap

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.splash_logo)
        val title = findViewById<TextView>(R.id.splash_title)
        val subtitle = findViewById<TextView>(R.id.splash_subtitle)
        val laser = findViewById<android.view.View>(R.id.laser_line)
        val handler = Handler(Looper.getMainLooper())

        // 1. Fade in logo + title
        logo.alpha = 0f
        title.alpha = 0f
        logo.animate().alpha(1f).setDuration(600).start()
        title.animate().alpha(1f).setDuration(800).setStartDelay(200).start()

        // 2. Pulse logo
        handler.postDelayed({
            logo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse))
        }, 400)

        // 3. Laser scan animation — moves top→bottom across logo over 1200ms
        handler.postDelayed({
            laser.visibility = android.view.View.VISIBLE
            laser.animate()
                .translationY(60f.dpToPx())
                .setDuration(1200)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction { laser.visibility = android.view.View.INVISIBLE }
                .start()
        }, 500)

        // 4. Fade in "POWERED BY ILINE TECH"
        handler.postDelayed({
            subtitle.animate().alpha(1f).setDuration(600).start()
        }, 900)

        // 5. Navigate after 2.8s — check consent first
        handler.postDelayed({
            val prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
            val target = when {
                !prefs.getBoolean("consent_given", false) -> ConsentActivity::class.java
                !prefs.contains("password")               -> SetupActivity::class.java
                else                                      -> MainActivity::class.java
            }
            startActivity(Intent(this, target))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 2800)
    }

    private fun Float.dpToPx(): Float =
        this * resources.displayMetrics.density
}
