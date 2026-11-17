package com.thievestrap

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v2.9.23: receives the results of the filtered background BLE scan ([BleMesh.syncScan]) even when the app is
 * closed. Anything that looks like a Thieves Trap beacon goes to [BleRelay]; the receiver cannot (and does not
 * try to) read it, it only passes the 23 opaque bytes on.
 */
class BleScanReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BleMesh.ACTION_SCAN) return
        try {
            val list = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: return
            var offered = false
            for (r in list) {
                val data = r.scanRecord?.getManufacturerSpecificData(BleMesh.COMPANY_ID) ?: continue
                if (data.size == BleCrypto.LEN && data[0].toInt() == BleCrypto.VER && BleRelay.offer(context, data)) offered = true
            }
            if (offered) BleRelay.scheduleFlush(context)
        } catch (e: Exception) { Log.w("TT-BleScan", "scan result error: ${e.message}") }
    }
}
