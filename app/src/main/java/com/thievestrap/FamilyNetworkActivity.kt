package com.thievestrap

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.util.BoundingBox
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
    private var ownMarker: Marker? = null
    private var ownCb: LocationCallback? = null
    private var ownPoint: GeoPoint? = null
    private var fitted = false
    private var limitedShown = false
    private lateinit var tvMapTitle: TextView
    private val RED = Color.parseColor("#E53935")      // the tracked (target / stolen) phone
    private val GREEN = Color.parseColor("#1DB954")    // this (monitor) phone

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
        tvMapTitle = findViewById(R.id.tv_map_title)
        tvMapTitle.text = s("fam_map_title")
        initMap()
        findViewById<TextView>(R.id.tv_family_notice).text = s("fam_notice_line")
        findViewById<TextView>(R.id.tv_family_privacy_log).apply { text = s("fam_log_row"); setOnClickListener { openPrivacyLog() } }
        if (!getSharedPreferences("tt_family", MODE_PRIVATE).getBoolean("notice_ok", false)) showNotice()

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
        // live channel: commands, locations and photo notices arrive at once; this screen shows pushed locations
        FamilySocket.acquire(this)
        FamilySocket.locListener = { pid, plain ->
            runOnUiThread { val pr = viewPair; if (pr != null && pr.pid == pid && processLoc(pr, plain)) refreshCaption() }
        }
        startOwnLocation()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(locTask)
        mapView?.onPause()
        FamilySocket.locListener = null
        FamilySocket.release()
        stopOwnLocation()
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
                lastFix = null; firstCentred = false; fitted = false; limitedShown = false
                FamilyAudit.add(this, "i_track", pair.name)
                updateLegend()
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
        updateLegend()
        refreshCaption()
    }

    // ------------------------------------------------------------------ markers and own position (v2.9.19)

    /** Round pin: white ring + colour. Red = the tracked phone, green = this phone. */
    private fun pinIcon(color: Int): BitmapDrawable {
        val d = resources.displayMetrics.density
        val size = (30 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, p)
        p.color = color; c.drawCircle(size / 2f, size / 2f, size / 2f - 4f * d, p)
        return BitmapDrawable(resources, bmp)
    }

    private fun updateLegend() {
        val name = viewPair?.name
        if (name == null) { tvMapTitle.text = s("fam_map_title"); return }
        val txt = "\u25CF $name   \u25CF ${s("fam_you")}"
        val sp = SpannableString(txt)
        sp.setSpan(ForegroundColorSpan(RED), 0, 1, 0)
        val i = txt.indexOf("   \u25CF") + 3
        sp.setSpan(ForegroundColorSpan(GREEN), i, i + 1, 0)
        tvMapTitle.text = sp
    }

    /** This phone's own position (green). It stays on this phone: nothing about it is ever sent. */
    private fun startOwnLocation() {
        if (ownCb != null) return
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                 ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { setOwn(it) } }
        }
        ownCb = cb
        try {
            client.lastLocation.addOnSuccessListener { l -> if (l != null) setOwn(l) }
            client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build(), cb, Looper.getMainLooper())
        } catch (e: SecurityException) { ownCb = null }
    }

    private fun stopOwnLocation() {
        ownCb?.let { try { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) } catch (e: Exception) {} }
        ownCb = null
    }

    private fun setOwn(l: android.location.Location) {
        val mv = mapView ?: return
        val gp = GeoPoint(l.latitude, l.longitude)
        ownPoint = gp
        val mk = ownMarker ?: Marker(mv).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = s("fam_you"); icon = pinIcon(GREEN) }
            .also { ownMarker = it; mv.overlays.add(it) }
        mk.position = gp
        mv.invalidate()
        maybeFit()
    }

    /** Once, when both phones are known: zoom so both markers are visible. */
    private fun maybeFit() {
        val mv = mapView ?: return
        val t = lastFix?.let { GeoPoint(it.optDouble("la"), it.optDouble("lo")) } ?: return
        val me = ownPoint ?: return
        if (fitted) return
        fitted = true
        mv.post { try { mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(t, me)).increaseByScale(1.6f), true) } catch (e: Exception) { } }
    }

    private fun onLoc(json: String?) {
        val pr = viewPair ?: return
        if (json != null && !processLoc(pr, json)) return
        refreshCaption()
        // right after START poll every second as a fallback to the live channel, then every 3 s
        handler.postDelayed(locTask, if (System.currentTimeMillis() / 1000 - sessionStartSec < 25) 1000L else 3000L)
    }

    /** Applies one decrypted location/status message. False when the session is over (message shown). */
    private fun processLoc(pr: FamilyBridge.FamilyPair, json: String): Boolean {
        try {
            val o = JSONObject(json)
            val ts = o.optLong("ts", 0L)
            if (ts >= sessionStartSec - 60) {                     // ignore leftovers of an earlier session
                if (o.has("st")) {
                    val st = o.optString("st")
                    if (st == "started_limited") {
                        if (!limitedShown) { limitedShown = true; toast(String.format(s("fam_st_limited"), pr.name)) }
                    } else {
                        val msg = statusText(st, pr.name)
                        if (msg != null) { toast(msg); endViewing(); return false }
                    }
                } else if (o.has("la")) applyFix(pr, o)
            }
        } catch (e: Exception) { /* undecodable: ignore */ }
        return true
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
        val mk = marker ?: Marker(mv).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = pr.name; icon = pinIcon(RED) }
            .also { marker = it; mv.overlays.add(it) }
        mk.position = gp
        val tr = trail ?: Polyline().apply { outlinePaint.color = Color.RED; outlinePaint.strokeWidth = 6f }
            .also { trail = it; mv.overlays.add(0, it) }
        trailPts.add(gp); tr.setPoints(trailPts)
        if (!firstCentred) { firstCentred = true; if (ownPoint == null) { mv.controller.setZoom(17.0); mv.controller.animateTo(gp) } }
        else if (fitted && !mv.boundingBox.contains(gp)) mv.controller.animateTo(gp)
        maybeFit()
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
        if (FamilyHistory.load(this, pair.pid, 1).isEmpty()) { toast(s("fam_hist_empty")); return }
        toast(s("fam_hist_loading"))
        Thread {
            FamilyHistory.resolveMissing(this, pair.pid, 20)          // street names for points saved while offline
            val list = FamilyHistory.load(this, pair.pid, 50).reversed()
            runOnUiThread { if (!isFinishing) showHistoryDialog(pair, list) }
        }.start()
    }

    private fun showHistoryDialog(pair: FamilyBridge.FamilyPair, list: List<FamilyHistory.Fix>) {
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val items = list.map {
            fmt.format(Date(it.ts * 1000)) + "  " +
                (if (it.ad.isNotEmpty()) it.ad else String.format(Locale.US, "%.4f, %.4f \u00B7 %s", it.la, it.lo, s("fam_hist_noaddr")))
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(String.format(s("fam_hist_title"), pair.name))
            .setItems(items) { _, i -> showHistoryPoint(list[i]) }
            .setNeutralButton(s("fam_hist_clear")) { _, _ -> FamilyHistory.clear(this, pair.pid); toast(s("fam_hist_cleared")) }
            .setNegativeButton(s("cancel"), null).show()
    }

    private fun showHistoryPoint(f: FamilyHistory.Fix) {
        mapView?.controller?.animateTo(GeoPoint(f.la, f.lo))
        toast(if (f.ad.isNotEmpty()) f.ad else s("fam_addr_unknown"))
    }

    // ------------------------------------------------------------------ notice and privacy log (v2.9.19)

    private fun showNotice() {
        AlertDialog.Builder(this).setTitle(s("fam_notice_title")).setMessage(s("fam_notice_msg")).setCancelable(false)
            .setPositiveButton(s("fam_notice_accept")) { _, _ ->
                getSharedPreferences("tt_family", MODE_PRIVATE).edit().putBoolean("notice_ok", true).apply()
            }
            .setNegativeButton(s("fam_notice_leave")) { _, _ -> finish() }.show()
    }

    /** The log needs the security PIN: a thief holding an unlocked phone cannot read it, and nobody can clear it. */
    private fun openPrivacyLog() {
        val et = EditText(this).apply {
            hint = s("fam_log_pin_title")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this).setTitle(s("fam_log_title")).setView(et)
            .setPositiveButton(s("fam_open")) { _, _ ->
                val saved = getSharedPreferences("tt_prefs", MODE_PRIVATE).getString("password", "") ?: ""
                if (saved.isNotEmpty() && et.text.toString().trim() == saved) showPrivacyLog() else toast(s("wrong_pin"))
            }
            .setNegativeButton(s("cancel"), null).show()
    }

    private fun showPrivacyLog() {
        val list = FamilyAudit.load(this, 100)
        if (list.isEmpty()) { toast(s("fam_log_empty")); return }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val items = list.map { fmt.format(Date(it.ts * 1000)) + "  " + logText(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(s("fam_log_title")).setItems(items, null)
            .setPositiveButton(s("fam_ok_short"), null).show()
    }

    private fun reasonText(x: String) = when (x) {
        "stopped" -> s("fam_rs_stopped"); "expired" -> s("fam_rs_expired"); "disarmed" -> s("fam_rs_disarmed")
        "revoked" -> s("fam_rs_revoked"); "not_armed" -> s("fam_rs_not_armed"); "not_allowed" -> s("fam_rs_not_allowed")
        else -> s("fam_rs_ended")
    }

    private fun logText(e: FamilyAudit.Entry): String = when (e.ev) {
        "track_on" -> String.format(s("fam_ev_track_on"), e.name)
        "track_off" -> String.format(s("fam_ev_track_off"), e.name, reasonText(e.x))
        "track_refused" -> String.format(s("fam_ev_track_refused"), e.name, reasonText(e.x))
        "ring" -> String.format(s("fam_ev_ring"), e.name)
        "ring_refused" -> String.format(s("fam_ev_ring_refused"), e.name)
        "selfie_sent" -> String.format(s("fam_ev_selfie"), e.name)
        "i_track" -> String.format(s("fam_ev_i_track"), e.name)
        "i_ring" -> String.format(s("fam_ev_i_ring"), e.name)
        else -> e.ev
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
        FamilySocket.ensure(this)
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
            .setPositiveButton(s("fam_save")) { _, _ ->
                FamilyBridge.setGrants(this, pair.pid, checked[0], checked[1], checked[2])
                if (checked[2]) BackgroundLocation.promptIfNeeded(this, true)      // "Allow all the time" is needed to keep sharing after restarts
            }
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
                    if (r == "sent" && i == 0) FamilyAudit.add(this, "i_ring", pair.name)
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
