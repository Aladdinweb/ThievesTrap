package com.thievestrap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * v2.9.23: foreground service (foregroundServiceType="location") that broadcasts the offline BLE beacon while an
 * ARMED phone has no internet. It
 *   - reads GPS every ~60 s (works without any network) and makes one encrypted [BleCrypto] beacon per pair that
 *     may see this phone's location (a beacon is made right away from the last known fix, marked "older than 15 min"
 *     if it is that old),
 *   - advertises it as non-connectable BLE manufacturer data; with several pairs it rotates between them every 8 s,
 *   - stops itself when internet returns, the phone is disarmed, Bluetooth goes off or nobody may see the location.
 * Nothing here talks to a server: the phones that overhear the beacon do that (BleScanReceiver / BleRelay).
 */
class BleBeaconService : Service() {

    private val TAG = "TT-BleBeacon"
    private val handler = Handler(Looper.getMainLooper())
    private var cb: LocationCallback? = null
    private var advCb: AdvertiseCallback? = null
    private var beacons: List<ByteArray> = emptyList()
    private var rot = 0
    private var seq = 0
    private var lastEpoch = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (!BleMesh.shouldBeacon(this)) { stopSelf(); return START_NOT_STICKY }
        if (cb == null) startLocation()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cb?.let { try { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) } catch (e: Exception) {} }
        cb = null
        stopAdvertising()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ location -> beacons

    private fun startLocation() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        val c = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { rebuild(it) } }
        }
        cb = c
        try {
            client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L).setMinUpdateIntervalMillis(30_000L).build(), c, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { l -> if (l != null && beacons.isEmpty()) rebuild(l) }     // something to say straight away
        } catch (e: SecurityException) { Log.w(TAG, "no location permission"); stopSelf() }
    }

    private fun rebuild(loc: Location) {
        if (!BleMesh.shouldBeacon(this)) { stopSelf(); return }
        val now = System.currentTimeMillis() / 1000
        val epoch = BleCrypto.epochOf(now)
        if (epoch != lastEpoch) { lastEpoch = epoch; seq = 0 } else seq = (seq + 1) and 0xFF     // never reuse (epoch, seq)
        val salt = BleMesh.salt(this)
        val bt = try { (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { 50 }
        val list = ArrayList<ByteArray>()
        for (pr in FamilyBridge.pairs(this).filter { it.location }) {
            val key = FamilyBridge.pairKey(this, pr) ?: continue
            try { list.add(BleCrypto.seal(key, salt, now, seq, loc.latitude, loc.longitude, loc.accuracy, loc.time / 1000, bt)) } catch (e: Exception) { Log.w(TAG, "seal failed: ${e.message}") }
        }
        beacons = list
        if (list.isNotEmpty()) { rot = 0; advertise(list[0]); handler.removeCallbacks(rotate); if (list.size > 1) handler.postDelayed(rotate, 8_000L) }
        updateNotification()
    }

    private val rotate = object : Runnable {
        override fun run() {
            val l = beacons
            if (l.size > 1) { rot = (rot + 1) % l.size; advertise(l[rot]); handler.postDelayed(this, 8_000L) }
        }
    }

    // ------------------------------------------------------------------ advertising

    @Suppress("MissingPermission")
    private fun advertise(beacon: ByteArray) {
        try {
            val adv = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeAdvertiser ?: return
            stopAdvertising()
            val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).setTimeout(0).build()
            val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addManufacturerData(BleMesh.COMPANY_ID, beacon).build()           // 4 + 23 bytes: fits one legacy packet
            val c = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed: $errorCode") }
            }
            advCb = c
            adv.startAdvertising(settings, data, c)
        } catch (e: Exception) { Log.w(TAG, "advertise error: ${e.message}") }
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        val c = advCb ?: return
        advCb = null
        try { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeAdvertiser?.stopAdvertising(c) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
        true
    } catch (e: Exception) { Log.e(TAG, "startForeground failed: ${e.message}"); false }

    private fun updateNotification() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) } catch (e: Exception) {}
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Offline mode", NotificationManager.IMPORTANCE_LOW))
        // v2.9.24: this service also runs as an automatic fallback on a weak/dropping (but technically
        // "connected") link, not only when fully offline -- say which one it is.
        val weak = BleMesh.isUnstable(this)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(Strings.get(this, if (weak) "fam_ble_notif_title_weak" else "fam_ble_notif_title"))
            .setContentText(Strings.get(this, if (weak) "fam_ble_notif_text_weak" else "fam_ble_notif_text"))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    companion object {
        private const val CHANNEL = "tt_ble_beacon"
        private const val NOTIF_ID = 7901

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, BleBeaconService::class.java)) }
            catch (e: Exception) { Log.w("TT-BleBeacon", "could not start: ${e.message}") }
        }
        fun stop(ctx: Context) {
            try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, BleBeaconService::class.java)) } catch (e: Exception) {}
        }
    }
}
