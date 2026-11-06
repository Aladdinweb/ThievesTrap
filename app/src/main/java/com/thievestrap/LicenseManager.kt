package com.thievestrap

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.9.9.9: licenses are issued, stored and validated by a Cloudflare Worker.
 *
 * - The app sends (license key, Device ID, random nonce) over HTTPS.
 * - The Worker replies with a payload (device, plan, expiry, nonce) SIGNED with a
 *   private key that exists only on the server. The app holds only the PUBLIC key.
 * - The signed payload is stored and re-verified on every read, so editing the
 *   stored prefs (or faking a server reply) cannot produce a valid license.
 * - There is no master/admin key in the app any more. Lifetime/admin keys are just
 *   keys generated with plan "lifetime" on the server.
 *
 * Devices that were activated before this version (old local key scheme) are kept
 * as "legacy lifetime" while ALLOW_LEGACY is true. Set it to false in a later
 * release to end that grace period.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"

    // ---- set these two before building (see the setup steps) ----
    private const val API_URL = "https://thievestrap-license.ferakalaaeddine.workers.dev"
    private const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEkjfjWDCbfWX86mg8Op9wp/n1FTcPjiS4r86Y+sRJ9ih15vb6Hz1cezi9a3KD+5KSjGrU0erfD9dz2aRsGq322A=="

    private const val ALLOW_LEGACY = true
    private const val REFRESH_OK_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val REFRESH_RETRY_MS = 15L * 60 * 1000

    // legacy (pre-server) storage, kept only for grandfathered devices
    private const val PREFS_FILE = "tt_secure"
    private const val KEY_PREMIUM = "p"
    private const val KEY_LICENSE = "l"
    private const val BACKUP_PREFS = "tt_premium_backup"
    private const val KEY_BACKUP_PREMIUM = "premium_backup"
    private const val KEY_BACKUP_LICENSE = "license_backup"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

    // signed-license storage (plain prefs are fine: everything is signature-checked)
    private const val K_PAYLOAD = "lic_payload"
    private const val K_SIG = "lic_sig"
    private const val K_KEY = "lic_key"
    private const val K_OK_AT = "lic_refresh_ok_at"
    private const val K_TRY_AT = "lic_refresh_try_at"
    private const val K_SEEN = "lic_last_seen"

    private val refreshing = AtomicBoolean(false)

    data class LicenseInfo(
        val plan: String,
        val expMillis: Long,      // 0 = lifetime
        val legacy: Boolean,
        val expired: Boolean
    )

    sealed class ActivationResult {
        data class Success(val info: LicenseInfo) : ActivationResult()
        object InvalidKey : ActivationResult()
        object KeyInUse : ActivationResult()
        object Revoked : ActivationResult()
        object Expired : ActivationResult()
        object Network : ActivationResult()
        object ServerError : ActivationResult()
        object NotConfigured : ActivationResult()
    }

    private class Reply(val code: Int, val error: String, val payload: String, val sig: String)

    private fun backup(context: Context): SharedPreferences =
        context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

    fun getAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    // ------------------------------------------------------------------ public API

    /** Blocking network call: run it off the main thread. */
    fun activate(context: Context, rawKey: String): ActivationResult {
        if (!API_URL.startsWith("https://") || API_URL.contains("YOUR-WORKER") ||
            PUBLIC_KEY_B64.startsWith("PASTE")) return ActivationResult.NotConfigured
        val key = rawKey.trim().uppercase()
        if (key.length < 8 || key.length > 64) return ActivationResult.InvalidKey

        val nonce = newNonce()
        val reply = post("/activate", JSONObject()
            .put("key", key)
            .put("device", getAndroidId(context))
            .put("nonce", nonce)) ?: return ActivationResult.Network

        if (reply.code in 200..299) {
            val json = verifyAndParse(context, reply.payload, reply.sig)
            if (json == null || json.optString("nonce") != nonce) return ActivationResult.ServerError
            store(context, key, reply.payload, reply.sig)
            val info = getLicenseInfo(context) ?: return ActivationResult.ServerError
            return ActivationResult.Success(info)
        }
        return when (reply.error) {
            "invalid_key" -> ActivationResult.InvalidKey
            "key_in_use" -> ActivationResult.KeyInUse
            "revoked" -> ActivationResult.Revoked
            "expired" -> ActivationResult.Expired
            else -> ActivationResult.ServerError
        }
    }

    fun isPremium(context: Context): Boolean {
        val info = getLicenseInfo(context) ?: return false
        if (!info.legacy) maybeRefreshAsync(context.applicationContext)
        return !info.expired
    }

    /** Verified license details for the UI, or null when there is none. */
    fun getLicenseInfo(context: Context): LicenseInfo? {
        val b = backup(context)
        val payload = b.getString(K_PAYLOAD, null)
        val sig = b.getString(K_SIG, null)
        if (payload != null) {
            // A stored license that fails verification is tampered: no premium, no legacy fallback.
            val json = verifyAndParse(context, payload, sig ?: "") ?: return null
            val expMs = json.optLong("exp", 0L) * 1000L
            val now = effectiveNow(b)
            return LicenseInfo(json.optString("plan", ""), expMs, false, expMs != 0L && now >= expMs)
        }
        if (ALLOW_LEGACY && legacyPremium(context)) return LicenseInfo("legacy", 0L, true, false)
        return null
    }

    /** Local reset. The key stays valid on the server and can be activated again on this device. */
    fun revokePremium(context: Context) {
        try {
            getSecurePrefs(context).edit().putBoolean(KEY_PREMIUM, false).putString(KEY_LICENSE, "").apply()
        } catch (e: Exception) {}
        backup(context).edit()
            .putBoolean(KEY_BACKUP_PREMIUM, false).putString(KEY_BACKUP_LICENSE, "")
            .remove(K_PAYLOAD).remove(K_SIG).remove(K_KEY).remove(K_OK_AT).remove(K_TRY_AT)
            .apply()
    }

    /** Kept for compatibility with existing callers; only meaningful for the legacy path. */
    fun setPremium(context: Context, value: Boolean, license: String = "") {
        try {
            getSecurePrefs(context).edit().putBoolean(KEY_PREMIUM, value).putString(KEY_LICENSE, license).apply()
        } catch (e: Exception) { Log.e(TAG, "setPremium: secure write failed", e) }
        backup(context).edit().putBoolean(KEY_BACKUP_PREMIUM, value).putString(KEY_BACKUP_LICENSE, license).apply()
    }

    // Called from PackageReplacedReceiver / MainActivity. Signed licenses live in plain prefs
    // and survive updates by themselves; this only re-asserts the legacy flag.
    fun reconcileAfterUpdate(context: Context) {
        try {
            val b = backup(context)
            val current = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            } catch (e: Exception) { -1 }
            val last = b.getInt(KEY_LAST_VERSION_CODE, -1)
            if (last != current) {
                if (b.getBoolean(KEY_BACKUP_PREMIUM, false)) {
                    try {
                        getSecurePrefs(context).edit()
                            .putBoolean(KEY_PREMIUM, true)
                            .putString(KEY_LICENSE, b.getString(KEY_BACKUP_LICENSE, "") ?: "")
                            .apply()
                    } catch (e: Exception) { Log.e(TAG, "reconcile: secure write failed", e) }
                }
                b.edit().putInt(KEY_LAST_VERSION_CODE, current).apply()
            }
        } catch (e: Exception) { Log.e(TAG, "reconcileAfterUpdate failed", e) }
    }

    // ------------------------------------------------------------------ server refresh

    private fun maybeRefreshAsync(app: Context) {
        val b = backup(app)
        val key = b.getString(K_KEY, null) ?: return
        val now = System.currentTimeMillis()
        if (now - b.getLong(K_OK_AT, 0L) < REFRESH_OK_INTERVAL_MS) return
        if (now - b.getLong(K_TRY_AT, 0L) < REFRESH_RETRY_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        b.edit().putLong(K_TRY_AT, now).apply()
        Thread {
            try { refresh(app, key) } catch (e: Exception) { Log.w(TAG, "refresh failed", e) }
            finally { refreshing.set(false) }
        }.start()
    }

    private fun refresh(context: Context, key: String) {
        if (!API_URL.startsWith("https://") || API_URL.contains("YOUR-WORKER")) return
        val nonce = newNonce()
        val reply = post("/activate", JSONObject()
            .put("key", key).put("device", getAndroidId(context)).put("nonce", nonce)) ?: return  // offline: keep going
        if (reply.code in 200..299) {
            val json = verifyAndParse(context, reply.payload, reply.sig)
            if (json != null && json.optString("nonce") == nonce) {
                store(context, key, reply.payload, reply.sig)
                backup(context).edit().putLong(K_OK_AT, System.currentTimeMillis()).apply()
            }
        } else if (reply.error == "revoked" || reply.error == "invalid_key") {
            Log.w(TAG, "license ${reply.error} on server, clearing")
            revokePremium(context)
        }
        // "expired" and "key_in_use" need no action: expiry is enforced locally from the signed payload
    }

    // ------------------------------------------------------------------ internals

    private fun store(context: Context, key: String, payload: String, sig: String) {
        backup(context).edit()
            .putString(K_PAYLOAD, payload).putString(K_SIG, sig).putString(K_KEY, key)
            .putLong(K_OK_AT, System.currentTimeMillis())
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
        if (payloadB64.isBlank() || sigB64.isBlank() || PUBLIC_KEY_B64.startsWith("PASTE")) return null
        return try {
            val payload = Base64.decode(payloadB64, Base64.NO_WRAP)
            val der = p1363ToDer(Base64.decode(sigB64, Base64.NO_WRAP))
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)))
            val sv = Signature.getInstance("SHA256withECDSA")
            sv.initVerify(pub)
            sv.update(payload)
            if (!sv.verify(der)) return null
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (json.optInt("v") != 1) return null
            if (json.optString("device") != getAndroidId(context)) return null
            json
        } catch (e: Exception) {
            Log.w(TAG, "verify failed", e)
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

    // ---- legacy (pre-server) premium flag, unchanged behaviour ----

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE, alias, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain tt_fb prefs", e)
            context.getSharedPreferences("tt_fb", Context.MODE_PRIVATE)
        }
    }

    private fun legacyPremium(context: Context): Boolean {
        val secure = try { getSecurePrefs(context).getBoolean(KEY_PREMIUM, false) } catch (e: Exception) { false }
        if (secure) return true
        val b = backup(context)
        if (b.getBoolean(KEY_BACKUP_PREMIUM, false)) {
            try {
                getSecurePrefs(context).edit()
                    .putBoolean(KEY_PREMIUM, true)
                    .putString(KEY_LICENSE, b.getString(KEY_BACKUP_LICENSE, "") ?: "")
                    .apply()
            } catch (e: Exception) { Log.e(TAG, "legacy self-heal failed", e) }
            return true
        }
        return false
    }
}
