package com.yugahashimoto.andcode.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Opens the app's encrypted preference files without ever failing the caller.
 *
 * See [openWithRecovery] for why this must not throw and [RecoveringOpener] for what is remembered
 * between calls. This object is only the Android side: the Keystore and file operations.
 */
object EncryptedPreferences {
    private const val TAG = "EncryptedPreferences"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** The master key is an AES-GCM Keystore key; the Keystore picks the IV itself. */
    private const val KEY_TEST_TRANSFORMATION = "AES/GCM/NoPadding"

    private val opener =
        RecoveringOpener<SharedPreferences>(masterKeyWorks = ::masterKeyWorks) { name ->
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

    /**
     * Whether the stored master key still encrypts. Only a key that does not (permanently
     * invalidated, or missing) is worth replacing; see [RecoveringOpener].
     */
    private fun masterKeyWorks(): Boolean =
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
            val cipher = key?.let { Cipher.getInstance(KEY_TEST_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, it) } }
            cipher != null && cipher.doFinal(ByteArray(1)).isNotEmpty()
        } catch (error: Exception) {
            Log.w(TAG, "The master key failed a test encryption", error)
            false
        }

    private fun deleteMasterKey() {
        Log.w(TAG, "Discarding the unusable master key so a new one can be created")
        KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }
}
