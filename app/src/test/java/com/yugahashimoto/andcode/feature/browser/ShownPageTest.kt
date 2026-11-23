package com.yugahashimoto.andcode.feature.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

class ShownPageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the first run takes its own baseline, and later runs compare against what the page shows`() {
        val page = ShownPage()

        val first = page.startRun()
        assertNull(first.shown)
        first.onShown(5L)

        assertEquals(5L, page.startRun().shown)
    }

    /** A restart before any run reported cannot know what the page shows, so it reloads. */
    @Test
    fun `a later run with nothing recorded compares against an unknown page`() {
        val page = ShownPage()
        page.startRun()

        assertEquals(UNKNOWN_FINGERPRINT, page.startRun().shown)
    }

    /** An old run's scan can finish after the newer run's: what it read is not what the page shows now. */
    @Test
    fun `a run that a newer one replaced reports nothing`() {
        val page = ShownPage()
        val old = page.startRun()
        val current = page.startRun()

        current.onShown(9L)
        old.onShown(3L)

        assertEquals(9L, page.startRun().shown)
    }

    /**
     * The app goes to the background during the first scan and comes back before that scan finishes:
     * the agent may have changed the files meanwhile, so the restarted watch reloads the page, and the
     * old scan's late report changes nothing.
     */
    @Test
    fun `a watch restarted before the old scan reports reloads the page`() =
        runBlocking {
            val root = temporaryFolder.newFolder().apply { File(this, "index.html").writeText("a") }
            val page = ShownPage()
            val scanning = CountDownLatch(1)
            val release = CountDownLatch(1)

            val oldRun = page.startRun()
            val old =
                launch(Dispatchers.Default) {
                    watchWorkspaceChanges(
                        root,
                        shown = oldRun.shown,
                        intervalMillis = 60_000,
                        onShown = oldRun.onShown,
                        fingerprint = {
                            scanning.countDown()
                            release.await()
                            workspaceFingerprint(it)
                        },
                    ) {}
                }
            scanning.await()
            old.cancel()
            File(root, "index.html").writeText("changed while the app was away")

            val newRun = page.startRun()
            val reloaded = CompletableDeferred<Unit>()
            val current =
                launch {
                    watchWorkspaceChanges(root, shown = newRun.shown, intervalMillis = 60_000, onShown = newRun.onShown) {
                        reloaded.complete(Unit)
                    }
                }
            try {
                withTimeout(5_000) { reloaded.await() }
            } finally {
                // Also on a timeout: the old scan cannot be cancelled, and the test would wait for it forever.
                release.countDown()
                current.cancel()
            }
            old.join()

            assertEquals(workspaceFingerprint(root), page.startRun().shown)
        }
}
