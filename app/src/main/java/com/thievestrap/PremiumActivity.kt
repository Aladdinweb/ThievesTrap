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
 * v2.9.9.7: pricing in EUR (5.99 / 10.99 / 24.99), cards forced LTR in the
 * layout, and tapping a pricing card now copies a ready-made subscription
 * request (plan, price, Device ID) to the clipboard and opens Messenger with
 * the same text pre-filled. Activation, revoke, support and the success
 * dialog are unchanged from v2.9.9.6.
 */
class PremiumActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private fun s(key: String) = Strings.get(this, key)

    // Facebook page URL for support
    private val FACEBOOK_PAGE_URL = "https://www.facebook.com/share/1D2RrP4ey2/"
    private val FACEBOOK_APP_URL = "fb://facewebmodal/f?href=$FACEBOOK_PAGE_URL"

    // Messenger page username or numeric Page ID. Blank = fall back to the Facebook page.
    private val MESSENGER_PAGE = ""

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

        setupStatusBadge(isPremium)
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
            findViewById<Button>(R.id.btn_buy).setOnClickListener {
                val key = etKey.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "Enter your license key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                when (LicenseManager.validateKey(this, key)) {
                    LicenseManager.ValidationResult.SUCCESS_DEVICE,
                    LicenseManager.ValidationResult.SUCCESS_MASTER -> {
                        showSubscriptionSuccessDialog()
                    }
                    LicenseManager.ValidationResult.INVALID ->
                        Toast.makeText(
                            this,
                            "${s("license_invalid")}\nDevice ID: $androidId",
                            Toast.LENGTH_LONG
                        ).show()
                }
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
        val lang = resources.configuration.locales[0].language
        return when (lang) {
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

    private fun planMessage(plan: Plan): String {
        val (name, price) = when (plan) {
            Plan.MONTHLY -> "Monthly Plan" to "5.99 \u20AC"
            Plan.THREE_MONTH -> "3 Months Plan" to "10.99 \u20AC"
            Plan.ANNUAL -> "Annual Plan" to "24.99 \u20AC"
        }
        val id = LicenseManager.getAndroidId(this)
        return "Hello, I want to subscribe to the $name ($price). My Device ID is: $id"
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
