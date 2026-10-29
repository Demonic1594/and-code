package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LoopbackDevServerProbeTest {
    private val probe = LoopbackDevServerProbe(timeoutMillis = 500)

    @Test
    fun `sees a server listening on the loopback address`() =
        runTest {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                assertTrue(probe.isListening(server.localPort))
            }
        }

    @Test
    fun `sees a server that listens on every address`() =
        runTest {
            ServerSocket(0).use { server ->
                assertTrue(probe.isListening(server.localPort))
            }
        }

    @Test
    fun `finds nothing once the server is gone`() =
        runTest {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

            assertFalse(probe.isListening(port))
        }
}
