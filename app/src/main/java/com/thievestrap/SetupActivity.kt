package com.thievestrap

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var pendingPickerTarget = 1

    private val pickContact1 = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                resolvePhone(uri)?.let {
                    findViewById<EditText>(R.id.et_setup_phone).setText(it as CharSequence)
                }
            }
        }
    }

    private val pickContact2 = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && LicenseManager.isPremium(this)) {
            result.data?.data?.let { uri ->
                resolvePhone(uri)?.let {
                    val et = findViewById<EditText>(R.id.et_setup_phone2)
                    et.isFocusable = true
                    et.isFocusableInTouchMode = true
                    et.setText(it as CharSequence)
                }
            }
        }
    }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchPicker(pendingPickerTarget)
        else Toast.makeText(this, "Contacts permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_setup)
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        // Restore saved IMEI
        val savedImei = prefs.getString("imei", "") ?: ""
        if (savedImei.isNotBlank()) {
            findViewById<EditText>(R.id.et_setup_imei).setText(savedImei as CharSequence)
        }

        // IMEI tooltip
        findViewById<TextView>(R.id.tv_imei_hint_setup).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("What is IMEI?")
                .setMessage("Your IMEI is the unique ID of your phone used for police reports.\\n\\nDial *#06# to see it instantly, then copy and paste it here.")
                .setPositiveButton("OK", null).show()
        }

        // IMEI paste button
        try {
            findViewById<ImageButton>(R.id.btn_paste_imei).setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                val digits = text.filter { it.isDigit() }.take(15)
                if (digits.isNotBlank()) {
                    findViewById<EditText>(R.id.et_setup_imei).setText(digits as CharSequence)
                    Toast.makeText(this, "IMEI pasted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No number in clipboard. Dial *#06# first.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {}

        // Contact picker buttons
        val isPremium = LicenseManager.isPremium(this)
        setupPremiumLocks(isPremium)

        try {
            findViewById<ImageButton>(R.id.btn_pick_contact1).setOnClickListener {
                pendingPickerTarget = 1; requestContacts(1)
            }
        } catch (e: Exception) {}
        try {
            findViewById<ImageButton>(R.id.btn_pick_contact2).setOnClickListener {
                if (!isPremium) { showUpgrade(); return@setOnClickListener }
                pendingPickerTarget = 2; requestContacts(2)
            }
        } catch (e: Exception) {}

        // Locked fields show upgrade dialog
        if (!isPremium) {
                try { findViewById<EditText>(R.id.et_setup_phone2).setOnClickListener { showUpgrade() } } catch (e: Exception) {}
        }

        // Finish Setup
        findViewById<Button>(R.id.btn_setup_done).setOnClickListener { attemptFinish() }
    }

    private fun setupPremiumLocks(isPremium: Boolean) {
        try {
            val etPhone2 = findViewById<EditText>(R.id.et_setup_phone2)
            if (isPremium) {
                etPhone2.apply { hint = "Emergency contact 2"; isFocusable = true; isFocusableInTouchMode = true }
            }
        } catch (e: Exception) {}
    }

    private fun showUpgrade() {
        AlertDialog.Builder(this)
            .setTitle("Unlock Full Protection")
            .setMessage("Multiple contacts and recovery features are Premium only.")
            .setPositiveButton("Upgrade") { _, _ -> startActivity(Intent(this, PremiumActivity::class.java)) }
            .setNegativeButton("Not now", null).show()
    }

    private fun requestContacts(target: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED) {
            launchPicker(target)
        } else {
            pendingPickerTarget = target
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun launchPicker(target: Int) {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        if (target == 1) pickContact1.launch(intent) else pickContact2.launch(intent)
    }

    private fun resolvePhone(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER))
                else null
            }
        } catch (e: Exception) { null }
    }

    private fun attemptFinish() {
        val pin = findViewById<EditText>(R.id.et_setup_pin).text.toString().trim()
        val phone = findViewById<EditText>(R.id.et_setup_phone).text.toString().trim()
        val imei = findViewById<EditText>(R.id.et_setup_imei).text.toString().trim()

        if (pin.length < 4) { Toast.makeText(this, "PIN must be at least 4 digits!", Toast.LENGTH_LONG).show(); return }
        if (phone.isBlank()) { Toast.makeText(this, "Emergency contact is required!", Toast.LENGTH_LONG).show(); return }

        prefs.edit()
            .putString("password", pin)
            .putString("phone", phone)
            .putString("imei", imei)
            .putBoolean("alert_failed", true)
            .putBoolean("alert_sim", true)
            .putBoolean("alert_airplane", true)
            .putBoolean("alert_shutdown", true)
            .putBoolean("alert_silent", true)
            .putBoolean("alert_charging", true)
            .putBoolean("location_ping", true)
            .putInt("ping_interval", 5)
            .putInt("failed_threshold", 3)
            .apply()

        if (LicenseManager.isPremium(this)) {
            prefs.edit()
                .putString("phone2", try { findViewById<EditText>(R.id.et_setup_phone2).text.toString().trim() } catch (e: Exception) { "" })
                .apply()
        }

        AlertDialog.Builder(this)
            .setTitle("Thieves Trap Activated!")
            .setMessage("Congratulations! Your phone is now protected. \n\nRemember: Your security PIN is [$pin]. Keep it safe!\n\nTap ARM on the main screen to begin monitoring.")
            .setCancelable(false)
            .setPositiveButton("GO TO HOME") { _, _ ->
                startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                finish()
            }
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(android.graphics.Color.parseColor("#CC0000"))
            }
    }
}
