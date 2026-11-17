package com.thievestrap

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * v2.9.23: Offline BLE mesh (find a phone that has NO internet), in two roles that every install plays:
 *
 *  LOST side  - an ARMED phone that loses internet for [OFFLINE_DELAY_MS] starts [BleBeaconService]: it takes GPS
 *               fixes (GPS needs no internet) and broadcasts them, encrypted with the pair key ([BleCrypto]), as
 *               rotating Bluetooth LE beacons. Only pairs the owner granted "See my location" to get a beacon.
 *               While online, the phone registers its upcoming rotating ids with the family Worker (every 6 h).
 *  HELPER side - every phone with the mesh switch ON listens (filtered, low power, PendingIntent scan that also
 *               works with the app closed) for those beacons and forwards each one it hears, still encrypted and
 *               WITHOUT adding its own location, over its own connection ([BleRelay]). The Worker hands it to the
 *               family phone that owns the pair.
 *
 * Needs the Nearby devices (Bluetooth scan / advertise) permission on Android 12+.
 */
object BleMesh {

    private const val TAG = "TT-BleMesh"
    const val COMPANY_ID = 0xFFFF                 // Bluetooth SIG "reserved for testing"; the magic version byte + MAC tell ours apart
    const val ACTION_SCAN = "com.thievestrap.BLE_SCAN_RESULT"
    const val OFFLINE_DELAY_MS = 60_000L          // internet must be gone this long before beaconing starts
    private const val REG_EVERY_MS = 6 * 3600_000L
    private const val REG_EPOCHS = 144            // 36 hours of 15-minute ids
    const val PERM_REQ = 7712

    private val handler = Handler(Looper.getMainLooper())
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var online = true
    private var pending: Runnable? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tt_ble", Context.MODE_PRIVATE)
    private fun armed(ctx: Context) = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getBoolean("running", false)

    // ------------------------------------------------------------------ switch + permissions

    fun enabled(ctx: Context) = sp(ctx).getBoolean("mesh_on", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("mesh_on", on).apply()
        if (on) syncScan(ctx) else { stopScan(ctx); BleBeaconService.stop(ctx) }
    }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun canScan(ctx: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(ctx, Manifest.permission.BLUETOOTH_SCAN)
        else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun canAdvertise(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE)
    fun permissionsOk(ctx: Context) = canScan(ctx) && canAdvertise(ctx)

    /** Asks for Nearby devices (Android 12+). Without [force] it asks at most 3 times in total (no nagging). */
    fun promptIfNeeded(act: Activity, force: Boolean = false) {
        if (permissionsOk(act) || act.isFinishing || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val n = sp(act).getInt("perm_prompts", 0)
        if (!force && n >= 3) return
        sp(act).edit().putInt("perm_prompts", n + 1).apply()
        ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), PERM_REQ)
    }

    private fun adapter(ctx: Context): BluetoothAdapter? =
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter } catch (e: Exception) { null }

    // ------------------------------------------------------------------ helper side: background scan

    private fun scanIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx.applicationContext, 4217,
            Intent(ctx.applicationContext, BleScanReceiver::class.java).setAction(ACTION_SCAN), flags)
    }

    private fun scanner(ctx: Context): BluetoothLeScanner? = adapter(ctx)?.takeIf { it.isEnabled }?.bluetoothLeScanner

    /** Starts (or restarts) / stops the listening scan to match the switch, the permission and Bluetooth state. Cheap and idempotent. */
    @Suppress("MissingPermission")
    fun syncScan(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (!enabled(app) || !canScan(app)) { stopScan(app); return }
            val sc = scanner(app) ?: return
            val filter = ScanFilter.Builder().setManufacturerData(COMPANY_ID, byteArrayOf(BleCrypto.VER.toByte()), byteArrayOf(0xFF.toByte())).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build()
            sc.startScan(listOf(filter), settings, scanIntent(app))
        } catch (e: Exception) { Log.w(TAG, "scan sync failed: ${e.message}") }
    }

    @Suppress("MissingPermission")
    fun stopScan(ctx: Context) {
        try { scanner(ctx)?.stopScan(scanIntent(ctx)) } catch (e: Exception) { }
    }

    /** 15-minute safety net: re-asserts the scan (reboot / Bluetooth toggled) and refreshes the registered ids. */
    fun schedule(ctx: Context) {
        try {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("tt_ble_sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BleSyncWorker>(15, TimeUnit.MINUTES).build())
        } catch (e: Exception) { Log.w(TAG, "schedule failed: ${e.message}") }
    }

    // ------------------------------------------------------------------ lost side: go offline -> beacon

    /** Called by MonitorService while ARMED. */
    @Synchronized
    fun startOfflineWatch(ctx: Context) {
        val app = ctx.applicationContext
        syncScan(app)
        if (netCb != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { evaluate(app) }
            override fun onLost(network: Network) { evaluate(app) }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { evaluate(app) }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (e: Exception) { Log.w(TAG, "network callback failed: ${e.message}") }
        evaluate(app)
    }

    @Synchronized
    fun stopOfflineWatch(ctx: Context) {
        val app = ctx.applicationContext
        netCb?.let { try { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (e: Exception) {} }
        netCb = null
        pending?.let { handler.removeCallbacks(it) }; pending = null
        BleBeaconService.stop(app)
    }

    private fun hasInternet(app: Context): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (e: Exception) { true }

    @Synchronized
    private fun evaluate(app: Context) {
        val now = hasInternet(app)
        if (now == online && (now || pending != null)) return       // nothing changed (offline and the timer already runs)
        online = now
        pending?.let { handler.removeCallbacks(it) }; pending = null
        if (now) {
            BleBeaconService.stop(app)
            Thread { try { registerIdsIfDue(app, false) } catch (e: Exception) { Log.w(TAG, "register failed: ${e.message}") } }.start()
        } else {
            val r = Runnable { pending = null; if (!online && shouldBeacon(app)) BleBeaconService.start(app) }
            pending = r
            handler.postDelayed(r, OFFLINE_DELAY_MS)
        }
    }

    /** All conditions for broadcasting: armed, offline, Bluetooth on, permission, and a pair that may see my location. */
    fun shouldBeacon(ctx: Context): Boolean =
        enabled(ctx) && armed(ctx) && permissionsOk(ctx) && adapter(ctx)?.isEnabled == true && FamilyBridge.pairs(ctx).any { it.location }

    // ------------------------------------------------------------------ rotating ids

    /** Random per-install salt: makes my rotating ids unrelated to anyone else's, even inside the same pair. */
    fun salt(ctx: Context): ByteArray {
        val s = sp(ctx).getString("salt", null)
        if (s != null) return android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(ctx).edit().putString("salt", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)).apply()
        return b
    }

    /** Blocking (network). Registers the next 36 h of ids for every pair that may see my location. */
    fun registerIdsIfDue(ctx: Context, force: Boolean) {
        val app = ctx.applicationContext
        val pairs = FamilyBridge.pairs(app).filter { it.location }
        val sig = pairs.joinToString(",") { it.pid }
        val last = sp(app).getLong("reg_at", 0L)
        if (!force && sig == sp(app).getString("reg_sig", "") && System.currentTimeMillis() - last < REG_EVERY_MS) return
        if (pairs.isEmpty()) { sp(app).edit().putString("reg_sig", "").apply(); return }
        val salt = salt(app)
        val e0 = BleCrypto.epochOf(System.currentTimeMillis() / 1000)
        var allOk = true
        for (pr in pairs) {
            val key = FamilyBridge.pairKey(app, pr) ?: continue
            val rids = (0 until REG_EPOCHS).map { BleCrypto.ridHex(key, salt, (e0 + it) and 0xFFFFFF) }
            if (!FamilyBridge.registerBle(pr.pid, rids)) allOk = false
        }
        if (allOk) sp(app).edit().putLong("reg_at", System.currentTimeMillis()).putString("reg_sig", sig).apply()
    }
}

class BleSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val app = applicationContext
        try {
            BleMesh.syncScan(app)
            BleRelay.flush(app)
            BleMesh.registerIdsIfDue(app, false)
        } catch (e: Exception) { Log.w("TT-BleSync", "sync failed: ${e.message}") }
        return Result.success()
    }
}
