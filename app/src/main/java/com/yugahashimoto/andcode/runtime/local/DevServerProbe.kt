package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Tells whether something on this device is accepting connections on a port. */
fun interface DevServerProbe {
    suspend fun isListening(port: Int): Boolean
}

/**
 * Connects to the port on the loopback addresses and hangs up. The guest's processes share the app's
 * network, so a server started in the terminal is reachable here without any permission - unlike
 * `/proc/net/tcp`, which apps cannot read since Android 10.
 *
 * Both address families are tried because a server that binds `localhost` may get either, depending on
 * how the guest resolves the name.
 */
class LoopbackDevServerProbe(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : DevServerProbe {
    override suspend fun isListening(port: Int): Boolean = withContext(Dispatchers.IO) { LOOPBACK_ADDRESSES.any { connects(it, port) } }

    private fun connects(
        address: String,
        port: Int,
    ): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(address, port), timeoutMillis) }
            true
        } catch (_: IOException) {
            false
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 300
        val LOOPBACK_ADDRESSES = listOf("127.0.0.1", "::1")
    }
}
