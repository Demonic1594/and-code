package com.yugahashimoto.andcode.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySharedPreferencesTest {
    private val preferences = InMemorySharedPreferences()

    @Test
    fun `every type reads back what was committed`() {
        preferences.edit()
            .putString("s", "value")
            .putInt("i", 7)
            .putLong("l", 8_000_000_000L)
            .putFloat("f", 1.5f)
            .putBoolean("b", true)
            .putStringSet("set", mutableSetOf("a", "b"))
            .commit()

        assertEquals("value", preferences.getString("s", null))
        assertEquals(7, preferences.getInt("i", 0))
        assertEquals(8_000_000_000L, preferences.getLong("l", 0L))
        assertEquals(1.5f, preferences.getFloat("f", 0f), 0f)
        assertTrue(preferences.getBoolean("b", false))
        assertEquals(setOf("a", "b"), preferences.getStringSet("set", null))
    }

    @Test
    fun `missing keys return the caller's default`() {
        assertEquals("fallback", preferences.getString("s", "fallback"))
        assertEquals(3, preferences.getInt("i", 3))
        assertEquals(4L, preferences.getLong("l", 4L))
        assertEquals(2.5f, preferences.getFloat("f", 2.5f), 0f)
        assertTrue(preferences.getBoolean("b", true))
        assertEquals(setOf("x"), preferences.getStringSet("set", mutableSetOf("x")))
        assertFalse(preferences.contains("s"))
    }

    @Test
    fun `reading a key as a different type than it was stored fails like the platform does`() {
        preferences.edit().putString("k", "text").commit()

        val error = runCatching { preferences.getInt("k", 0) }.exceptionOrNull()

        assertTrue(error is ClassCastException)
    }

    @Test
    fun `putting a null string or set removes the key`() {
        preferences.edit().putString("s", "value").putStringSet("set", mutableSetOf("a")).commit()

        preferences.edit().putString("s", null).putStringSet("set", null).commit()

        assertFalse(preferences.contains("s"))
        assertFalse(preferences.contains("set"))
    }

    @Test
    fun `edits are invisible until applied`() {
        val editor = preferences.edit().putString("s", "value")

        assertNull(preferences.getString("s", null))

        editor.apply()

        assertEquals("value", preferences.getString("s", null))
    }

    @Test
    fun `remove and clear are applied with the rest of the batch`() {
        preferences.edit().putString("a", "1").putString("b", "2").commit()

        preferences.edit().remove("a").commit()
        assertFalse(preferences.contains("a"))
        assertTrue(preferences.contains("b"))

        preferences.edit().clear().commit()
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun `a clear wipes what was stored but keeps what the same batch puts, whatever the call order`() {
        preferences.edit().putString("old", "1").commit()

        preferences.edit().putString("kept", "2").clear().commit()

        assertFalse(preferences.contains("old"))
        assertEquals("2", preferences.getString("kept", null))
    }

    @Test
    fun `an editor that was already committed does not replay its old changes`() {
        val editor = preferences.edit().putString("a", "1")
        editor.commit()
        preferences.edit().remove("a").commit()

        editor.commit()

        assertFalse(preferences.contains("a"))
    }

    @Test
    fun `string sets are copied on the way in and on the way out`() {
        val source = mutableSetOf("a")
        preferences.edit().putStringSet("set", source).commit()
        source += "mutated-after-commit"

        val read = preferences.getStringSet("set", null)!!.toMutableSet()
        read += "mutated-after-read"

        assertEquals(setOf("a"), preferences.getStringSet("set", null))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `all returns a snapshot that cannot change the store`() {
        preferences.edit().putString("a", "1").commit()

        (preferences.all as MutableMap<String, Any?>).clear()

        assertEquals("1", preferences.getString("a", null))
    }
}
