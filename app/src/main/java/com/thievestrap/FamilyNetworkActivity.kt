package com.thievestrap

import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.0.0 step 1: real pairing through the Family Worker (see FamilyBridge).
 *
 * Two-way by design: every pair is mutual, and each phone decides separately what the other one may do
 * on it (ring / selfies / location).
 *
 * v2.9.18: live map (osmdroid + OpenStreetMap tiles, no API key). START asks the other phone, with an
 * encrypted command, to share its location; it does so only while it is ARMED and allows it. This screen
 * polls the server's single "latest fix" slot every 3 s while it is open and keeps the history only on
 * this phone (FamilyHistory).
 */
class FamilyNetworkActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var sessionStart = 0L
    private var prompted = false           // approval / waiting dialog already shown for this session
    private var dialog: AlertDialog? = null
    private lateinit var tvCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMapCaption: TextView
    private var mapView: MapView? = null
    private var marker: Marker? = null
    private var trail: Polyline? = null
    private val trailPts = ArrayList<GeoPoint>()
    private var viewPair: FamilyBridge.FamilyPair? = null
    private var sessionStartSec = 0L
    private var lastFix: JSONObject? = null
    private var firstCentred = false

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

    private val locTask = object : Runnable {
        override fun run() {
            val pr = viewPair ?: return
            if (!resumed) return
            Thread {
                val ct = FamilyBridge.getLoc(pr.pid)
                val key = FamilyBridge.pairKey(this@FamilyNetworkActivity, pr)
                val plain = if (ct != null && key != null) FamilyBridge.open(key, "loc|" + pr.pid, ct) else null
                runOnUiThread { onLoc(plain?.let { String(it) }) }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        // OpenStreetMap tile policy: identify the app; keep the tile cache inside the app's cache folder
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        setContentView(R.layout.activity_family_network)

        tvCode = findViewById(R.id.tv_pair_code)
        tvStatus = findViewById(R.id.tv_family_status)
        tvCode.text = s("fam_tap_code")
        tvCode.textSize = 22f
        tvMapCaption = findViewById(R.id.tv_map_caption)
        findViewById<TextView>(R.id.tv_preview_note).text = s("fam_live_note")
        findViewById<TextView>(R.id.tv_map_title).text = s("fam_map_title")
        initMap()

        findViewById<Button>(R.id.btn_back_family).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_family_info).setOnClickListener { showInfoDialog() }

        tvCode.setOnClickListener { withName { startCode() } }
        findViewById<Button>(R.id.btn_family_connect).setOnClickListener {
            val typed = findViewById<EditText>(R.id.et_family_code).text.toString()
            if (typed.filter { it.isDigit() }.length != 4) { toast(s("fam_code_prompt")); return@setOnClickListener }
            withName { startJoin(typed) }
        }
        tvStatus.setOnClickListener { showManage() }

        findViewById<View>(R.id.btn_family_start).setOnClickListener { chooseTarget { startViewing(it) } }
        findViewById<View>(R.id.btn_family_stop).setOnClickListener { stopViewing() }
        findViewById<View>(R.id.card_family_history).setOnClickListener { chooseTarget { showHistory(it) } }
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
        mapView?.onResume()
        if (viewPair != null) handler.post(locTask)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(locTask)
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        // leaving the screen for good ends the session on the other phone too (no sharing nobody is watching)
        val pr = viewPair
        if (isFinishing && pr != null) Thread { FamilyBridge.sendCommand(applicationContext, pr, "track_stop") }.start()
        mapView?.onDetach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ live map (v2.9.18)

    private fun initMap() {
        val mv = MapView(this)
        mv.setTileSource(TileSourceFactory.MAPNIK)
        mv.setMultiTouchControls(true)
        // the screen scrolls: let the map take its own drags and pinches
        mv.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        mv.overlays.add(CopyrightOverlay(this))          // required OpenStreetMap attribution
        val lastKnown = FamilyBridge.pairs(this).firstNotNullOfOrNull { FamilyHistory.load(this, it.pid, 1).lastOrNull() }
        if (lastKnown != null) { mv.controller.setZoom(15.0); mv.controller.setCenter(GeoPoint(lastKnown.la, lastKnown.lo)) }
        else { mv.controller.setZoom(3.0); mv.controller.setCenter(GeoPoint(20.0, 0.0)) }
        findViewById<FrameLayout>(R.id.map_container).addView(
            mv, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mapView = mv
        refreshCaption()
    }

    private fun startViewing(pair: FamilyBridge.FamilyPair) {
        Thread {
            val r = FamilyBridge.sendCommand(this, pair, "track_start", 60)
            runOnUiThread {
                if (r != "sent") { toast(errText(r)); return@runOnUiThread }
                viewPair = pair
                sessionStartSec = System.currentTimeMillis() / 1000
                lastFix = null; firstCentred = false
                trailPts.clear(); trail?.setPoints(trailPts)
                toast(String.format(s("fam_track_sent"), pair.name))
                refreshCaption()
                handler.removeCallbacks(locTask); handler.post(locTask)
            }
        }.start()
    }

    private fun stopViewing() {
        val pr = viewPair
        if (pr == null) { toast(s("fam_map_idle")); return }
        Thread { FamilyBridge.sendCommand(this, pr, "track_stop") }.start()
        endViewing()
        toast(s("fam_track_stopped"))
    }

    private fun endViewing() {
        handler.removeCallbacks(locTask)
        viewPair = null
        refreshCaption()
    }

    private fun onLoc(json: String?) {
        val pr = viewPair ?: return
        if (json != null) {
            try {
                val o = JSONObject(json)
                val ts = o.optLong("ts", 0L)
                if (ts >= sessionStartSec - 60) {                 // ignore leftovers of an earlier session
                    if (o.has("st")) {
                        val msg = statusText(o.optString("st"), pr.name)
                        if (msg != null) { toast(msg); endViewing(); return }
                    } else if (o.has("la")) applyFix(pr, o)
                }
            } catch (e: Exception) { /* undecodable: ignore */ }
        }
        refreshCaption()
        handler.postDelayed(locTask, 3000)
    }

    /** Null for "started" (keep waiting), otherwise the reason the session is over. */
    private fun statusText(st: String, name: String): String? = when (st) {
        "not_armed" -> String.format(s("fam_st_not_armed"), name)
        "not_allowed" -> String.format(s("fam_st_not_allowed"), name)
        "stopped" -> String.format(s("fam_st_stopped"), name)
        "expired" -> s("fam_st_expired")
        "disarmed" -> String.format(s("fam_st_disarmed"), name)
        "revoked" -> String.format(s("fam_st_revoked"), name)
        else -> null
    }

    private fun applyFix(pr: FamilyBridge.FamilyPair, o: JSONObject) {
        val mv = mapView ?: return
        if (lastFix != null && o.optLong("ts") <= lastFix!!.optLong("ts")) return     // already shown
        lastFix = o
        val gp = GeoPoint(o.getDouble("la"), o.getDouble("lo"))
        val mk = marker ?: Marker(mv).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = pr.name }
            .also { marker = it; mv.overlays.add(it) }
        mk.position = gp
        val tr = trail ?: Polyline().apply { outlinePaint.color = Color.RED; outlinePaint.strokeWidth = 6f }
            .also { trail = it; mv.overlays.add(0, it) }
        trailPts.add(gp); tr.setPoints(trailPts)
        if (!firstCentred) { mv.controller.setZoom(17.0); mv.controller.animateTo(gp); firstCentred = true }
        else if (!mv.boundingBox.contains(gp)) mv.controller.animateTo(gp)
        mv.invalidate()
        FamilyHistory.add(this, pr.pid, FamilyHistory.Fix(o.optLong("ts"), gp.latitude, gp.longitude, o.optDouble("ac", 0.0).toFloat()))
    }

    private fun refreshCaption() {
        val pr = viewPair
        val o = lastFix
        tvMapCaption.text = when {
            pr == null -> s("fam_map_idle")
            o == null -> String.format(s("fam_map_waiting"), pr.name)
            System.currentTimeMillis() / 1000 - o.optLong("ts") > 90 -> s("fam_map_stale")
            else -> {
                val ago = (System.currentTimeMillis() / 1000 - o.optLong("ts")).coerceAtLeast(0)
                val bt = o.optInt("bt", -1)
                String.format(s("fam_map_updated"), pr.name, ago, o.optDouble("ac", 0.0).toInt(),
                    if (bt >= 0) "$bt%" else "?", s(if (o.optBoolean("mv")) "fam_moving" else "fam_still"))
            }
        }
    }

    // ------------------------------------------------------------------ local history (v2.9.18)

    private fun showHistory(pair: FamilyBridge.FamilyPair) {
        val list = FamilyHistory.load(this, pair.pid, 50).reversed()
        if (list.isEmpty()) { toast(s("fam_hist_empty")); return }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val items = list.map { fmt.format(Date(it.ts * 1000)) + String.format(Locale.US, "  %.5f, %.5f", it.la, it.lo) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(String.format(s("fam_hist_title"), pair.name))
            .setItems(items) { _, i -> showHistoryPoint(list[i]) }
            .setNeutralButton(s("fam_hist_clear")) { _, _ -> FamilyHistory.clear(this, pair.pid); toast(s("fam_hist_cleared")) }
            .setNegativeButton(s("cancel"), null).show()
    }

    @Suppress("DEPRECATION")
    private fun showHistoryPoint(f: FamilyHistory.Fix) {
        mapView?.controller?.animateTo(GeoPoint(f.la, f.lo))
        Thread {
            val addr = try {
                if (Geocoder.isPresent()) Geocoder(this, Locale.getDefault()).getFromLocation(f.la, f.lo, 1)?.firstOrNull()?.getAddressLine(0) else null
            } catch (e: Exception) { null }
            runOnUiThread { toast(addr ?: s("fam_addr_unknown")) }
        }.start()
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

    private fun showInfoDialog() {
        val message = s("fam_info")
        AlertDialog.Builder(this)
            .setTitle(R.string.fn_info_title)
            .setMessage(message)
            .setPositiveButton(R.string.fn_ok, null)
            .show()
    }
}
