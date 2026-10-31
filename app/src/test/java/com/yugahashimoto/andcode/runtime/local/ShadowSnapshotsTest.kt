package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the real scripts with the host's git against a temporary workspace that is not a repository. */
class ShadowSnapshotsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun shell(): (String, Long, Int) -> LocalRuntimeCommandResult =
        { command, timeout, _ ->
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(timeout, TimeUnit.SECONDS)
            LocalRuntimeCommandResult(process.exitValue(), output)
        }

    private fun gitAvailable(): Boolean = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun fixture(): Pair<ShadowSnapshots, File> {
        assumeTrue("git is needed for these tests", gitAvailable())
        val workspace = temp.newFolder("workspace")
        val snapshots = ShadowSnapshots(shell(), Dispatchers.Unconfined, root = temp.newFolder("snapshots").path)
        return snapshots to workspace
    }

    @Test
    fun `a turn's changes are diffed in a folder that is not a git repository`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, "a.txt").writeText("one\ntwo\n")
            val before = requireNotNull(snapshots.snapshot(workspace.path))
            File(workspace, "a.txt").writeText("one\nTWO\n")
            File(workspace, "new.txt").writeText("hello\n")
            val after = requireNotNull(snapshots.snapshot(workspace.path))

            val changes = requireNotNull(snapshots.diff(workspace.path, before, after))

            assertEquals(setOf("a.txt", "new.txt"), changes.map { it.displayPath }.toSet())
            val edited = changes.single { it.displayPath == "a.txt" }
            assertEquals(1, edited.added)
            assertEquals(1, edited.removed)
            assertTrue(edited.patch.orEmpty().contains("+TWO"))
            assertFalse("the workspace stays free of git", File(workspace, ".git").exists())
        }

    @Test
    fun `restore puts files back, removes new ones, and can itself be undone`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, "keep.txt").writeText("original\n")
            val before = requireNotNull(snapshots.snapshot(workspace.path))
            File(workspace, "keep.txt").writeText("changed by the agent\n")
            File(workspace, "added.txt").writeText("new\n")
            File(workspace, "node_modules").mkdirs()
            File(workspace, "node_modules/dep.js").writeText("installed\n")

            val undo = snapshots.restore(workspace.path, before)

            assertNotNull(undo)
            assertEquals("original\n", File(workspace, "keep.txt").readText())
            assertFalse(File(workspace, "added.txt").exists())
            assertEquals("excluded folders are left alone", "installed\n", File(workspace, "node_modules/dep.js").readText())

            snapshots.restore(workspace.path, requireNotNull(undo))

            assertEquals("changed by the agent\n", File(workspace, "keep.txt").readText())
            assertEquals("new\n", File(workspace, "added.txt").readText())
        }

    @Test
    fun `restore refuses rather than delete ignored files in the way`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, ".gitignore").writeText("*.env\n")
            File(workspace, "data").writeText("was a file\n")
            val before = requireNotNull(snapshots.snapshot(workspace.path))
            File(workspace, "data").delete()
            File(workspace, "data/node_modules/pkg").mkdirs()
            File(workspace, "data/node_modules/pkg/index.js").writeText("installed\n")
            File(workspace, "data/precious.env").writeText("SECRET=1\n")

            val undo = snapshots.restore(workspace.path, before)

            assertNull("the restore fails instead of wiping data/", undo)
            assertEquals("SECRET=1\n", File(workspace, "data/precious.env").readText())
            assertEquals("installed\n", File(workspace, "data/node_modules/pkg/index.js").readText())
        }

    @Test
    fun `a lock left by a killed command does not block later snapshots`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, "a.txt").writeText("one\n")
            requireNotNull(snapshots.snapshot(workspace.path))
            val lock = File(ShadowSnapshots.gitDir(File(temp.root, "snapshots").path, workspace.path), "index.lock")
            lock.writeText("")
            lock.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000)
            File(workspace, "a.txt").writeText("two\n")

            assertNotNull(snapshots.snapshot(workspace.path))
        }

    @Test
    fun `excluded folders never enter a snapshot`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            val before = requireNotNull(snapshots.snapshot(workspace.path))
            File(workspace, "build").mkdirs()
            File(workspace, "build/out.bin").writeText("artifact\n")
            File(workspace, "src.kt").writeText("fun main() {}\n")
            val after = requireNotNull(snapshots.snapshot(workspace.path))

            val changes = requireNotNull(snapshots.diff(workspace.path, before, after))

            assertEquals(listOf("src.kt"), changes.map { it.displayPath })
        }

    @Test
    fun `ids that are not snapshot hashes are refused before reaching the shell`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            assertNull(snapshots.diff(workspace.path, "HEAD; rm -rf /", "abc"))
            assertNull(snapshots.restore(workspace.path, "--hard"))
        }

    @Test
    fun `each workspace gets its own history under the root`() {
        val a = ShadowSnapshots.gitDir("/snap", "/workspace/a")
        val b = ShadowSnapshots.gitDir("/snap", "/workspace/b")
        assertTrue(a.startsWith("/snap/"))
        assertTrue(a != b)
        assertEquals(a, ShadowSnapshots.gitDir("/snap", "/workspace/a"))
    }
}
