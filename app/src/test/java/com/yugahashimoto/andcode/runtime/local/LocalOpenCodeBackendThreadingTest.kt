package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeApiClient
import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

/**
 * Every call on the backend resolves the local server first, and that reads runtime metadata and the
 * password file from disk. ChatViewModel calls it from the main thread, where a slow disk became an
 * ANR in Crashlytics, so the reads must happen off the caller's thread.
 */
class LocalOpenCodeBackendThreadingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reads the port and password off the calling thread`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val caller = Thread.currentThread()
            var portThread: Thread? = null
            var passwordThread: Thread? = null
            val backend =
                LocalOpenCodeBackend(
                    portProvider = {
                        portThread = Thread.currentThread()
                        server.port
                    },
                    passwordProvider = {
                        passwordThread = Thread.currentThread()
                        "secret"
                    },
                    backendFactory = { profile ->
                        val pinned = profile.copy(baseUrl = server.url("/").toString())
                        RemoteOpenCodeBackend(pinned, OpenCodeApiClient(pinned))
                    },
                )

            backend.answerQuestion("q-1", emptyList(), "/workspace/repo")

            assertNotNull(portThread)
            assertNotNull(passwordThread)
            assertNotSame(caller, portThread)
            assertNotSame(caller, passwordThread)
        }
}
