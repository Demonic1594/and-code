package com.yugahashimoto.andcode.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class EncryptedPreferencesRecoveryTest {
    private val steps = mutableListOf<String>()

    private fun recover(
        failuresBeforeSuccess: Int,
        discardPreferences: () -> Unit = { steps += "discardPreferences" },
        discardMasterKey: () -> Unit = { steps += "discardMasterKey" },
        failure: () -> Throwable = { GeneralSecurityException("keyset undecryptable") },
    ): String {
        var attempts = 0
        return openWithRecovery(
            open = {
                steps += "open"
                if (attempts++ < failuresBeforeSuccess) throw failure()
                "encrypted"
            },
            discardStoredPreferences = discardPreferences,
            discardMasterKey = discardMasterKey,
            fallback = {
                steps += "fallback"
                "in-memory"
            },
        )
    }

    @Test
    fun `a healthy keystore is opened once and nothing is discarded`() {
        assertEquals("encrypted", recover(failuresBeforeSuccess = 0))

        assertEquals(listOf("open"), steps)
    }

    @Test
    fun `an undecryptable keyset only costs the stored preferences, never the shared master key`() {
        assertEquals("encrypted", recover(failuresBeforeSuccess = 1))

        assertEquals(listOf("open", "discardPreferences", "open"), steps)
    }

    @Test
    fun `an unusable master key is discarded only after a fresh preferences file still fails`() {
        assertEquals("encrypted", recover(failuresBeforeSuccess = 2))

        assertEquals(
            listOf("open", "discardPreferences", "open", "discardPreferences", "discardMasterKey", "open"),
            steps,
        )
    }

    @Test
    fun `a keystore that cannot be repaired falls back instead of crashing the app`() {
        assertEquals("in-memory", recover(failuresBeforeSuccess = Int.MAX_VALUE))

        assertEquals("fallback", steps.last())
    }

    @Test
    fun `a failing discard does not stop the remaining recovery steps`() {
        val result =
            recover(
                failuresBeforeSuccess = 2,
                discardPreferences = { throw java.io.IOException("cannot delete") },
                discardMasterKey = { throw java.security.KeyStoreException("cannot delete") },
            )

        assertEquals("encrypted", result)
        assertEquals(listOf("open", "open", "open"), steps)
    }

    @Test
    fun `an unrecoverable JVM error is not swallowed`() {
        assertThrows(OutOfMemoryError::class.java) {
            recover(failuresBeforeSuccess = 1, failure = { OutOfMemoryError() })
        }
    }

    @Test
    fun `a failing fallback is the caller's problem and is not hidden`() {
        assertThrows(IllegalStateException::class.java) {
            openWithRecovery(
                open = { throw GeneralSecurityException("broken") },
                discardStoredPreferences = {},
                discardMasterKey = {},
                fallback = { throw IllegalStateException("no fallback") },
            )
        }
    }
}
