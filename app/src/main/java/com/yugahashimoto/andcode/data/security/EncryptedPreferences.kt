package com.yugahashimoto.andcode.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    private const val GCM_TAG_BITS = 128
    private const val KEY_TEST_BYTE: Byte = 0x2A

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
     * Whether the stored master key still works: one byte encrypted and decrypted back. A key that
     * fails either way (permanently invalidated, missing, or able to encrypt but not decrypt) is worth
     * replacing; see [RecoveringOpener].
     */
    private fun masterKeyWorks(): Boolean =
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
            key != null && roundTrips(key)
        } catch (error: Exception) {
            Log.w(TAG, "The master key failed a test encryption", error)
            false
        }

    private fun roundTrips(key: SecretKey): Boolean {
        val probe = byteArrayOf(KEY_TEST_BYTE)
        val encrypt = Cipher.getInstance(KEY_TEST_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val sealed = encrypt.doFinal(probe)
        val decrypt =
            Cipher.getInstance(KEY_TEST_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, encrypt.iv))
            }
        return decrypt.doFinal(sealed).contentEquals(probe)
    }

    private fun deleteMasterKey() {
        Log.w(TAG, "Discarding the unusable master key so a new one can be created")
        KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }
}
