package com.thievestrap

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v2.9.23: the offline-beacon packet (what a phone without internet broadcasts over Bluetooth LE).
 *
 * It must fit ONE legacy advertisement (31 bytes), so it is a compact encrypt-then-MAC format, 23 bytes:
 *
 *   ver(1)=0x01 | rid(4) | epoch(3) | seq(1) | ct(8) | tag(6)
 *
 *   rid    rotating routing id = HMAC(ridKey, epoch)[0..4]; changes every 15 min (EPOCH_S) so a bystander cannot
 *          follow one phone around; only the owner and the server's id index (which gets the ids the owner
 *          registers) can map it to a pair. ridKey = HMAC(pairKey, "tt-ble-v1-rid" | local random salt).
 *   epoch  floor(unix seconds / 900), 24 bit.   seq  counts beacons inside one epoch (never reused: it is
 *          part of the keystream block).
 *   ct     the 64-bit fix below XOR the first 8 bytes of AES-256(encKey, ver|rid|epoch|seq|0...) (CTR-style).
 *   tag    HMAC-SHA256(macKey, ver|rid|epoch|seq|ct)[0..6]; the watcher drops anything that does not verify.
 *
 *   fix (64 bits) = lat(24) | lon(25) | minuteInEpoch(4) | fixAgeMin(4) | battery(4) | accuracyClass(3)
 *          lat/lon quantised to ~1.2 m.  time of the fix = epoch*900 + minute*60 - age*60 (+-1 min).
 *
 * encKey / macKey come from the 32-byte pair key (the one that already protects selfies and live location) so
 * only the two paired phones can read a beacon. Nearby strangers relay it without being able to open it, and
 * they never add their own location to it.
 * NOTE: this is a purpose-built compact format (48-bit tag), not Apple's protocol; see STATE.md for the review notes.
 */
object BleCrypto {

    const val VER = 1
    const val LEN = 23
    const val EPOCH_S = 900L
    private val ACC = intArrayOf(10, 25, 50, 100, 250, 500, 1000)       // upper bounds in metres; 7 = worse than 1 km

    class Fix(val lat: Double, val lon: Double, val accuracyM: Int, val timeSec: Long, val battery: Int, val stale: Boolean)

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }
    private fun sub(pairKey: ByteArray, label: String) = hmac(pairKey, label.toByteArray())

    fun epochOf(nowSec: Long): Int = (nowSec / EPOCH_S).toInt() and 0xFFFFFF

    private fun epochBytes(e: Int) = byteArrayOf((e shr 16).toByte(), (e shr 8).toByte(), e.toByte())

    fun rid(pairKey: ByteArray, salt: ByteArray, epoch: Int): ByteArray =
        hmac(hmac(pairKey, "tt-ble-v1-rid".toByteArray(), salt), epochBytes(epoch)).copyOfRange(0, 4)

    fun ridHex(pairKey: ByteArray, salt: ByteArray, epoch: Int): String = rid(pairKey, salt, epoch).joinToString("") { "%02x".format(it) }

    private fun keystream(encKey: ByteArray, header: ByteArray): ByteArray {
        val block = ByteArray(16); header.copyInto(block, 0)              // 9 header bytes + 7 zero bytes
        val c = Cipher.getInstance("AES/ECB/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"))
        return c.doFinal(block)
    }

    private fun accClass(m: Float): Int { for (i in ACC.indices) if (m < ACC[i]) return i; return 7 }

    /** Builds one 23-byte beacon. [fixTimeSec] is when the GPS fix was taken, [nowSec] when the beacon is made. */
    fun seal(pairKey: ByteArray, salt: ByteArray, nowSec: Long, seq: Int, lat: Double, lon: Double,
             accuracyM: Float, fixTimeSec: Long, batteryPct: Int): ByteArray {
        val epoch = epochOf(nowSec)
        val minute = (((nowSec % EPOCH_S) / 60L).toInt()).coerceIn(0, 14)
        val age = (((nowSec - fixTimeSec) / 60L).toInt()).coerceIn(0, 15)
        val la = Math.round((lat.coerceIn(-90.0, 90.0) + 90.0) / 180.0 * 16777215.0)           // 2^24 - 1
        val lo = Math.round((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * 33554431.0)        // 2^25 - 1
        val bat = Math.round(batteryPct.coerceIn(0, 100) / 100.0 * 15.0).toInt()
        val v = (la shl 40) or (lo shl 15) or (minute.toLong() shl 11) or (age.toLong() shl 7) or (bat.toLong() shl 3) or accClass(accuracyM).toLong()
        val plain = ByteBuffer.allocate(8).putLong(v).array()

        val header = ByteArray(9)
        header[0] = VER.toByte()
        rid(pairKey, salt, epoch).copyInto(header, 1)
        epochBytes(epoch).copyInto(header, 5)
        header[8] = seq.toByte()
        val ks = keystream(sub(pairKey, "tt-ble-v1-enc"), header)
        val ct = ByteArray(8) { (plain[it].toInt() xor ks[it].toInt()).toByte() }
        val tag = hmac(sub(pairKey, "tt-ble-v1-mac"), header, ct).copyOfRange(0, 6)
        return header + ct + tag
    }

    /** Opens a beacon with the pair key; null when it is malformed or was not made with this key. */
    fun open(pairKey: ByteArray, b: ByteArray): Fix? {
        if (b.size != LEN || b[0].toInt() != VER) return null
        val header = b.copyOfRange(0, 9); val ct = b.copyOfRange(9, 17); val tag = b.copyOfRange(17, 23)
        val want = hmac(sub(pairKey, "tt-ble-v1-mac"), header, ct).copyOfRange(0, 6)
        var diff = 0; for (i in 0 until 6) diff = diff or (want[i].toInt() xor tag[i].toInt())
        if (diff != 0) return null
        val ks = keystream(sub(pairKey, "tt-ble-v1-enc"), header)
        val plain = ByteArray(8) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
        val v = ByteBuffer.wrap(plain).long
        val la = (v ushr 40) and 0xFFFFFF
        val lo = (v ushr 15) and 0x1FFFFFF
        val minute = ((v ushr 11) and 0xF).toInt()
        val age = ((v ushr 7) and 0xF).toInt()
        val bat = ((v ushr 3) and 0xF).toInt()
        val acc = (v and 0x7).toInt()
        val epoch = ((b[5].toInt() and 0xFF) shl 16) or ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        return Fix(
            la / 16777215.0 * 180.0 - 90.0, lo / 33554431.0 * 360.0 - 180.0,
            if (acc < 7) ACC[acc] else 2000,
            epoch * EPOCH_S + minute * 60L - age * 60L, Math.round(bat / 15.0 * 100.0).toInt(), age >= 15)
    }

    /** 24-bit epoch and 8-bit sequence as one comparable number (newer beacons are larger). */
    fun order(b: ByteArray): Long = ((b[5].toLong() and 0xFF) shl 16 or ((b[6].toLong() and 0xFF) shl 8) or (b[7].toLong() and 0xFF)) * 256L + (b[8].toLong() and 0xFF)
}
