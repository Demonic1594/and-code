package com.yugahashimoto.andcode.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevServerDetectorTest {
    private fun portsIn(vararg lines: String): List<Int> = DevServerDetector.portsIn(lines.toList())

    @Test
    fun `finds the port in a Vite banner`() {
        assertEquals(listOf(5173), portsIn("  ➜  Local:   http://localhost:5173/"))
    }

    @Test
    fun `finds loopback and any-address urls`() {
        assertEquals(listOf(3000), portsIn("ready - started server on http://127.0.0.1:3000"))
        assertEquals(listOf(8000), portsIn("Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ..."))
        assertEquals(listOf(8000), portsIn("Serving HTTP on :: port 8000 (http://[::]:8000/) ..."))
        assertEquals(listOf(4000), portsIn("Listening on http://[::1]:4000"))
    }

    @Test
    fun `finds a listening message with or without a host`() {
        assertEquals(listOf(3000), portsIn("Server listening on port 3000"))
        assertEquals(listOf(8080), portsIn("Listening on :8080"))
        assertEquals(listOf(8080), portsIn("listening at 0.0.0.0:8080"))
        assertEquals(listOf(9000), portsIn("App is listening on localhost:9000"))
    }

    @Test
    fun `ignores addresses that are not on this device`() {
        assertTrue(portsIn("  ➜  Network: http://192.168.1.20:5173/").isEmpty())
        assertTrue(portsIn("see https://example.com:8443/docs").isEmpty())
        assertTrue(portsIn("listening on 192.168.1.20:3000").isEmpty())
    }

    @Test
    fun `ignores ports that cannot exist`() {
        assertTrue(portsIn("http://localhost:0/").isEmpty())
        assertTrue(portsIn("http://localhost:65536/").isEmpty())
        assertTrue(portsIn("http://localhost:99999999999/").isEmpty())
    }

    @Test
    fun `ignores text with no address`() {
        assertTrue(portsIn("", "$ npm run dev", "compiled successfully in 412 ms", "port 3000 is a number").isEmpty())
    }

    @Test
    fun `lists each port once in the order it first appeared`() {
        val found =
            portsIn(
                "Local: http://localhost:5173/",
                "API: http://localhost:3000/",
                "Local: http://localhost:5173/",
                "listening on port 3000",
            )

        assertEquals(listOf(5173, 3000), found)
    }

    @Test
    fun `finds several addresses on one line`() {
        assertEquals(listOf(3000, 3001), portsIn("proxy http://localhost:3000 -> http://localhost:3001"))
    }

    @Test
    fun `is not case sensitive`() {
        assertEquals(listOf(3000), portsIn("HTTP://LOCALHOST:3000/"))
        assertEquals(listOf(3000), portsIn("LISTENING ON PORT 3000"))
    }
}
