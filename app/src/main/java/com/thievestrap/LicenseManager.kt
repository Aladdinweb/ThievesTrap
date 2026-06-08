package com.thievestrap

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest

object LicenseManager {

    private const val MASTER_KEY = "ADMIN-ILINE-2024"
    private const val PREFS_FILE = "tt_secure"
    private const val KEY_PREMIUM = "p"
    private const val KEY_LICENSE = "l"

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                keyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("tt_fb", Context.MODE_PRIVATE)
        }
    }

    fun getAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    fun generateKeyForDevice(androidId: String): String {
        val hash = sha256("ILINE2026$androidId")
        val p1 = hash.substring(0, 4).uppercase()
        val p2 = hash.substring(4, 8).uppercase()
        val p3 = hash.substring(8, 12).uppercase()
        return "ILINE-$p1-$p2-$p3"
    }

    fun validateKey(context: Context, inputKey: String): ValidationResult {
        val trimmed = inputKey.trim().uppercase()
        if (trimmed == MASTER_KEY) {
            setPremium(context, true, trimmed)
            return ValidationResult.SUCCESS_MASTER
        }
        val expected = generateKeyForDevice(getAndroidId(context))
        return if (trimmed == expected) {
            setPremium(context, true, trimmed)
            ValidationResult.SUCCESS_DEVICE
        } else ValidationResult.INVALID
    }

    fun isPremium(context: Context): Boolean =
        try { getSecurePrefs(context).getBoolean(KEY_PREMIUM, false) }
        catch (e: Exception) { false }

    fun setPremium(context: Context, value: Boolean, license: String = "") {
        try {
            getSecurePrefs(context).edit()
                .putBoolean(KEY_PREMIUM, value)
                .putString(KEY_LICENSE, license)
                .apply()
        } catch (e: Exception) {}
    }

    fun revokePremium(context: Context) {
        try {
            getSecurePrefs(context).edit()
                .putBoolean(KEY_PREMIUM, false)
                .putString(KEY_LICENSE, "")
                .apply()
        } catch (e: Exception) {}
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    enum class ValidationResult { SUCCESS_DEVICE, SUCCESS_MASTER, INVALID }
}
