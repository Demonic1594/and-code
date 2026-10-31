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
 * See [openWithRecovery] for why this must not throw and [RecoveringOpener] for what is remembered
 * between calls. This object is only the Android side: the Keystore and file operations.
 */
object EncryptedPreferences {
    private const val TAG = "EncryptedPreferences"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private val opener =
        RecoveringOpener<SharedPreferences> { name ->
            Log.e(TAG, "Encrypted preferences '$name' are unavailable; settings will not be saved")
            InMemorySharedPreferences()
        }

    /** Returns the encrypted preferences [name], or an [InMemorySharedPreferences] if none can be had. */
    fun open(
        context: Context,
        name: String,
    ): SharedPreferences =
        opener.open(
            name = name,
            create = { create(context, name) },
            discardStoredPreferences = { context.deleteSharedPreferences(name) },
            discardMasterKey = ::deleteMasterKey,
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
            )
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
