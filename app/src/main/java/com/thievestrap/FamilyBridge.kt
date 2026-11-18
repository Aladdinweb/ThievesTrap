package com.thievestrap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v3.0.0 step 1: Family Bridge client (pairing only).
 *
 * - Own Keystore signing key `tt_family_dev_v1` (separate from the license key); every request is signed.
 * - Pairing: ephemeral ECDH P-256 keys travel through the Worker; both phones derive the SAME pair key and
 *   the SAME 6-digit verification code (SAS) from them. The user compares the codes on both screens, so a
 *   Worker (or anyone relaying) that swapped keys would produce different codes.
 * - The pair key is wrapped by a non-exportable Keystore AES key before it is stored.
 * - Permissions (what the other phone may do here) are stored locally; later steps refuse any command the
 *   peer was not granted.
 * All network functions are BLOCKING: call them off the main thread.
 */
object FamilyBridge {

    const val API_URL = "https://thievestrap-family.ferakalaaeddine.workers.dev"
    private const val TAG = "FamilyBridge"
    private const val SIGN_ALIAS = "tt_family_dev_v1"
    private const val WRAP_ALIAS = "tt_family_wrap_v1"
    private const val PREFS = "tt_family"
    const val MAX_PAIRS = 5

    /** `peerName` is what the other phone calls itself; `nick` is MY own friendly name for it (v2.9.20). `name` is what we show. */
    class FamilyPair(
        val pid: String, val peerName: String, val keyWrapped: String,
        var ring: Boolean, var selfie: Boolean, var location: Boolean, val created: Long, var nick: String = ""
    ) {
        val name: String get() = nick.ifEmpty { peerName }
    }

    /** In-memory state of one pairing in progress (lost if the process dies: just start again). */
    class Session(
        val owner: Boolean, val code: String, val ephPriv: java.security.PrivateKey, val ephPub: String,
        val myName: String
    ) {
        var peerEpk: String? = null
        var peerName: String? = null
        @Volatile var sas: String? = null
        var done: Boolean = false
    }
    @Volatile var session: Session? = null

    class Reply(val http: Int, val json: JSONObject?) {
        val ok get() = json?.optBoolean("ok", false) == true
        val error get() = json?.optString("error", "") ?: if (http == 0) "offline" else "http_$http"
    }

    // ------------------------------------------------------------------ names and storage

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun myName(ctx: Context): String = p(ctx).getString("my_name", "") ?: ""
    fun setMyName(ctx: Context, n: String) { p(ctx).edit().putString("my_name", n.trim().take(24)).apply() }

    fun pairs(ctx: Context): List<FamilyPair> {
        val arr = try { JSONArray(p(ctx).getString("pairs", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            FamilyPair(o.optString("pid"), o.optString("name"), o.optString("key"),
                o.optBoolean("ring", true), o.optBoolean("selfie", true), o.optBoolean("loc", true), o.optLong("t"), o.optString("nick", ""))
        }
    }

    private fun savePairs(ctx: Context, list: List<FamilyPair>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("pid", it.pid).put("name", it.peerName).put("nick", it.nick).put("key", it.keyWrapped)
                .put("ring", it.ring).put("selfie", it.selfie).put("loc", it.location).put("t", it.created))
        }
        p(ctx).edit().putString("pairs", arr.toString()).apply()
    }

    /** Saves a custom name for a paired phone (stays on this phone only). Empty = back to the name the other phone chose. */
    fun setNick(ctx: Context, pid: String, nick: String) {
        val l = pairs(ctx)
        l.firstOrNull { it.pid == pid }?.nick = nick.filter { !it.isISOControl() }.trim().take(24)
        savePairs(ctx, l)
    }

    fun setGrants(ctx: Context, pid: String, ring: Boolean, selfie: Boolean, location: Boolean) {
        val l = pairs(ctx)
        l.firstOrNull { it.pid == pid }?.let { it.ring = ring; it.selfie = selfie; it.location = location }
        savePairs(ctx, l)
    }

    private fun removeLocal(ctx: Context, pid: String) {
        savePairs(ctx, pairs(ctx).filter { it.pid != pid })
        FamilyHistory.clear(ctx, pid)                       // unpairing also erases the location history kept here
        if (p(ctx).getString("trk_pid", "") == pid) FamilyTracker.stop(ctx, null)
    }

    /** The raw 32-byte pair key (used by later steps to encrypt selfies and locations). */
    fun pairKey(ctx: Context, pair: FamilyPair): ByteArray? = try { unwrap(pair.keyWrapped) } catch (e: Exception) { null }

    // ------------------------------------------------------------------ pairing flow

    /** Phone that SHOWS a code. Returns the 4-digit code or an error. */
    fun openCode(ctx: Context): Reply {
        val eph = newEphemeral()
        val name = myName(ctx)
        val r = post("/pair/open", JSONObject().put("epk", b64(eph.public.encoded)).put("name", name))
        if (r.ok) session = Session(true, r.json!!.optString("code"), eph.private, b64(eph.public.encoded), name)
        return r
    }

    /** Phone that TYPES a code. On success the verification code is ready in session.sas. */
    fun join(ctx: Context, codeText: String): Reply {
        val code = codeText.trim().uppercase().removePrefix("TRAP-").trim()
        val eph = newEphemeral()
        val name = myName(ctx)
        val r = post("/pair/join", JSONObject().put("code", code).put("epk", b64(eph.public.encoded)).put("name", name))
        if (r.ok) {
            val s = Session(false, code, eph.private, b64(eph.public.encoded), name)
            val peer = r.json!!.optJSONObject("peer")
            s.peerEpk = peer?.optString("epk"); s.peerName = peer?.optString("name")
            s.sas = sas(s.ephPub, s.peerEpk ?: "")
            session = s
        }
        return r
    }

    /** Polls the rendezvous. Returns the status string: pending, approved, denied, or an error code. */
    fun poll(ctx: Context): String {
        val s = session ?: return "no_session"
        val r = post("/pair/poll", JSONObject().put("code", s.code))
        if (!r.ok) return r.error.ifEmpty { "error" }
        val status = r.json!!.optString("status")
        val peer = r.json.optJSONObject("peer")
        if (peer != null && s.peerEpk == null) {
            s.peerEpk = peer.optString("epk"); s.peerName = peer.optString("name")
            s.sas = sas(s.ephPub, s.peerEpk ?: "")
        }
        if (status == "approved" && !s.done) {
            val pid = r.json.optString("pid")
            if (pid.isNotEmpty() && finish(ctx, s, pid)) { s.done = true } else return "error"
        }
        return status
    }

    /** Owner only: allow or deny the pending request. */
    fun decide(ctx: Context, accept: Boolean): String {
        val s = session ?: return "no_session"
        val r = post("/pair/decide", JSONObject().put("code", s.code).put("accept", accept))
        if (!r.ok) return r.error.ifEmpty { "error" }
        if (accept) {
            val pid = r.json!!.optString("pid")
            if (pid.isEmpty() || !finish(ctx, s, pid)) return "error"
            s.done = true
            return "approved"
        }
        return "denied"
    }

    fun cancelSession() { session = null }

    private fun finish(ctx: Context, s: Session, pid: String): Boolean {
        return try {
            val peerEpk = s.peerEpk ?: return false
            if (pairs(ctx).size >= MAX_PAIRS) return false
            val key = deriveKey(s.ephPriv, peerEpk, s.ephPub)
            val l = pairs(ctx).filter { it.pid != pid }.toMutableList()
            l.add(FamilyPair(pid, (s.peerName ?: "").ifEmpty { "Phone" }, wrap(key), true, true, true, System.currentTimeMillis()))
            savePairs(ctx, l)
            true
        } catch (e: Exception) { android.util.Log.w(TAG, "finish failed", e); false }
    }

    /** Ends a pairing on the server (best effort) and ALWAYS removes it locally. */
    fun unpair(ctx: Context, pid: String): Boolean {
        val r = post("/pair/unpair", JSONObject().put("pid", pid))
        removeLocal(ctx, pid)
        return r.ok || r.http == 404
    }

    /** Drops local pairs that the other side ended (server answers not_found). Returns pids removed. */
    fun refresh(ctx: Context): List<String> {
        val gone = mutableListOf<String>()
        for (pr in pairs(ctx)) {
            val r = post("/pair/info", JSONObject().put("pid", pr.pid))
            if (r.http == 404) { removeLocal(ctx, pr.pid); gone.add(pr.pid) }
        }
        return gone
    }

    // ------------------------------------------------------------------ relay (v2.9.17)

    class Incoming(val msgs: List<String>, val photos: List<String>)

    /** AES-256-GCM with the pair key; output is base64(iv + ciphertext + tag). `aad` binds the context. */
    fun seal(key: ByteArray, aad: String, plain: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return b64(iv + c.doFinal(plain))
    }

    /** Null when the data was not produced with this key and context (tampered, wrong pair, wrong id). */
    fun open(key: ByteArray, aad: String, data: String): ByteArray? = try {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        if (raw.size < 29) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            c.updateAAD(aad.toByteArray())
            c.doFinal(raw, 12, raw.size - 12)
        }
    } catch (e: Exception) { null }

    /** Same photo always gets the same id, so a retried upload is recognised by the server. */
    fun photoId(pid: String, name: String): String =
        MessageDigest.getInstance("SHA-256").digest("$pid|$name".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Sends an end-to-end encrypted command ("ring" / "stop") to the other phone. Returns "sent" or an error code. */
    fun sendCommand(ctx: Context, pair: FamilyPair, type: String, mins: Int = 0): String {
        val key = pairKey(ctx, pair) ?: return "no_key"
        val o = JSONObject().put("t", type).put("ts", System.currentTimeMillis() / 1000)
            .put("n", b64(ByteArray(9).also { SecureRandom().nextBytes(it) }))
        if (mins > 0) o.put("mins", mins)
        val plain = o.toString()
        val r = post("/room/send", JSONObject().put("pid", pair.pid).put("ct", seal(key, "cmd|" + pair.pid, plain.toByteArray())))
        return if (r.ok) "sent" else r.error.ifEmpty { "error" }
    }

    /** Commands and photo ids waiting for this phone, or null on any failure (including an unpaired room). */
    fun pollRoom(pid: String): Incoming? {
        val r = post("/room/poll", JSONObject().put("pid", pid))
        if (!r.ok) return null
        val msgs = r.json!!.optJSONArray("msgs") ?: JSONArray()
        val photos = r.json.optJSONArray("photos") ?: JSONArray()
        return Incoming((0 until msgs.length()).map { msgs.getJSONObject(it).optString("ct") },
            (0 until photos.length()).map { photos.getJSONObject(it).optString("id") })
    }

    fun putPhoto(pid: String, id: String, ct: String): String {
        val r = post("/photo/put", JSONObject().put("pid", pid).put("id", id).put("ct", ct))
        return if (r.ok) "ok" else r.error.ifEmpty { "error" }
    }

    fun getPhoto(pid: String, id: String): String? {
        val r = post("/photo/get", JSONObject().put("pid", pid).put("id", id))
        return if (r.ok) r.json!!.optString("ct") else null
    }

    // v2.9.18: live location. The server keeps ONE encrypted slot per recipient (the latest fix), no history.
    // v2.9.24: returns the Reply (not just a Boolean) so the caller can tell a genuine network failure
    // (http == 0: timeout / no route / DNS) -- the signal for "weak or dropping internet" -- apart from a
    // normal application-level refusal (pair revoked, etc).
    fun putLoc(pid: String, ct: String): FamilyBridge.Reply = post("/loc/put", JSONObject().put("pid", pid).put("ct", ct))

    /** The latest encrypted fix or status for this phone, or null when there is none / on any failure. */
    fun getLoc(pid: String): String? {
        val r = post("/loc/get", JSONObject().put("pid", pid))
        return if (r.ok && !r.json!!.optBoolean("none", false)) r.json.optString("ct").ifEmpty { null } else null
    }

    fun clearLoc(pid: String): Boolean = post("/loc/clear", JSONObject().put("pid", pid)).ok

    // v2.9.23: offline BLE mesh. Register my upcoming rotating beacon ids (owner), forward a beacon I overheard
    // (any phone; opaque bytes only), fetch the newest beacon of a paired phone (watcher).
    fun registerBle(pid: String, rids: List<String>): Boolean =
        post("/ble/register", JSONObject().put("pid", pid).put("rids", org.json.JSONArray(rids))).ok

    /** "ok" = delivered or unknown id (nothing to retry), "retry" = try again later, anything else = refused for good. */
    fun reportBle(rid: String, b64: String): String {
        val r = post("/ble/report", JSONObject().put("rid", rid).put("b", b64))
        return when {
            r.ok -> "ok"
            r.http == 0 || r.http == 429 || r.http >= 500 -> "retry"
            else -> "refused"
        }
    }

    /** The newest offline beacon (base64, 23 bytes) the server holds for this phone from this pair, or null. */
    fun getBle(pid: String): String? {
        val r = post("/ble/get", JSONObject().put("pid", pid))
        return if (r.ok && !r.json!!.optBoolean("none", false)) r.json.optString("b").ifEmpty { null } else null
    }

    /** Tells the server the photo arrived safely: it is permanently deleted there right away. */
    fun ackPhoto(pid: String, id: String): Boolean = post("/photo/ack", JSONObject().put("pid", pid).put("id", id)).ok

    // ------------------------------------------------------------------ crypto

    private class Eph(val private: java.security.PrivateKey, val public: java.security.PublicKey)

    private fun newEphemeral(): Eph {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp: KeyPair = g.generateKeyPair()
        return Eph(kp.private, kp.public)
    }

    /** ECDH + HKDF-SHA256. Both phones sort the two ephemeral public keys, so the result is identical. */
    private fun deriveKey(myPriv: java.security.PrivateKey, peerEpkB64: String, myEpkB64: String): ByteArray {
        val peerPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(peerEpkB64, Base64.NO_WRAP)))
        val ka = KeyAgreement.getInstance("ECDH"); ka.init(myPriv); ka.doPhase(peerPub, true)
        val secret = ka.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(sortedJoin(myEpkB64, peerEpkB64).toByteArray())
        val prk = hmac(salt, secret)
        return hmac(prk, "TT-family-v1".toByteArray() + byteArrayOf(1))   // HKDF-Expand, one 32-byte block
    }

    /** 6 digits both users read aloud or compare: derived from the two public keys only. */
    private fun sas(myEpk: String, peerEpk: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("TT-SAS|" + sortedJoin(myEpk, peerEpk)).toByteArray())
        val n = ((d[0].toLong() and 0xff) shl 24 or ((d[1].toLong() and 0xff) shl 16) or
                ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)) % 1_000_000L
        return String.format("%06d", n).let { it.substring(0, 3) + " " + it.substring(3) }
    }

    private fun sortedJoin(a: String, b: String) = if (a <= b) "$a|$b" else "$b|$a"

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data)
    }

    private fun wrapKey(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun wrap(raw: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return b64(c.iv) + ":" + b64(c.doFinal(raw))
    }

    private fun unwrap(s: String): ByteArray {
        val (iv, ct) = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
    }

    // ------------------------------------------------------------------ signed HTTP

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun signingKey(): KeyPair? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(SIGN_ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            g.generateKeyPair()
        }
        KeyPair(ks.getCertificate(SIGN_ALIAS).publicKey, ks.getKey(SIGN_ALIAS, null) as java.security.PrivateKey)
    } catch (e: Exception) { android.util.Log.w(TAG, "signing key unavailable", e); null }

    /** ASN.1 DER ECDSA signature -> raw r||s (64 bytes), which is what WebCrypto verifies. */
    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2; if (der[1].toInt() and 0x80 != 0) i += (der[1].toInt() and 0x7f)
        fun readInt(): ByteArray {
            val len = der[i + 1].toInt() and 0xff; val v = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
            val t = v.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - t.size) + t
        }
        val r = readInt(); val s = readInt(); return r + s
    }

    /** Signed headers for a request that is not a JSON POST (the WebSocket upgrade). Null without a device key. */
    internal fun signedHeaders(method: String, path: String, bodyText: String): Map<String, String>? {
        val kp = signingKey() ?: return null
        return try {
            val ts = System.currentTimeMillis() / 1000
            val nonce = Base64.encodeToString(ByteArray(9).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(bodyText.toByteArray()).joinToString("") { "%02x".format(it) }
            val sg = Signature.getInstance("SHA256withECDSA"); sg.initSign(kp.private)
            sg.update("TF1|$method|$path|$ts|$nonce|$bodyHash".toByteArray())
            mapOf("x-tt-pub" to b64(kp.public.encoded), "x-tt-ts" to ts.toString(), "x-tt-nonce" to nonce, "x-tt-sig" to b64(derToRaw(sg.sign())))
        } catch (e: Exception) { null }
    }

    internal fun post(path: String, body: JSONObject): Reply {
        val kp = signingKey() ?: return Reply(0, JSONObject().put("ok", false).put("error", "no_device_key"))
        var conn: HttpURLConnection? = null
        return try {
            val text = body.toString()
            val ts = System.currentTimeMillis() / 1000
            val nonce = Base64.encodeToString(ByteArray(9).also { SecureRandom().nextBytes(it) },
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val sg = Signature.getInstance("SHA256withECDSA"); sg.initSign(kp.private)
            sg.update("TF1|POST|$path|$ts|$nonce|$bodyHash".toByteArray())
            conn = (URL(API_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 10000; readTimeout = 15000; doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-tt-pub", b64(kp.public.encoded))
                setRequestProperty("x-tt-ts", ts.toString())
                setRequestProperty("x-tt-nonce", nonce)
                setRequestProperty("x-tt-sig", b64(derToRaw(sg.sign())))
            }
            conn.outputStream.use { it.write(text.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Reply(code, try { JSONObject(txt) } catch (e: Exception) { null })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "network error on $path: ${e.message}")
            Reply(0, null)
        } finally { conn?.disconnect() }
    }
}
