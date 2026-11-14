package com.thievestrap

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v2.9.15: the REMOTE SMS PIN.
 *
 * - Completely independent from the local unlock PIN (pref "password"): the two must differ.
 * - Never stored in clear text: PBKDF2-HMAC-SHA256 (random 16-byte salt, 120 000 iterations).
 *   Plain salted SHA-256 is far too fast for a short numeric PIN, so the hash is iterated.
 * - Compared in constant time.
 * - Brute-force lockout: 3 consecutive wrong PINs received by SMS block ALL remote commands for
 *   15 minutes. The state is persisted (survives process death and reboots) and is GLOBAL, not per
 *   sender, because an SMS sender number can be spoofed or rotated.
 *
 * Own prefs file, so it never mixes with tt_prefs. Call only from a background thread
 * (verify/set take a few tens of milliseconds by design).
 */
object RemotePin {

    private const val PREFS = "tt_remote_pin"
    private const val K_SALT = "salt"
    private const val K_HASH = "hash"
    private const val K_ITER = "iter"
    private const val K_FAILS = "fails"
    private const val K_LOCK_UNTIL = "lock_until"

    const val MIN_LEN = 6
    const val MAX_LEN = 12
    const val MAX_FAILS = 3
    const val LOCK_MS = 15L * 60 * 1000
    private const val ITERATIONS = 120_000

    private val PIN_SHAPE = Regex("^[0-9]{$MIN_LEN,$MAX_LEN}$")

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the text has the shape of a remote PIN (only such texts count as a PIN attempt). */
    fun looksLikePin(text: String): Boolean = PIN_SHAPE.matches(text)

    fun isSet(ctx: Context): Boolean = p(ctx).getString(K_HASH, null) != null

    sealed class SetResult {
        object Ok : SetResult()
        object BadFormat : SetResult()
        object SameAsLocal : SetResult()
    }

    /** Stores a new remote PIN (hash only) and clears any lockout. */
    fun set(ctx: Context, pin: String): SetResult {
        if (!looksLikePin(pin)) return SetResult.BadFormat
        val local = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE).getString("password", "") ?: ""
        if (local.isNotEmpty() && MessageDigest.isEqual(local.toByteArray(), pin.toByteArray())) return SetResult.SameAsLocal
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt, ITERATIONS)
        p(ctx).edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(K_ITER, ITERATIONS)
            .putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0L)
            .apply()
        return SetResult.Ok
    }

    /** Constant-time check. False when no PIN is set. Does NOT touch the lockout counters. */
    fun verify(ctx: Context, pin: String): Boolean {
        val sp = p(ctx)
        val saltB64 = sp.getString(K_SALT, null) ?: return false
        val hashB64 = sp.getString(K_HASH, null) ?: return false
        val iter = sp.getInt(K_ITER, ITERATIONS)
        if (!looksLikePin(pin)) return false
        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val expected = Base64.decode(hashB64, Base64.NO_WRAP)
            MessageDigest.isEqual(expected, pbkdf2(pin, salt, iter))
        } catch (e: Exception) { false }
    }

    fun isLocked(ctx: Context): Boolean = lockRemainingMs(ctx) > 0L

    fun lockRemainingMs(ctx: Context): Long =
        (p(ctx).getLong(K_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Records one wrong PIN. Returns true when THIS attempt started the lockout. */
    @Synchronized
    fun registerFailure(ctx: Context): Boolean {
        val sp = p(ctx)
        val fails = sp.getInt(K_FAILS, 0) + 1
        return if (fails >= MAX_FAILS) {
            sp.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, System.currentTimeMillis() + LOCK_MS).apply()
            true
        } else {
            sp.edit().putInt(K_FAILS, fails).apply()
            false
        }
    }

    /** A correct PIN ends the "consecutive" streak. */
    @Synchronized
    fun registerSuccess(ctx: Context) {
        p(ctx).edit().putInt(K_FAILS, 0).apply()
    }

    // PBKDF2-HMAC-SHA256 written out with Mac, so it works on every Android version
    // (the PBKDF2WithHmacSHA256 factory only exists from API 26).
    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))        // block index 1 (32-byte output = one block)
        var u = mac.doFinal()
        val out = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }
}
