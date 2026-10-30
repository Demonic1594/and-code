package com.yugahashimoto.andcode.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Opens the app's encrypted preference files without ever failing the caller.
 *
 * See [openWithRecovery] for why this must not throw. The preferences are encrypted with a master
 * key shared by every file, which is why the key is only discarded when no encrypted file has opened
 * in this process: a file that did open proves the key works, so a later failure is that file's own
 * problem and replacing the key would only destroy the files that are fine.
 */
object EncryptedPreferences {
    private const val TAG = "EncryptedPreferences"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var masterKeyProvenUsable = false

    /** Returns the encrypted preferences [name], or an [InMemorySharedPreferences] if none can be had. */
    fun open(
        context: Context,
        name: String,
    ): SharedPreferences =
        openWithRecovery(
            open = { create(context, name) },
            discardStoredPreferences = { context.deleteSharedPreferences(name) },
            discardMasterKey = { if (!masterKeyProvenUsable) deleteMasterKey() },
            fallback = {
                Log.e(TAG, "Encrypted preferences '$name' are unavailable; settings will not be saved")
                InMemorySharedPreferences()
            },
        )

    private fun create(
        context: Context,
        name: String,
    ): SharedPreferences =
        try {
            // Building the master key is part of opening: it is where Keystore key generation fails.
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { masterKeyProvenUsable = true }
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted preferences '$name' failed to open", error)
            throw error
        }

    private fun deleteMasterKey() {
        Log.w(TAG, "Discarding the unusable master key so a new one can be created")
        KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }
}
