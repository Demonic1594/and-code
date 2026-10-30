package com.yugahashimoto.andcode.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class RecoveringOpenerTest {
    private val steps = mutableListOf<String>()
    private var fallbacksCreated = 0
    private val opener = RecoveringOpener { "in-memory-${++fallbacksCreated}" }

    private fun open(
        name: String,
        broken: Boolean = false,
    ): String =
        opener.open(
            name = name,
            create = {
                steps += "open $name"
                if (broken) throw GeneralSecurityException("keystore unusable") else "encrypted-$name"
            },
            discardStoredPreferences = { steps += "discardPreferences $name" },
            discardMasterKey = { steps += "discardMasterKey" },
        )

    @Test
    fun `a healthy file is opened without any recovery`() {
        assertEquals("encrypted-a", open("a"))

        assertEquals(listOf("open a"), steps)
    }

    @Test
    fun `every caller asking for an unavailable file shares one fallback`() {
        val first = open("secure", broken = true)
        val second = open("secure", broken = true)

        assertEquals("in-memory-1", first)
        assertEquals(first, second)
        assertEquals(1, fallbacksCreated)
    }

    @Test
    fun `a file that already fell back is not repaired again`() {
        open("secure", broken = true)
        val stepsAfterFirstOpen = steps.toList()

        open("secure", broken = true)

        assertEquals(stepsAfterFirstOpen, steps)
    }

    @Test
    fun `different files fall back independently`() {
        val secure = open("secure", broken = true)
        val schedules = open("schedules", broken = true)

        assertNotEquals(secure, schedules)
    }

    @Test
    fun `the master key is discarded while no encrypted file has ever opened`() {
        open("secure", broken = true)

        assertTrue("discardMasterKey" in steps)
    }

    @Test
    fun `the master key is kept once another file has proven it works`() {
        open("secure")

        val schedules = open("schedules", broken = true)

        assertEquals("in-memory-1", schedules)
        assertFalse("discardMasterKey" in steps)
    }
}
