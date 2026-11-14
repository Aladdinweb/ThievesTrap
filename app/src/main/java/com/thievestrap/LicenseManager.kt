package com.thievestrap

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.9.10: hybrid online licensing.
 *
 * SERVER IS THE AUTHORITY
 * - Every answer is signed by the Worker (ECDSA P-256 / SHA-256); this app only holds the PUBLIC key.
 * - A signed answer carries a LEASE (default 72 h). After the lease the license stops working until the
 *   app has re-checked online. While online the app re-checks every ~15 minutes, so a revoke, a delete,
 *   or an admin "Unbind" takes effect on this phone within minutes.
 *
 * DEVICE BINDING
 * - The license is bound to a non-exportable EC key generated in the Android Keystore (hardware backed
 *   where the phone supports it, StrongBox first). Every request is signed with that key, so copying the
 *   app data to another phone or emulator does not carry the binding. The Android ID is only a label.
 * - STRICT lock-to-device: a key belongs to ONE phone. Typing it on a second phone is refused by the
 *   server (key_in_use) and the user is told to contact support. Only the admin can free a key
 *   ("Unbind device" in the dashboard); the key then binds to the next phone that activates it, and the
 *   old phone is locked out at its next check. Background checks can never change the binding.
 *
 * REPLAY / TAMPERING
 * - Requests carry a timestamp + random nonce and are signed with the device key; the server rejects
 *   old, duplicated or altered requests. Replies echo the nonce and are bound to this device key.
 *
 * LIMITS (be honest): nothing here stops someone who patches their own APK to skip these checks, and
 * the Keystore proof is not hardware ATTESTATION (that needs Play Integrity / key attestation).
 */
object LicenseManager {

    private const val TAG = "LicenseManager"

    // ---- set these two before building (the push command patches them) ----
    private const val API_URL = "https://thievestrap-license.ferakalaaeddine.workers.dev"
    private const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEkjfjWDCbfWX86mg8Op9wp/n1FTcPjiS4r86Y+sRJ9ih15vb6Hz1cezi9a3KD+5KSjGrU0erfD9dz2aRsGq322A=="

    private const val SOFT_REFRESH_MS = 15L * 60 * 1000           // re-check this often while the app is in use
    private const val RETRY_MS = 5L * 60 * 1000                   // after a failed attempt
    private const val KS_ALIAS = "tt_license_dev_v1"

    // v2.9.15: names of the REMOVED pre-server storage, kept only so purgeLegacyState() can erase it
    private const val OLD_SECURE_PREFS = "tt_secure"
    private const val BACKUP_PREFS = "tt_premium_backup"      // still the file that holds the signed license
    private const val OLD_BACKUP_KEYS_A = "premium_backup"
    private const val OLD_BACKUP_KEYS_B = "license_backup"
    private const val OLD_BACKUP_KEYS_C = "last_version_code"

    // signed-license storage (plain prefs are fine: everything is signature-checked)
    private const val K_PAYLOAD = "lic_payload"
    private const val K_SIG = "lic_sig"
    private const val K_KEY = "lic_key"
    private const val K_OK_AT = "lic_refresh_ok_at"
    private const val K_TRY_AT = "lic_refresh_try_at"
    private const val K_SEEN = "lic_last_seen"
    private const val K_LAST = "lic_last_check"   // "millis|result" of the latest server check, for the diagnostics line

    private const val WORK_NAME = "tt_license_refresh"
    private val refreshing = AtomicBoolean(false)
    private val workerScheduled = AtomicBoolean(false)
    @Volatile private var lastFail = ""
    @Volatile private var cachedDpk: String? = null

    data class LicenseInfo(
        val plan: String,
        val expMillis: Long,          // 0 = lifetime
        val expired: Boolean,
        val leaseExpired: Boolean = false   // true = must be re-checked online before it counts again
    )

    sealed class ActivationResult {
        data class Success(val info: LicenseInfo) : ActivationResult()
        object InvalidKey : ActivationResult()
        /** The key is bound to another phone. Only the admin can unbind it, so the UI says "contact support". */
        object KeyInUse : ActivationResult()
        object Revoked : ActivationResult()
        object Expired : ActivationResult()
        object BadClock : ActivationResult()
        object Network : ActivationResult()
        data class ServerError(val detail: String) : ActivationResult()
        /** The server answered 200 but the reply failed local verification; reason = signature/device/dpk/nonce/... */
        data class BadResponse(val reason: String) : ActivationResult()
        object NotConfigured : ActivationResult()
    }

    private class Reply(val code: Int, val error: String, val payload: String, val sig: String)

    private fun backup(context: Context): SharedPreferences =
        context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

    fun getAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun configured(): Boolean =
        API_URL.startsWith("https://") && !API_URL.contains("YOUR-WORKER") && !PUBLIC_KEY_B64.startsWith("PASTE")

    // ------------------------------------------------------------------ public API

    /** Blocking network call: run it off the main thread. Binds an unbound key to this phone; a key bound elsewhere gives KeyInUse. */
    fun activate(context: Context, rawKey: String): ActivationResult {
        if (!configured()) return ActivationResult.NotConfigured
        val key = rawKey.trim().uppercase()
        if (key.length < 8 || key.length > 64) return ActivationResult.InvalidKey

        val nonce = newNonce()
        val body = buildRequest(context, "activate", key, nonce)
            ?: return ActivationResult.ServerError("keystore")
        val reply = post("/activate", body) ?: return ActivationResult.Network

        if (reply.code in 200..299) {
            val json = verifyAndParse(context, reply.payload, reply.sig)
                ?: return ActivationResult.BadResponse(lastFail)
            if (json.optString("nonce") != nonce) return ActivationResult.BadResponse("nonce")
            store(context, key, reply.payload, reply.sig, json.optLong("iat", 0L))
            val info = getLicenseInfo(context) ?: return ActivationResult.BadResponse("stored:$lastFail")
            return ActivationResult.Success(info)
        }
        return when (reply.error) {
            "invalid_key" -> ActivationResult.InvalidKey
            "key_in_use" -> ActivationResult.KeyInUse
            "revoked" -> ActivationResult.Revoked
            "expired" -> ActivationResult.Expired
            "bad_time" -> ActivationResult.BadClock
            else -> ActivationResult.ServerError("http ${reply.code} ${reply.error}")
        }
    }

    fun isPremium(context: Context): Boolean {
        val info = getLicenseInfo(context) ?: return false
        ensureBackgroundCheck(context.applicationContext)
        maybeRefreshAsync(context.applicationContext, force = false)
        return !info.expired && !info.leaseExpired
    }

    /** Verified license details for the UI, or null when there is none. */
    fun getLicenseInfo(context: Context): LicenseInfo? {
        val b = backup(context)
        val payload = b.getString(K_PAYLOAD, null)
        val sig = b.getString(K_SIG, null)
        if (payload != null) {
            // A stored license that fails verification is tampered: no premium, no fallback of any kind.
            val json = verifyAndParse(context, payload, sig ?: "") ?: return null
            val expMs = json.optLong("exp", 0L) * 1000L
            // v2.9.15: only v2 answers are accepted (lease + device key bound); v1 payloads are rejected in verifyAndParse
            val leaseMs = json.optLong("lease", 0L) * 1000L
            val now = effectiveNow(b)
            return LicenseInfo(
                plan = json.optString("plan", ""),
                expMillis = expMs,
                expired = expMs != 0L && now >= expMs,
                leaseExpired = now >= leaseMs
            )
        }
        return null
    }

    /** Ask the server now (ignores the 15-minute throttle). Safe to call from the UI thread. */
    fun refreshAsync(context: Context, onDone: (() -> Unit)? = null) =
        maybeRefreshAsync(context.applicationContext, force = true, onDone = onDone)

    /** Local reset. The key stays valid on the server and can be activated again on this device. */
    fun revokePremium(context: Context) {
        backup(context).edit()
            .remove(K_PAYLOAD).remove(K_SIG).remove(K_KEY).remove(K_OK_AT).remove(K_TRY_AT)
            .apply()
        try { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) } catch (e: Exception) {}
        workerScheduled.set(false)
    }

    /**
     * v2.9.15: one-time erase of everything the removed pre-server (legacy) path ever stored: the
     * encrypted premium flag, the plain-text premium backup and the plain "tt_fb" fallback flags.
     * Safe to call on every start. A phone that was premium only through that path is Free now and
     * must activate a server key.
     */
    fun purgeLegacyState(context: Context) {
        try {
            val b = backup(context)
            if (b.contains(OLD_BACKUP_KEYS_A) || b.contains(OLD_BACKUP_KEYS_B) || b.contains(OLD_BACKUP_KEYS_C)) {
                b.edit().remove(OLD_BACKUP_KEYS_A).remove(OLD_BACKUP_KEYS_B).remove(OLD_BACKUP_KEYS_C).apply()
            }
            val app = context.applicationContext
            if (app.getSharedPreferences(OLD_SECURE_PREFS, Context.MODE_PRIVATE).all.isNotEmpty())
                app.deleteSharedPreferences(OLD_SECURE_PREFS)
            val fb = app.getSharedPreferences("tt_fb", Context.MODE_PRIVATE)
            if (fb.contains("p") || fb.contains("l")) fb.edit().remove("p").remove("l").apply()
        } catch (e: Exception) { Log.w(TAG, "purgeLegacyState failed", e) }
    }

    // ------------------------------------------------------------------ online re-validation

    private fun maybeRefreshAsync(app: Context, force: Boolean, onDone: (() -> Unit)? = null) {
        if (!configured()) return
        val b = backup(app)
        val key = b.getString(K_KEY, null) ?: return
        val now = System.currentTimeMillis()
        if (!force) {
            if (now - b.getLong(K_OK_AT, 0L) < SOFT_REFRESH_MS) return
            if (now - b.getLong(K_TRY_AT, 0L) < RETRY_MS) return
        }
        if (!refreshing.compareAndSet(false, true)) return
        b.edit().putLong(K_TRY_AT, now).apply()
        Thread {
            try { refresh(app, key) } catch (e: Exception) { Log.w(TAG, "refresh failed", e) }
            finally { refreshing.set(false) }
            try { onDone?.invoke() } catch (e: Exception) { Log.w(TAG, "onDone failed", e) }
        }.start()
    }

    /**
     * Background check every ~15 minutes while the phone has internet, even if the app is never opened.
     * This is what makes a revoke / delete / move reach THIS phone within minutes.
     */
    private fun ensureBackgroundCheck(app: Context) {
        if (!configured() || !workerScheduled.compareAndSet(false, true)) return
        try {
            val req = PeriodicWorkRequest.Builder(RefreshWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) {
            workerScheduled.set(false)
            Log.w(TAG, "could not schedule background check", e)
        }
    }

    internal fun backgroundCheck(context: Context) {
        if (!configured()) return
        val key = backup(context).getString(K_KEY, null) ?: return
        refresh(context, key)
    }

    class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            try { LicenseManager.backgroundCheck(applicationContext) }
            catch (e: Exception) { Log.w(LicenseManager.TAG, "background check failed", e) }
            return Result.success()
        }
    }

    /** Read-only check against the server. It can confirm or deny; it never moves the binding. */
    private fun refresh(context: Context, key: String) {
        val nonce = newNonce()
        val body = buildRequest(context, "check", key, nonce)
        if (body == null) { noteCheck(context, "no device key"); return }
        val reply = post("/check", body)
        if (reply == null) { noteCheck(context, "offline"); return }   // offline: the lease keeps running

        if (reply.code in 200..299) {
            val json = verifyAndParse(context, reply.payload, reply.sig)
            if (json != null && json.optString("nonce") == nonce) {
                store(context, key, reply.payload, reply.sig, json.optLong("iat", 0L))
                noteCheck(context, "ok")
            } else {
                noteCheck(context, "bad reply: " + (if (json == null) lastFail else "nonce"))
            }
            return
        }
        noteCheck(context, if (reply.error.isBlank()) "http ${reply.code}" else "${reply.error} (${reply.code})")
        when (reply.error) {
            // Definitive answers from the server: drop the license NOW.
            "revoked", "invalid_key", "key_in_use" -> {
                Log.w(TAG, "server says ${reply.error}: clearing local license")
                revokePremium(context)
            }
            // expired: enforced locally from the signed payload. Anything else (5xx, bad_time): keep the lease.
            else -> Log.w(TAG, "check answered ${reply.code} ${reply.error}")
        }
    }

    private fun noteCheck(context: Context, result: String) {
        backup(context).edit().putString(K_LAST, "${System.currentTimeMillis()}|$result").apply()
    }

    /** e.g. "14:33 - revoked (403)", or null if this phone never talked to the server. */
    fun lastCheckSummary(context: Context): String? {
        val raw = backup(context).getString(K_LAST, null) ?: return null
        val i = raw.indexOf('|')
        if (i < 0) return null
        val ms = raw.substring(0, i).toLongOrNull() ?: return null
        val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
        return time + " - " + raw.substring(i + 1)
    }

    // ------------------------------------------------------------------ device key + signed requests

    private fun generateDeviceKey() {
        for (strongBox in listOf(true, false)) {
            try {
                val b = KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                kpg.initialize(b.build())
                kpg.generateKeyPair()
                return
            } catch (e: Exception) {
                Log.w(TAG, "device key generation (strongBox=$strongBox) failed: ${e.javaClass.simpleName}")
            }
        }
        throw IllegalStateException("device key generation failed")
    }

    private fun deviceKeyPair(): KeyPair? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(KS_ALIAS)) generateDeviceKey()
        val priv = ks.getKey(KS_ALIAS, null) as PrivateKey
        val pub = ks.getCertificate(KS_ALIAS).publicKey
        KeyPair(pub, priv)
    } catch (e: Exception) {
        Log.e(TAG, "Keystore unavailable", e)
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Short fingerprint of this phone's device key; the server puts it in every signed answer. */
    private fun localDpk(): String? {
        cachedDpk?.let { return it }
        val kp = deviceKeyPair() ?: return null
        val d = sha256Hex(kp.public.encoded).substring(0, 16)
        cachedDpk = d
        return d
    }

    private fun buildRequest(context: Context, action: String, key: String, nonce: String): JSONObject? {
        val kp = deviceKeyPair() ?: return null
        return try {
            val device = getAndroidId(context)
            val ts = System.currentTimeMillis() / 1000
            val msg = listOf("TT2", action, key, device, ts, nonce).joinToString("|")
            val sv = Signature.getInstance("SHA256withECDSA")
            sv.initSign(kp.private)
            sv.update(msg.toByteArray(Charsets.UTF_8))
            val raw = derToP1363(sv.sign())
            JSONObject()
                .put("key", key)
                .put("device", device)
                .put("nonce", nonce)
                .put("ts", ts)
                .put("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
                .put("sig", Base64.encodeToString(raw, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "signing request failed", e)
            null
        }
    }

    // ------------------------------------------------------------------ internals

    private fun store(context: Context, key: String, payload: String, sig: String, serverIat: Long) {
        val b = backup(context)
        val seen = maxOf(b.getLong(K_SEEN, 0L), serverIat * 1000L)   // never let the clock go behind the server
        b.edit()
            .putString(K_PAYLOAD, payload).putString(K_SIG, sig).putString(K_KEY, key)
            .putLong(K_OK_AT, System.currentTimeMillis())
            .putLong(K_SEEN, seen)
            .apply()
    }

    /** Device clock, but never earlier than the latest time seen (blunts clock rollback). */
    private fun effectiveNow(b: SharedPreferences): Long {
        val seen = b.getLong(K_SEEN, 0L)
        val now = maxOf(System.currentTimeMillis(), seen)
        if (now - seen > 10 * 60 * 1000L) b.edit().putLong(K_SEEN, now).apply()
        return now
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    private fun verifyAndParse(context: Context, payloadB64: String, sigB64: String): JSONObject? {
        if (payloadB64.isBlank() || sigB64.isBlank() || PUBLIC_KEY_B64.startsWith("PASTE")) { lastFail = "empty"; return null }
        return try {
            val payload = Base64.decode(payloadB64, Base64.NO_WRAP)
            val der = p1363ToDer(Base64.decode(sigB64, Base64.NO_WRAP))
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)))
            val sv = Signature.getInstance("SHA256withECDSA")
            sv.initVerify(pub)
            sv.update(payload)
            if (!sv.verify(der)) { lastFail = "signature"; return null }
            val json = JSONObject(String(payload, Charsets.UTF_8))
            val v = json.optInt("v")
            if (v != 2) { lastFail = "version"; return null }   // v2.9.15: v1 (no device-key binding) is no longer accepted
            if (json.optString("device") != getAndroidId(context)) { lastFail = "device"; return null }
            val mine = localDpk()
            if (mine == null) { lastFail = "keystore"; return null }
            if (json.optString("dpk") != mine) { lastFail = "dpk"; return null }
            json
        } catch (e: Exception) {
            Log.w(TAG, "verify failed", e)
            lastFail = "decode:" + e.javaClass.simpleName
            null
        }
    }

    // WebCrypto signs ECDSA as raw r||s; Java wants DER.
    private fun p1363ToDer(raw: ByteArray): ByteArray {
        val n = raw.size / 2
        fun enc(src: ByteArray): ByteArray {
            var i = 0
            while (i < src.size - 1 && src[i] == 0.toByte()) i++
            var v = src.copyOfRange(i, src.size)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return v
        }
        val r = enc(raw.copyOfRange(0, n))
        val s = enc(raw.copyOfRange(n, raw.size))
        val len = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, len.toByte(), 0x02, r.size.toByte()) + r +
               byteArrayOf(0x02, s.size.toByte()) + s
    }

    // Java signs ECDSA as DER; WebCrypto on the server wants raw r||s (32 + 32 bytes).
    private fun derToP1363(der: ByteArray): ByteArray {
        var i = 2
        if ((der[1].toInt() and 0x80) != 0) i = 2 + (der[1].toInt() and 0x7f)
        if (der[i] != 0x02.toByte()) throw IllegalArgumentException("bad DER (r)")
        val rl = der[i + 1].toInt() and 0xff
        val r = der.copyOfRange(i + 2, i + 2 + rl)
        i += 2 + rl
        if (der[i] != 0x02.toByte()) throw IllegalArgumentException("bad DER (s)")
        val sl = der[i + 1].toInt() and 0xff
        val s = der.copyOfRange(i + 2, i + 2 + sl)
        fun fix(b: ByteArray): ByteArray {
            var v = b
            while (v.size > 32 && v[0] == 0.toByte()) v = v.copyOfRange(1, v.size)
            return ByteArray(32 - v.size) + v
        }
        return fix(r) + fix(s)
    }

    private fun post(path: String, body: JSONObject): Reply? {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(API_URL + path).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            Reply(code, j.optString("error", ""), j.optString("payload", ""), j.optString("sig", ""))
        } catch (e: Exception) {
            Log.w(TAG, "network error on $path", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

}
