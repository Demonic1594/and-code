package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.data.security.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTriggerTokenTest {
    private val token = ScheduleTriggerToken(InMemorySharedPreferences())

    @Test
    fun `nothing is accepted until triggers are turned on`() {
        assertNull(token.current())
        assertFalse(token.accepts(""))
        assertFalse(token.accepts("anything"))
        assertFalse(token.accepts(null))
    }

    @Test
    fun `only the current token is accepted`() {
        val first = token.renew()

        assertEquals(48, first.length)
        assertTrue(token.accepts(first))
        assertFalse(token.accepts(first.dropLast(1)))
        assertFalse(token.accepts(null))

        val second = token.renew()
        assertNotEquals(first, second)
        assertFalse("a new token revokes the old one", token.accepts(first))
        assertTrue(token.accepts(second))
    }

    @Test
    fun `turning triggers off revokes the token`() {
        val issued = token.renew()

        token.turnOff()

        assertNull(token.current())
        assertFalse(token.accepts(issued))
    }
}
