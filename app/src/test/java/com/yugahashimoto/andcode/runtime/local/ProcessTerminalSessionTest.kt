package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * These drive a real `sh` over pipes. A pipe is not a pty, so what is tested is the session's own
 * job - streaming output, forwarding input and ending cleanly - not terminal semantics, which the
 * sandbox's `script` provides.
 *
 * Scripts end in `exec` so no grandchild outlives the shell and keeps the output pipe open.
 */
class ProcessTerminalSessionTest {
    private val shell = File("/bin/sh")

    private fun open(
        script: String,
        killTree: (Process) -> Unit = {},
    ): ProcessTerminalSession {
        assumeTrue("needs a POSIX shell", shell.canExecute())
        return ProcessTerminalSession(ProcessBuilder(shell.path, "-c", script).redirectErrorStream(true).start(), killTree)
    }

    private fun CoroutineScope.collect(
        session: ProcessTerminalSession,
        sink: MutableList<String>,
    ) = launch { session.output.collect { sink += it } }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `streams output while the process is still running`() =
        runBlocking(Dispatchers.IO) {
            val session = open("echo first; exec sleep 30")
            try {
                val chunks = CopyOnWriteArrayList<String>()
                collect(session, chunks)

                waitUntil { chunks.joinToString("").contains("first") }

                assertTrue("output arrives before the process ends", session.isAlive)
            } finally {
                session.close()
            }
        }

    @Test
    fun `forwards input to the running process`() =
        runBlocking(Dispatchers.IO) {
            val session = open("read name; echo hello \$name; exec sleep 30")
            try {
                val chunks = CopyOnWriteArrayList<String>()
                collect(session, chunks)

                session.write("world\n")

                waitUntil { chunks.joinToString("").contains("hello world") }
            } finally {
                session.close()
            }
        }

    @Test
    fun `keeps input in order`() =
        runBlocking(Dispatchers.IO) {
            val session = open("read a; read b; read c; echo \$a\$b\$c; exec sleep 30")
            try {
                val chunks = CopyOnWriteArrayList<String>()
                collect(session, chunks)

                session.write("1\n")
                session.write("2\n")
                session.write("3\n")

                waitUntil { chunks.joinToString("").contains("123") }
            } finally {
                session.close()
            }
        }

    @Test
    fun `reports the exit code once the process ends on its own`() =
        runBlocking(Dispatchers.IO) {
            val session = open("echo bye; exit 7")
            try {
                val exit = withTimeout(10_000) { session.exitCode.first { it != null } }

                assertEquals(7, exit)
                assertFalse(session.isAlive)
            } finally {
                session.close()
            }
        }

    @Test
    fun `the output flow completes when the process ends`() =
        runBlocking(Dispatchers.IO) {
            val session = open("echo done")
            val chunks = CopyOnWriteArrayList<String>()

            withTimeout(10_000) { session.output.collect { chunks += it } }

            assertTrue(chunks.joinToString("").contains("done"))
        }

    @Test
    fun `merges error output into the same stream`() =
        runBlocking(Dispatchers.IO) {
            val session = open("echo oops 1>&2")
            try {
                val chunks = CopyOnWriteArrayList<String>()
                collect(session, chunks)

                waitUntil { chunks.joinToString("").contains("oops") }
            } finally {
                session.close()
            }
        }

    @Test
    fun `close stops a process that never ends`() =
        runBlocking(Dispatchers.IO) {
            val session = open("exec sleep 60")

            session.close()

            val exit = withTimeout(10_000) { session.exitCode.first { it != null } }
            assertNotNull(exit)
            assertFalse(session.isAlive)
        }

    @Test
    fun `close is idempotent and writes after close are dropped`() =
        runBlocking(Dispatchers.IO) {
            val session = open("exec sleep 60")

            session.close()
            session.close()
            session.write("ignored\n")

            // Destroying the process is asynchronous, so wait for the exit before asking about it.
            withTimeout(10_000) { session.exitCode.first { it != null } }
            assertFalse(session.isAlive)
        }

    @Test
    fun `decodes multi byte characters`() =
        runBlocking(Dispatchers.IO) {
            val session = open("printf '日本語'")
            try {
                val chunks = CopyOnWriteArrayList<String>()
                collect(session, chunks)

                waitUntil { chunks.joinToString("").contains("日本語") }
            } finally {
                session.close()
            }
        }

    @Test
    fun `sends multi byte input as utf-8`() =
        runBlocking(Dispatchers.IO) {
            val session = open("read word; echo \"[\$word]\"; exec sleep 30")
            try {
                val chunks = CopyOnWriteArrayList<String>()
                collect(session, chunks)

                session.write("日本語\n")

                waitUntil { chunks.joinToString("").contains("[日本語]") }
            } finally {
                session.close()
            }
        }

    @Test
    fun `close ends the whole process tree before destroying the root`() =
        runBlocking(Dispatchers.IO) {
            val killed = CopyOnWriteArrayList<Boolean>()
            // The root has to still be there to be able to walk its children.
            val session = open("exec sleep 60", killTree = { process -> killed += process.isAlive })

            session.close()

            withTimeout(10_000) { session.exitCode.first { it != null } }
            assertEquals(listOf(true), killed.toList())
        }

    @Test
    fun `a failing tree walk still destroys the root`() =
        runBlocking(Dispatchers.IO) {
            val session = open("exec sleep 60", killTree = { error("no /proc today") })

            session.close()

            val exit = withTimeout(10_000) { session.exitCode.first { it != null } }
            assertNotNull(exit)
            assertFalse(session.isAlive)
        }

    @Test
    fun `close never blocks the caller on the tree walk`() =
        runBlocking(Dispatchers.IO) {
            val release = java.util.concurrent.CountDownLatch(1)
            val session = open("exec sleep 60", killTree = { release.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            try {
                val started = System.nanoTime()
                session.close()
                val tookMillis = (System.nanoTime() - started) / 1_000_000

                assertTrue("close() took $tookMillis ms", tookMillis < 2_000)
            } finally {
                release.countDown()
            }
            assertNotNull(withTimeout(10_000) { session.exitCode.first { it != null } })
        }
}
