package com.thievestrap

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * v2.9.9.9: license keys are now validated by the Cloudflare Worker (see
 * LicenseManager). A new line under the Device ID shows the expiry date and
 * days left, or "Lifetime" / "À vie" / "مدى الحياة", in the app language.
 */
class PremiumActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private fun s(key: String) = Strings.get(this, key)

    // Facebook page URL for support
    private val FACEBOOK_PAGE_URL = "https://www.facebook.com/share/1D2RrP4ey2/"
    private val FACEBOOK_APP_URL = "fb://facewebmodal/f?href=$FACEBOOK_PAGE_URL"

    // Messenger page username or numeric Page ID. Blank = fall back to the Facebook page.
    private val MESSENGER_PAGE = "61576473907901"

    private enum class Plan { MONTHLY, THREE_MONTH, ANNUAL }
    private var selectedPlan: Plan = Plan.MONTHLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_premium)
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)

        val isPremium = LicenseManager.isPremium(this)
        val androidId = LicenseManager.getAndroidId(this)

        findViewById<TextView>(R.id.tv_device_id).text = "Device ID: $androidId"

        showDeviceIdLine(androidId)
        // Ask the server right now (picks up a revoke / delete / move) and redraw if the answer changes anything.
        val shownPremium = isPremium
        LicenseManager.refreshAsync(applicationContext) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val info = LicenseManager.getLicenseInfo(this)
                val nowPremium = info != null && !info.expired && !info.leaseExpired
                if (nowPremium != shownPremium) recreate() else showDeviceIdLine(androidId)
            }
        }
        setupStatusBadge(isPremium)
        setupExpiryLine()
        setupPlanCards()

        if (isPremium) {
            findViewById<Button>(R.id.btn_buy).apply {
                text = "${s("license_activated")} \u2705"
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF003300.toInt())
                setTextColor(0xFF00CC44.toInt())
                isEnabled = false
            }
        } else {
            val etKey = findViewById<EditText>(R.id.et_license_key)
            etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val btnBuy = findViewById<Button>(R.id.btn_buy)
            btnBuy.setOnClickListener {
                val key = etKey.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, tr("Enter your license key", "Entrez votre cl\u00E9 de licence", "\u0623\u062F\u062E\u0644 \u0645\u0641\u062A\u0627\u062D \u0627\u0644\u062A\u0631\u062E\u064A\u0635"), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val originalText = btnBuy.text
                btnBuy.isEnabled = false
                btnBuy.text = tr("Verifying...", "V\u00E9rification...", "\u062C\u0627\u0631\u064D \u0627\u0644\u062A\u062D\u0642\u0642...")
                Thread {
                    val result = LicenseManager.activate(applicationContext, key)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        btnBuy.isEnabled = true
                        btnBuy.text = originalText
                        handleActivationResult(result, androidId)
                    }
                }.start()
            }
        }

        // Contact Support -> Facebook page
        findViewById<Button>(R.id.btn_pay_support).setOnClickListener {
            openFacebook()
        }

        // Copy Device ID
        findViewById<Button>(R.id.btn_copy_device_id).setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Device ID", androidId))
            Toast.makeText(this, "Device ID copied: $androidId", Toast.LENGTH_LONG).show()
        }

        setupRevokeButton(isPremium)
    }

    /** Device ID plus the result of the latest server check, so a failing check is visible on screen. */
    private fun showDeviceIdLine(androidId: String) {
        val last = LicenseManager.lastCheckSummary(this)
        val label = tr("Last check", "Derni\u00E8re v\u00E9rification", "\u0622\u062E\u0631 \u062A\u062D\u0642\u0642")
        findViewById<TextView>(R.id.tv_device_id).text =
            "Device ID: $androidId" + (if (last != null) "\n$label: $last" else "")
    }

    private fun tr(en: String, fr: String, ar: String): String = when (appLang()) {
        "fr" -> fr
        "ar" -> ar
        else -> en
    }

    private fun handleActivationResult(result: LicenseManager.ActivationResult, androidId: String) {
        val msg: String = when (result) {
            is LicenseManager.ActivationResult.Success -> { showSubscriptionSuccessDialog(); return }
            LicenseManager.ActivationResult.InvalidKey ->
                tr("Invalid license key.", "Cl\u00E9 de licence invalide.", "\u0645\u0641\u062A\u0627\u062D \u0627\u0644\u062A\u0631\u062E\u064A\u0635 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D.")
            LicenseManager.ActivationResult.KeyInUse ->
                tr("This key is already used on another device.", "Cette cl\u00E9 est d\u00E9j\u00E0 utilis\u00E9e sur un autre appareil.", "\u0647\u0630\u0627 \u0627\u0644\u0645\u0641\u062A\u0627\u062D \u0645\u0633\u062A\u062E\u062F\u0645 \u0639\u0644\u0649 \u062C\u0647\u0627\u0632 \u0622\u062E\u0631.")
            LicenseManager.ActivationResult.Revoked ->
                tr("This key has been revoked.", "Cette cl\u00E9 a \u00E9t\u00E9 r\u00E9voqu\u00E9e.", "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0647\u0630\u0627 \u0627\u0644\u0645\u0641\u062A\u0627\u062D.")
            LicenseManager.ActivationResult.MigrationLimit ->
                tr("Too many device changes for this key. Please contact support.", "Trop de changements d'appareil pour cette cl\u00E9. Contactez le support.", "\u062A\u0645 \u062A\u062C\u0627\u0648\u0632 \u062D\u062F \u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u062C\u0647\u0627\u0632 \u0644\u0647\u0630\u0627 \u0627\u0644\u0645\u0641\u062A\u0627\u062D. \u062A\u0648\u0627\u0635\u0644 \u0645\u0639 \u0627\u0644\u062F\u0639\u0645.")
            LicenseManager.ActivationResult.BadClock ->
                tr("Your device clock is wrong. Set date and time to automatic.", "L'horloge de l'appareil est incorrecte. Activez la date et l'heure automatiques.", "\u0633\u0627\u0639\u0629 \u0627\u0644\u062C\u0647\u0627\u0632 \u063A\u064A\u0631 \u0635\u062D\u064A\u062D\u0629. \u0641\u0639\u0651\u0644 \u0627\u0644\u062A\u0627\u0631\u064A\u062E \u0648\u0627\u0644\u0648\u0642\u062A \u0627\u0644\u062A\u0644\u0642\u0627\u0626\u064A.")
            LicenseManager.ActivationResult.Expired ->
                tr("This key has expired.", "Cette cl\u00E9 a expir\u00E9.", "\u0627\u0646\u062A\u0647\u062A \u0635\u0644\u0627\u062D\u064A\u0629 \u0647\u0630\u0627 \u0627\u0644\u0645\u0641\u062A\u0627\u062D.")
            LicenseManager.ActivationResult.Network ->
                tr("No connection to the license server. Check your internet and try again.", "Pas de connexion au serveur de licences. V\u00E9rifiez votre internet.", "\u0644\u0627 \u064A\u0648\u062C\u062F \u0627\u062A\u0635\u0627\u0644 \u0628\u062E\u0627\u062F\u0645 \u0627\u0644\u062A\u0631\u0627\u062E\u064A\u0635. \u062A\u062D\u0642\u0642 \u0645\u0646 \u0627\u0644\u0625\u0646\u062A\u0631\u0646\u062A.")
            is LicenseManager.ActivationResult.ServerError ->
                tr("License server error. Try again later.", "Erreur du serveur de licences. R\u00E9essayez plus tard.", "\u062E\u0637\u0623 \u0641\u064A \u062E\u0627\u062F\u0645 \u0627\u0644\u062A\u0631\u0627\u062E\u064A\u0635. \u062D\u0627\u0648\u0644 \u0644\u0627\u062D\u0642\u0627\u064B.") + " (" + result.detail + ")"
            is LicenseManager.ActivationResult.BadResponse ->
                tr("Security check failed. Please contact support.", "Contr\u00F4le de s\u00E9curit\u00E9 \u00E9chou\u00E9. Contactez le support.", "\u0641\u0634\u0644 \u0627\u0644\u062A\u062D\u0642\u0642 \u0627\u0644\u0623\u0645\u0646\u064A. \u062A\u0648\u0627\u0635\u0644 \u0645\u0639 \u0627\u0644\u062F\u0639\u0645.") + " [" + result.reason + "]"
            LicenseManager.ActivationResult.NotConfigured ->
                "License server not configured in this build."
        }
        Toast.makeText(this, "$msg\nDevice ID: $androidId", Toast.LENGTH_LONG).show()
    }

    /** Expiry date + days left, or Lifetime, right under the Device ID. */
    private fun setupExpiryLine() {
        val tv = findViewById<TextView>(R.id.tv_expiry)
        val info = LicenseManager.getLicenseInfo(this)
        if (info == null) { tv.visibility = android.view.View.GONE; return }
        tv.visibility = android.view.View.VISIBLE

        if (info.leaseExpired && !info.expired) {
            tv.text = tr("Online check required. Connect to the internet.", "V\u00E9rification en ligne requise. Connectez-vous \u00E0 internet.", "\u064A\u0644\u0632\u0645 \u0627\u0644\u062A\u062D\u0642\u0642 \u0639\u0628\u0631 \u0627\u0644\u0625\u0646\u062A\u0631\u0646\u062A. \u0627\u062A\u0635\u0644 \u0628\u0627\u0644\u0625\u0646\u062A\u0631\u0646\u062A.")
            tv.setTextColor(0xFFFFA726.toInt())
            return
        }
        if (info.expMillis == 0L) {
            tv.text = tr("Lifetime", "\u00C0 vie", "\u0645\u062F\u0649 \u0627\u0644\u062D\u064A\u0627\u0629")
            tv.setTextColor(0xFFFFD700.toInt())
            return
        }
        val locale = resources.configuration.locales[0]
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
            .format(java.util.Date(info.expMillis))
        if (info.expired) {
            tv.text = tr("Expired on $date", "Expir\u00E9 le $date", "\u0627\u0646\u062A\u0647\u0649 \u0641\u064A $date")
            tv.setTextColor(0xFFEF5350.toInt())
            return
        }
        val days = Math.ceil((info.expMillis - System.currentTimeMillis()) / 86400000.0).toInt().coerceAtLeast(1)
        val dot = "\u00B7"
        tv.text = when (appLang()) {
            "fr" -> "Expire le $date $dot $days ${if (days == 1) "jour restant" else "jours restants"}"
            "ar" -> "\u064A\u0646\u062A\u0647\u064A \u0641\u064A $date $dot \u0645\u062A\u0628\u0642\u064A $days \u064A\u0648\u0645\u064B\u0627"
            else -> "Expires $date $dot $days ${if (days == 1) "day" else "days"} left"
        }
        tv.setTextColor(if (days <= 7) 0xFFFFA726.toInt() else 0xFF66BB6A.toInt())
    }

    private fun setupStatusBadge(isPremium: Boolean) {
        val layout = findViewById<LinearLayout>(R.id.layout_premium_status)
        val label = findViewById<TextView>(R.id.tv_premium_status)
        val dot = findViewById<android.view.View>(R.id.dot_premium_status)
        if (isPremium) {
            layout.setBackgroundResource(R.drawable.bg_status_active)
            label.text = getString(R.string.premium_active_badge)
            label.setTextColor(0xFF66BB6A.toInt())
            dot.setBackgroundResource(R.drawable.dot_status) // already green
        } else {
            layout.setBackgroundResource(R.drawable.bg_status_free)
            label.text = getString(R.string.free_version_badge)
            label.setTextColor(0xFF8E8E93.toInt())
            dot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8E8E93.toInt())
        }
    }

    /** "1 month" / "3 months" / "1 year" under each price, in the current app language. */
    private fun durationLabel(plan: Plan): String {
        return when (appLang()) {
            "fr" -> when (plan) {
                Plan.MONTHLY -> "1 mois"
                Plan.THREE_MONTH -> "3 mois"
                Plan.ANNUAL -> "1 an"
            }
            "ar" -> when (plan) {
                Plan.MONTHLY -> "\u0634\u0647\u0631 \u0648\u0627\u062D\u062F"
                Plan.THREE_MONTH -> "3 \u0623\u0634\u0647\u0631"
                Plan.ANNUAL -> "\u0633\u0646\u0629 \u0648\u0627\u062D\u062F\u0629"
            }
            else -> when (plan) {
                Plan.MONTHLY -> "1 month"
                Plan.THREE_MONTH -> "3 months"
                Plan.ANNUAL -> "1 year"
            }
        }
    }

    /** Three pricing cards: tapping one highlights it AND opens Messenger with the subscription request. */
    private fun setupPlanCards() {
        val cardMonthly = findViewById<FrameLayout>(R.id.card_plan_monthly)
        val card3Month  = findViewById<FrameLayout>(R.id.card_plan_3month)
        val cardAnnual  = findViewById<FrameLayout>(R.id.card_plan_annual)

        val labelMonthly = findViewById<TextView>(R.id.tv_label_monthly)
        val label3Month  = findViewById<TextView>(R.id.tv_label_3month)
        val labelAnnual  = findViewById<TextView>(R.id.tv_label_annual)

        val priceMonthly = findViewById<TextView>(R.id.tv_price_monthly)
        val price3Month  = findViewById<TextView>(R.id.tv_price_3month)
        val priceAnnual  = findViewById<TextView>(R.id.tv_price_annual)

        findViewById<TextView>(R.id.tv_sub_monthly).text = durationLabel(Plan.MONTHLY)
        findViewById<TextView>(R.id.tv_sub_3month).text = durationLabel(Plan.THREE_MONTH)
        findViewById<TextView>(R.id.tv_sub_annual).text = durationLabel(Plan.ANNUAL)

        fun applySelection() {
            val gold = 0xFFFFD700.toInt()
            val white = 0xFFFFFFFF.toInt()
            val silver = 0xFFC0C0C8.toInt()

            cardMonthly.setBackgroundResource(if (selectedPlan == Plan.MONTHLY) R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card)
            card3Month.setBackgroundResource(if (selectedPlan == Plan.THREE_MONTH) R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card)
            cardAnnual.setBackgroundResource(if (selectedPlan == Plan.ANNUAL) R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card)

            labelMonthly.setTextColor(if (selectedPlan == Plan.MONTHLY) gold else white)
            label3Month.setTextColor(if (selectedPlan == Plan.THREE_MONTH) gold else white)
            labelAnnual.setTextColor(if (selectedPlan == Plan.ANNUAL) gold else white)

            priceMonthly.setTextColor(if (selectedPlan == Plan.MONTHLY) gold else white)
            price3Month.setTextColor(if (selectedPlan == Plan.THREE_MONTH) gold else silver)
            priceAnnual.setTextColor(if (selectedPlan == Plan.ANNUAL) gold else silver)
        }

        cardMonthly.setOnClickListener { selectedPlan = Plan.MONTHLY; applySelection(); contactForPlan(selectedPlan) }
        card3Month.setOnClickListener  { selectedPlan = Plan.THREE_MONTH; applySelection(); contactForPlan(selectedPlan) }
        cardAnnual.setOnClickListener  { selectedPlan = Plan.ANNUAL; applySelection(); contactForPlan(selectedPlan) }

        applySelection() // monthly highlighted by default
    }

    private fun appLang(): String = resources.configuration.locales[0].language

    /** Subscription request in the current app language (EN / FR / AR), always including plan, price and Device ID. */
    private fun planMessage(plan: Plan): String {
        val lang = appLang()
        val price = when (plan) {
            Plan.MONTHLY -> "5.99 \u20AC"
            Plan.THREE_MONTH -> "10.99 \u20AC"
            Plan.ANNUAL -> "24.99 \u20AC"
        }
        val id = LicenseManager.getAndroidId(this)
        return when (lang) {
            "fr" -> {
                val name = when (plan) {
                    Plan.MONTHLY -> "Mensuel"
                    Plan.THREE_MONTH -> "3 Mois"
                    Plan.ANNUAL -> "Annuel"
                }
                "Bonjour, je souhaite m'abonner au plan $name ($price). Mon ID d'appareil est : $id"
            }
            "ar" -> {
                val name = when (plan) {
                    Plan.MONTHLY -> "\u0627\u0644\u0628\u0627\u0642\u0629 \u0627\u0644\u0634\u0647\u0631\u064A\u0629"
                    Plan.THREE_MONTH -> "\u0628\u0627\u0642\u0629 3 \u0623\u0634\u0647\u0631"
                    Plan.ANNUAL -> "\u0627\u0644\u0628\u0627\u0642\u0629 \u0627\u0644\u0633\u0646\u0648\u064A\u0629"
                }
                "\u0645\u0631\u062D\u0628\u0627\u064B\u060C \u0623\u0631\u064A\u062F \u0627\u0644\u0627\u0634\u062A\u0631\u0627\u0643 \u0641\u064A $name ($price). \u0645\u0639\u0631\u0641 \u0627\u0644\u062C\u0647\u0627\u0632 \u0627\u0644\u062E\u0627\u0635 \u0628\u064A \u0647\u0648: $id"
            }
            else -> {
                val name = when (plan) {
                    Plan.MONTHLY -> "Monthly Plan"
                    Plan.THREE_MONTH -> "3 Months Plan"
                    Plan.ANNUAL -> "Annual Plan"
                }
                "Hello, I want to subscribe to the $name ($price). My Device ID is: $id"
            }
        }
    }

    /**
     * Copies the ready-made request to the clipboard, then opens Messenger with
     * the same text pre-filled. Messenger's ?text= prefill is ignored on some
     * versions, which is why the clipboard copy always happens first.
     */
    private fun contactForPlan(plan: Plan) {
        val msg = planMessage(plan)
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Subscription request", msg))
        Toast.makeText(this, "Message copied. Paste it in Messenger if it is empty.", Toast.LENGTH_LONG).show()

        if (MESSENGER_PAGE.isBlank()) {
            openFacebook()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.me/$MESSENGER_PAGE?text=" + Uri.encode(msg))))
        } catch (e: Exception) {
            openFacebook()
        }
    }

    /**
     * Dark/gold success dialog shown right after a license key validates.
     * Not cancelable by back/outside-tap -- the only way out is OK, which
     * finishes this screen and drops the user on MainActivity with a clean
     * back stack (so Back from the home screen doesn't return here).
     */
    private fun showSubscriptionSuccessDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_subscription_success, null)
        view.findViewById<TextView>(R.id.tv_success_title).text = s("subscription_success_title")
        view.findViewById<TextView>(R.id.tv_success_sub).text = s("license_activated")

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        view.findViewById<Button>(R.id.btn_success_ok).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
        dialog.show()
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
