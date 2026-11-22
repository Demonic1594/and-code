package com.yugahashimoto.andcode.feature.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

class WorkspaceFingerprintTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `fingerprint changes when a web file changes`() {
        val root = temporaryFolder.newFolder()
        val page = File(root, "index.html").apply { writeText("a") }
        val before = workspaceFingerprint(root)

        page.writeText("a longer body")

        assertNotEquals(before, workspaceFingerprint(root))
    }

    @Test
    fun `ignores vendored and non-web files`() {
        val root = temporaryFolder.newFolder()
        File(root, "index.html").writeText("a")
        val before = workspaceFingerprint(root)

        File(root, "node_modules").mkdirs()
        File(root, "node_modules/x.js").writeText("big change")
        File(root, "README.md").writeText("docs")

        assertEquals(before, workspaceFingerprint(root))
    }

    /** The guest could link a folder to its parent: the walk must still end, without the linked copy. */
    @Test
    fun `a folder linked back into the workspace is not walked`() {
        val root = temporaryFolder.newFolder()
        File(root, "index.html").writeText("a")
        val before = workspaceFingerprint(root)

        java.nio.file.Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())

        assertEquals(before, workspaceFingerprint(root))
    }

    /**
     * The watch stops while the app is in the background; the agent may change the site meanwhile.
     * The page on screen still shows the old files, so it reloads as soon as the watch starts again.
     */
    @Test
    fun `files changed while nothing watched reload the page when the watch starts again`() =
        runBlocking {
            val root = temporaryFolder.newFolder()
            val page = File(root, "index.html").apply { writeText("a") }
            val shown = workspaceFingerprint(root)
            page.writeText("a longer body")

            val reloaded = CompletableDeferred<Unit>()
            val reported = mutableListOf<Long>()
            val watch =
                launch {
                    watchWorkspaceChanges(root, shown = shown, intervalMillis = 60_000, onShown = { reported += it }) {
                        reloaded.complete(Unit)
                    }
                }
            withTimeout(5_000) { reloaded.await() }
            watch.cancel()

            assertEquals(listOf(workspaceFingerprint(root)), reported)
        }

    @Test
    fun `a page already showing the current files is not reloaded when the watch starts`() =
        runBlocking {
            val root = temporaryFolder.newFolder()
            File(root, "index.html").writeText("a")
            val baseline = CompletableDeferred<Long>()
            var reloads = 0

            val watch =
                launch {
                    watchWorkspaceChanges(
                        root,
                        shown = workspaceFingerprint(root),
                        intervalMillis = 60_000,
                        onShown = { baseline.complete(it) },
                    ) {
                        reloads++
                    }
                }
            assertEquals(workspaceFingerprint(root), withTimeout(5_000) { baseline.await() })
            watch.cancel()

            assertEquals(0, reloads)
        }

    /**
     * The app going to the background during the first scan: the agent may change the files after the
     * page loaded and before the scan reads them, so that scan cannot stand for the page, and the next
     * watch reloads it.
     */
    @Test
    fun `a first scan the watch stopped during makes the next watch reload`() =
        runBlocking {
            val root = temporaryFolder.newFolder().apply { File(this, "index.html").writeText("a") }
            val scanning = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reported = CompletableDeferred<Long>()

            val watch =
                launch(Dispatchers.Default) {
                    watchWorkspaceChanges(
                        root,
                        intervalMillis = 60_000,
                        onShown = { reported.complete(it) },
                        fingerprint = {
                            scanning.countDown()
                            release.await()
                            workspaceFingerprint(it)
                        },
                    ) {}
                }
            scanning.await()
            watch.cancel()
            release.countDown()
            val shown = withTimeout(5_000) { reported.await() }
            assertEquals(UNKNOWN_FINGERPRINT, shown)

            val reloaded = CompletableDeferred<Unit>()
            val again = launch { watchWorkspaceChanges(root, shown = shown, intervalMillis = 60_000) { reloaded.complete(Unit) } }
            withTimeout(5_000) { reloaded.await() }
            again.cancel()
        }
}
