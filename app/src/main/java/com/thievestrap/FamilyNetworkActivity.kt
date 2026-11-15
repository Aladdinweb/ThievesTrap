package com.thievestrap

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * v3.0.0 step 1: real pairing through the Family Worker (see FamilyBridge).
 *
 * Two-way by design: every pair is mutual, and each phone decides separately what the other one may do
 * on it (ring / selfies / location). Live tracking, ring and selfie relay are still "coming soon"
 * (steps 2 to 4), so those buttons keep showing the dialog.
 */
class FamilyNetworkActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var sessionStart = 0L
    private var prompted = false           // approval / waiting dialog already shown for this session
    private var dialog: AlertDialog? = null
    private lateinit var tvCode: TextView
    private lateinit var tvStatus: TextView

    private fun s(key: String) = Strings.get(this, key)

    private val pollTask = object : Runnable {
        override fun run() {
            val sess = FamilyBridge.session
            if (sess == null || sess.done || !resumed) return
            if (System.currentTimeMillis() - sessionStart > 10 * 60 * 1000L) { endSession(); return }
            Thread {
                val st = FamilyBridge.poll(this@FamilyNetworkActivity)
                runOnUiThread { onPoll(st) }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_family_network)

        tvCode = findViewById(R.id.tv_pair_code)
        tvStatus = findViewById(R.id.tv_family_status)
        tvCode.text = s("fam_tap_code")
        tvCode.textSize = 22f

        findViewById<Button>(R.id.btn_back_family).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_family_info).setOnClickListener { showInfoDialog() }

        tvCode.setOnClickListener { withName { startCode() } }
        findViewById<Button>(R.id.btn_family_connect).setOnClickListener {
            val typed = findViewById<EditText>(R.id.et_family_code).text.toString()
            if (typed.filter { it.isDigit() }.length != 4) { toast(s("fam_code_prompt")); return@setOnClickListener }
            withName { startJoin(typed) }
        }
        tvStatus.setOnClickListener { showManage() }

        val comingSoon = View.OnClickListener { showComingSoon() }
        intArrayOf(R.id.btn_family_start, R.id.btn_family_stop, R.id.card_family_history)
            .forEach { findViewById<View>(it).setOnClickListener(comingSoon) }
        findViewById<View>(R.id.btn_family_alarm).setOnClickListener { chooseTarget { showRing(it) } }
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        renderStatus()
        Thread {
            val gone = FamilyBridge.refresh(this)
            if (gone.isNotEmpty()) runOnUiThread { toast(s("fam_unpaired_remote")); renderStatus() }
        }.start()
        if (FamilyBridge.session != null) handler.post(pollTask)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(pollTask)
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ pairing

    private fun withName(then: () -> Unit) {
        if (FamilyBridge.myName(this).isNotEmpty()) { then(); return }
        val et = EditText(this).apply { hint = s("fam_name_hint"); inputType = InputType.TYPE_CLASS_TEXT; setPadding(48, 32, 48, 16) }
        AlertDialog.Builder(this).setTitle(s("fam_name_title")).setView(et)
            .setPositiveButton(s("fam_save")) { _, _ ->
                val n = et.text.toString().trim()
                if (n.length >= 2) { FamilyBridge.setMyName(this, n); then() } else toast(s("fam_name_hint"))
            }
            .setNegativeButton(s("cancel"), null).show()
    }

    private fun startCode() {
        endSession()
        tvCode.text = "…"
        Thread {
            val r = FamilyBridge.openCode(this)
            runOnUiThread {
                if (r.ok) {
                    tvCode.text = "TRAP-" + r.json!!.optString("code")
                    toast(s("fam_code_expires"))
                    beginPolling()
                } else { tvCode.text = s("fam_tap_code"); toast(errText(r.error)) }
            }
        }.start()
    }

    private fun startJoin(typed: String) {
        endSession()
        Thread {
            val r = FamilyBridge.join(this, typed)
            runOnUiThread {
                if (!r.ok) { toast(errText(r.error)); return@runOnUiThread }
                val sess = FamilyBridge.session ?: return@runOnUiThread
                beginPolling()
                prompted = true
                showDialog(AlertDialog.Builder(this)
                    .setTitle(String.format(s("fam_waiting"), sess.peerName ?: ""))
                    .setMessage(String.format(s("fam_sas_msg"), sess.sas ?: ""))
                    .setCancelable(false)
                    .setNegativeButton(s("cancel")) { _, _ -> endSession() })
            }
        }.start()
    }

    private fun beginPolling() {
        prompted = false
        sessionStart = System.currentTimeMillis()
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, 2500)
    }

    private fun onPoll(status: String) {
        val sess = FamilyBridge.session ?: return
        when (status) {
            "pending" -> {
                if (sess.owner && !prompted && sess.peerName != null && sess.sas != null) {
                    prompted = true
                    showDialog(AlertDialog.Builder(this)
                        .setTitle(String.format(s("fam_wants_pair"), sess.peerName))
                        .setMessage(String.format(s("fam_sas_msg"), sess.sas))
                        .setCancelable(false)
                        .setPositiveButton(s("fam_allow")) { _, _ -> decide(true) }
                        .setNegativeButton(s("fam_deny")) { _, _ -> decide(false) })
                }
                handler.postDelayed(pollTask, 3000)
            }
            "open" -> handler.postDelayed(pollTask, 3000)
            "approved" -> finishPairing(sess.peerName ?: "")
            "denied" -> { dialog?.dismiss(); toast(s("fam_denied")); endSession() }
            "offline", "error" -> handler.postDelayed(pollTask, 5000)
            else -> { dialog?.dismiss(); endSession() }        // invalid_code / expired
        }
    }

    private fun decide(accept: Boolean) {
        Thread {
            val st = FamilyBridge.decide(this, accept)
            runOnUiThread {
                when (st) {
                    "approved" -> finishPairing(FamilyBridge.session?.peerName ?: "")
                    "denied" -> endSession()
                    else -> { toast(s("fam_err_generic")); endSession() }
                }
            }
        }.start()
    }

    private fun finishPairing(peer: String) {
        dialog?.dismiss()
        val pair = FamilyBridge.pairs(this).maxByOrNull { it.created }
        endSession()
        renderStatus()
        FamilyRelay.schedule(this)
        toast(String.format(s("fam_paired_ok"), peer))
        if (pair != null) showGrants(pair)
    }

    private fun endSession() {
        handler.removeCallbacks(pollTask)
        FamilyBridge.cancelSession()
        prompted = false
        tvCode.text = s("fam_tap_code")
    }

    // ------------------------------------------------------------------ permissions and management

    private fun showGrants(pair: FamilyBridge.FamilyPair) {
        val items = arrayOf(s("fam_perm_ring"), s("fam_perm_selfie"), s("fam_perm_location"))
        val checked = booleanArrayOf(pair.ring, pair.selfie, pair.location)
        AlertDialog.Builder(this)
            .setTitle(String.format(s("fam_perm_title"), pair.name))
            .setMultiChoiceItems(items, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton(s("fam_save")) { _, _ -> FamilyBridge.setGrants(this, pair.pid, checked[0], checked[1], checked[2]) }
            .show()
    }

    private fun showManage() {
        val list = FamilyBridge.pairs(this)
        if (list.isEmpty()) { toast(s("fam_no_pairs")); return }
        AlertDialog.Builder(this).setTitle(s("fam_manage_title"))
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> managePair(list[i]) }
            .setNegativeButton(s("cancel"), null).show()
    }

    private fun managePair(pair: FamilyBridge.FamilyPair) {
        AlertDialog.Builder(this).setTitle(pair.name)
            .setItems(arrayOf(s("fam_manage_perms"), s("fam_unpair"))) { _, i ->
                if (i == 0) showGrants(pair) else confirmUnpair(pair)
            }.setNegativeButton(s("cancel"), null).show()
    }

    private fun confirmUnpair(pair: FamilyBridge.FamilyPair) {
        AlertDialog.Builder(this).setTitle(String.format(s("fam_unpair_title"), pair.name))
            .setMessage(s("fam_unpair_msg"))
            .setPositiveButton(s("fam_unpair")) { _, _ ->
                Thread { FamilyBridge.unpair(this, pair.pid); runOnUiThread { renderStatus(); FamilyRelay.schedule(this) } }.start()
            }
            .setNegativeButton(s("cancel"), null).show()
    }

    // ------------------------------------------------------------------ ring (v2.9.17)

    private fun chooseTarget(then: (FamilyBridge.FamilyPair) -> Unit) {
        val list = FamilyBridge.pairs(this)
        when {
            list.isEmpty() -> toast(s("fam_no_pairs"))
            list.size == 1 -> then(list[0])
            else -> AlertDialog.Builder(this).setTitle(s("fam_manage_title"))
                .setItems(list.map { it.name }.toTypedArray()) { _, i -> then(list[i]) }
                .setNegativeButton(s("cancel"), null).show()
        }
    }

    private fun showRing(pair: FamilyBridge.FamilyPair) {
        AlertDialog.Builder(this).setTitle(String.format(s("fam_ring_title"), pair.name))
            .setItems(arrayOf(s("fam_ring_now"), s("fam_ring_stop"))) { _, i ->
                val type = if (i == 0) "ring" else "stop"
                Thread {
                    val r = FamilyBridge.sendCommand(this, pair, type)
                    runOnUiThread { toast(if (r == "sent") (if (i == 0) s("fam_ring_sent") else s("fam_ring_stop_sent")) else errText(r)) }
                }.start()
            }.setNegativeButton(s("cancel"), null).show()
    }

    // ------------------------------------------------------------------ helpers

    private fun renderStatus() {
        val names = FamilyBridge.pairs(this).joinToString(", ") { it.name }
        tvStatus.text = if (names.isEmpty()) getString(R.string.fn_status_disconnected)
                        else String.format(s("fam_paired_status"), names)
    }

    private fun showDialog(b: AlertDialog.Builder) { dialog?.dismiss(); dialog = b.show() }

    private fun errText(code: String) = when (code) {
        "invalid_code" -> s("fam_err_code")
        "rate_limited" -> s("fam_err_rate")
        "offline", "http_0" -> s("fam_err_offline")
        "busy" -> s("fam_err_busy")
        "self" -> s("fam_err_self")
        else -> s("fam_err_generic")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

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
