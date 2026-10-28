package com.yugahashimoto.andcode.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevServerTrackerTest {
    private fun DevServerTracker.up(vararg ports: Int) = applyProbe(ports.associateWith { true })

    private fun DevServerTracker.down(vararg ports: Int) = applyProbe(ports.associateWith { false })

    @Test
    fun `a port that was only mentioned is probed but not shown`() {
        val tracker = DevServerTracker().observe(listOf(8080))

        assertEquals(listOf(8080), tracker.ports)
        assertTrue(tracker.servers.isEmpty())
    }

    @Test
    fun `a port is shown as running once it answers`() {
        val tracker = DevServerTracker().observe(listOf(5173)).up(5173)

        assertEquals(listOf(DevServer(5173, DevServerStatus.RUNNING)), tracker.servers)
    }

    @Test
    fun `one missed probe does not stop a running server`() {
        val tracker = DevServerTracker().observe(listOf(5173)).up(5173).down(5173)

        assertEquals(DevServerStatus.RUNNING, tracker.servers.single().status)
    }

    @Test
    fun `two missed probes in a row mark it stopped`() {
        val tracker = DevServerTracker().observe(listOf(5173)).up(5173).down(5173).down(5173)

        assertEquals(DevServerStatus.STOPPED, tracker.servers.single().status)
    }

    @Test
    fun `a stopped server runs again when it answers again`() {
        val tracker = DevServerTracker().observe(listOf(5173)).up(5173).down(5173).down(5173).up(5173)

        assertEquals(DevServerStatus.RUNNING, tracker.servers.single().status)
    }

    @Test
    fun `a port that never answered is forgotten after a while`() {
        var tracker = DevServerTracker().observe(listOf(8080))
        repeat(DevServerTracker.DROP_AFTER_MISSES - 1) { tracker = tracker.down(8080) }
        assertEquals(listOf(8080), tracker.ports)

        tracker = tracker.down(8080)

        assertTrue(tracker.ports.isEmpty())
    }

    @Test
    fun `a stopped server disappears after a longer while`() {
        var tracker = DevServerTracker().observe(listOf(5173)).up(5173)
        repeat(DevServerTracker.DROP_AFTER_MISSES) { tracker = tracker.down(5173) }

        assertTrue(tracker.ports.isEmpty())
        assertTrue(tracker.servers.isEmpty())
    }

    @Test
    fun `mentioning a known port again changes nothing`() {
        val before = DevServerTracker().observe(listOf(5173)).up(5173)

        val after = before.observe(listOf(5173))

        assertEquals(before.servers, after.servers)
        assertEquals(before.ports, after.ports)
    }

    @Test
    fun `servers keep the order in which they were first seen`() {
        val tracker = DevServerTracker().observe(listOf(3000, 5173)).up(5173, 3000)

        assertEquals(listOf(3000, 5173), tracker.servers.map { it.port })
    }

    @Test
    fun `tracks no more than the limit, keeping the newest candidates`() {
        val many = (1..DevServerTracker.MAX_TRACKED + 5).map { 3000 + it }

        val tracker = DevServerTracker().observe(many)

        assertEquals(many.takeLast(DevServerTracker.MAX_TRACKED), tracker.ports)
    }

    @Test
    fun `a new mention pushes out the oldest candidate that never answered`() {
        // A fragment of an address cut by a chunk boundary names a port nobody listens on; it must not
        // keep the real server's slot for half a minute.
        val ports = (1..DevServerTracker.MAX_TRACKED).map { 3000 + it }
        val tracker = DevServerTracker().observe(ports).up(3002)

        val next = tracker.observe(listOf(9000))

        assertTrue(3001 !in next.ports)
        assertTrue(3002 in next.ports)
        assertTrue(9000 in next.ports)
        assertEquals(DevServerTracker.MAX_TRACKED, next.ports.size)
    }

    @Test
    fun `a server that answered is never pushed out by a new mention`() {
        val ports = (1..DevServerTracker.MAX_TRACKED).map { 3000 + it }
        val tracker = DevServerTracker().observe(ports).up(*ports.toIntArray())

        val next = tracker.observe(listOf(9000))

        assertEquals(ports, next.ports)
        assertEquals(ports, next.servers.map { it.port })
    }

    @Test
    fun `a stopped server is not pushed out either`() {
        val ports = (1..DevServerTracker.MAX_TRACKED).map { 3000 + it }
        val tracker = DevServerTracker().observe(ports).up(*ports.toIntArray()).down(3001).down(3001)

        val next = tracker.observe(listOf(9000))

        assertTrue(3001 in next.ports)
        assertTrue(9000 !in next.ports)
    }

    @Test
    fun `a freed slot can be taken by a new port`() {
        var tracker = DevServerTracker().observe((1..DevServerTracker.MAX_TRACKED).map { 3000 + it })
        repeat(DevServerTracker.DROP_AFTER_MISSES) { tracker = tracker.down(3001) }

        tracker = tracker.observe(listOf(9000))

        assertTrue(9000 in tracker.ports)
        assertTrue(3001 !in tracker.ports)
    }

    @Test
    fun `a probe result for a port that is not tracked is ignored`() {
        val tracker = DevServerTracker().up(1234)

        assertTrue(tracker.ports.isEmpty())
        assertTrue(tracker.servers.isEmpty())
    }

    @Test
    fun `the server url opens the port on this device`() {
        assertEquals("http://localhost:5173/", DevServer(5173, DevServerStatus.RUNNING).url)
    }
}
