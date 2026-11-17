package com.thievestrap

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v2.9.23: the helper side of the offline mesh. Collects beacons heard nearby and forwards them, still
 * encrypted, to the family Worker over this phone's own connection (WorkManager waits for a network).
 *
 * Privacy / abuse limits:
 *  - only the 23 opaque bytes and their routing id are sent; this phone's location, name and the owner's
 *    identity are never part of it (the beacon holds the LOST phone's own fix, which only its pair can open);
 *  - each distinct beacon (id + epoch + sequence) is forwarded once per phone; at most 40 per hour, 20 queued;
 *  - queued beacons older than 24 h are dropped.
 */
object BleRelay {

    private const val TAG = "TT-BleRelay"
    private const val MAX_PENDING = 20
    private const val MAX_PER_HOUR = 40

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tt_ble_relay", Context.MODE_PRIVATE)

    /** True when the beacon is new to this phone and was queued. */
    @Synchronized
    fun offer(ctx: Context, data: ByteArray): Boolean {
        val s = sp(ctx)
        val id = data.copyOfRange(1, 9).joinToString("") { "%02x".format(it) }          // rid + epoch + seq
        val now = System.currentTimeMillis()
        val seen = JSONArray(s.getString("seen", "[]"))
        for (i in 0 until seen.length()) if (seen.getJSONArray(i).getString(0) == id) return false
        val hour = now / 3_600_000L
        val cnt = if (s.getLong("hour", 0L) == hour) s.getInt("count", 0) else 0
        if (cnt >= MAX_PER_HOUR) return false
        // remember (last 120 ids)
        val keep = JSONArray(); val from = maxOf(0, seen.length() - 119)
        for (i in from until seen.length()) keep.put(seen.get(i))
        keep.put(JSONArray().put(id).put(now))
        val pend = JSONArray(s.getString("pending", "[]"))
        val q = JSONArray(); val pf = maxOf(0, pend.length() - (MAX_PENDING - 1))
        for (i in pf until pend.length()) { val o = pend.getJSONObject(i); if (now - o.getLong("ts") < 24 * 3_600_000L) q.put(o) }
        q.put(JSONObject().put("rid", data.copyOfRange(1, 5).joinToString("") { "%02x".format(it) })
            .put("b", Base64.encodeToString(data, Base64.NO_WRAP)).put("ts", now))
        s.edit().putString("seen", keep.toString()).putString("pending", q.toString()).putLong("hour", hour).putInt("count", cnt + 1).apply()
        return true
    }

    fun scheduleFlush(ctx: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<BleFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork("tt_ble_flush", ExistingWorkPolicy.KEEP, req)
        } catch (e: Exception) { Log.w(TAG, "schedule failed: ${e.message}") }
    }

    /** Blocking (network). Returns true when nothing is left to retry. */
    fun flush(ctx: Context): Boolean {
        val s = sp(ctx)
        val pend: JSONArray = synchronized(this) { JSONArray(s.getString("pending", "[]")) }
        if (pend.length() == 0) return true
        var retry = false
        val finished = HashSet<String>()                      // beacons that need no further attempt
        for (i in 0 until pend.length()) {
            val o = pend.getJSONObject(i)
            when (FamilyBridge.reportBle(o.getString("rid"), o.getString("b"))) {
                "retry" -> retry = true                       // offline / rate limited / server busy: keep it queued
                else -> finished.add(o.getString("b"))        // delivered, unknown id (nothing to retry) or refused for good
            }
        }
        synchronized(this) {
            // anything queued while we were uploading stays; only the finished ones go
            val now = JSONArray(s.getString("pending", "[]"))
            val left = JSONArray()
            for (i in 0 until now.length()) { val o = now.getJSONObject(i); if (o.getString("b") !in finished) left.put(o) }
            s.edit().putString("pending", left.toString()).apply()
        }
        return !retry
    }
}

class BleFlushWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result =
        if (BleRelay.flush(applicationContext) || runAttemptCount >= 5) Result.success() else Result.retry()
}
