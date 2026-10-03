package com.thievestrap

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest

object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val MASTER_KEY = "ADMIN-ILINE-2024"
    private const val PREFS_FILE = "tt_secure"
    private const val KEY_PREMIUM = "p"
    private const val KEY_LICENSE = "l"

    // FIX 2026-09-04: plain (unencrypted) backup of premium state. App
    // updates can invalidate the Keystore-backed master key behind
    // EncryptedSharedPreferences (most commonly after a signing-key change,
    // but it has also been observed on plain in-place updates on some
    // devices/Android versions) -- once that key is gone, the OLD encrypted
    // blob is cryptographically unrecoverable, and isPremium() silently
    // reverts to false with zero indication anything went wrong. A plain
    // SharedPreferences file is immune to that failure mode (no Keystore
    // involved at all) and survives every update and reinstall-with-same-
    // package-name scenario. It's intentionally used only as a recovery
    // signal, never as the primary store -- the encrypted store stays the
    // source of truth whenever it's actually readable.
    private const val BACKUP_PREFS = "tt_premium_backup"
    private const val KEY_BACKUP_PREMIUM = "premium_backup"
    private const val KEY_BACKUP_LICENSE = "license_backup"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

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
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain tt_fb prefs", e)
            context.getSharedPreferences("tt_fb", Context.MODE_PRIVATE)
        }
    }

    private fun getBackupPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

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

    fun isPremium(context: Context): Boolean {
        val secureValue = try {
            getSecurePrefs(context).getBoolean(KEY_PREMIUM, false)
        } catch (e: Exception) {
            false
        }
        if (secureValue) return true

        // FIX 2026-09-04: the encrypted store says false (or is unreadable
        // and fell back to the empty tt_fb file). Before accepting that at
        // face value, check the plain backup -- if it says premium was
        // activated, the encrypted layer is the one that's broken, not the
        // license. Self-heal by re-writing into whatever prefs are
        // currently accessible (this always succeeds even post-update,
        // since writing creates a FRESH entry under the CURRENT valid
        // Keystore key -- only READING an OLD blob under a NEW key fails).
        val backup = getBackupPrefs(context)
        val backupPremium = backup.getBoolean(KEY_BACKUP_PREMIUM, false)
        if (backupPremium) {
            Log.i(TAG, "Encrypted premium flag was false/unreadable but backup says true -- self-healing")
            val license = backup.getString(KEY_BACKUP_LICENSE, "") ?: ""
            try {
                getSecurePrefs(context).edit()
                    .putBoolean(KEY_PREMIUM, true)
                    .putString(KEY_LICENSE, license)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Self-heal write to secure prefs failed, continuing on backup value only", e)
            }
            return true
        }
        return false
    }

    fun setPremium(context: Context, value: Boolean, license: String = "") {
        try {
            getSecurePrefs(context).edit()
                .putBoolean(KEY_PREMIUM, value)
                .putString(KEY_LICENSE, license)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "setPremium: write to secure prefs failed", e)
        }
        // Always mirror into the plain backup too, regardless of whether the
        // encrypted write above succeeded -- this is what makes the
        // self-heal path in isPremium() possible later.
        try {
            getBackupPrefs(context).edit()
                .putBoolean(KEY_BACKUP_PREMIUM, value)
                .putString(KEY_BACKUP_LICENSE, license)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "setPremium: write to backup prefs failed", e)
        }
    }

    fun revokePremium(context: Context) {
        try {
            getSecurePrefs(context).edit()
                .putBoolean(KEY_PREMIUM, false)
                .putString(KEY_LICENSE, "")
                .apply()
        } catch (e: Exception) {}
        try {
            getBackupPrefs(context).edit()
                .putBoolean(KEY_BACKUP_PREMIUM, false)
                .putString(KEY_BACKUP_LICENSE, "")
                .apply()
        } catch (e: Exception) {}
    }

    // FIX 2026-09-04: called from PackageReplacedReceiver (MY_PACKAGE_REPLACED)
    // and defensively from MainActivity.onCreate() as a second safety net in
    // case that broadcast doesn't fire reliably on some OEM/launcher
    // combinations. Detects an app update via versionCode comparison (stored
    // in the plain backup prefs, which is immune to the Keystore issue this
    // whole mechanism exists to work around) and proactively re-asserts
    // premium state into the encrypted store so the very next isPremium()
    // call after an update is already correct, rather than relying on that
    // call's own self-heal path to catch it reactively.
    fun reconcileAfterUpdate(context: Context) {
        try {
            val backup = getBackupPrefs(context)
            val currentVersionCode = try {
                context.packageManager.getPackageInfo(context.packageName, 0).let {
                    @Suppress("DEPRECATION")
                    it.versionCode
                }
            } catch (e: Exception) { -1 }

            val lastVersionCode = backup.getInt(KEY_LAST_VERSION_CODE, -1)
            if (lastVersionCode != currentVersionCode) {
                Log.i(TAG, "App update detected (versionCode $lastVersionCode -> $currentVersionCode), reconciling premium state")
                if (backup.getBoolean(KEY_BACKUP_PREMIUM, false)) {
                    val license = backup.getString(KEY_BACKUP_LICENSE, "") ?: ""
                    try {
                        getSecurePrefs(context).edit()
                            .putBoolean(KEY_PREMIUM, true)
                            .putString(KEY_LICENSE, license)
                            .apply()
                    } catch (e: Exception) {
                        Log.e(TAG, "reconcileAfterUpdate: write to secure prefs failed", e)
                    }
                }
                backup.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconcileAfterUpdate failed", e)
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    enum class ValidationResult { SUCCESS_DEVICE, SUCCESS_MASTER, INVALID }
}
