package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.feature.chat.RestoreResult
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
            assertEquals(RestoreResult.Refused("data"), snapshots.restoreChecked(workspace.path, before))
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
    fun `a file ignored after it was snapshotted is never written back`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, ".env").writeText("OLD=1\n")
            File(workspace, "app.txt").writeText("v1\n")
            val withEnv = requireNotNull(snapshots.snapshot(workspace.path))
            File(workspace, ".gitignore").writeText(".env\n")
            File(workspace, ".env").writeText("NEW=2\n")
            requireNotNull(snapshots.snapshot(workspace.path))

            assertNull("a target that holds a now-ignored file is refused", snapshots.restore(workspace.path, withEnv))
            assertEquals(RestoreResult.Refused(".env"), snapshots.restoreChecked(workspace.path, withEnv))
            assertEquals("NEW=2\n", File(workspace, ".env").readText())

            File(workspace, "app.txt").writeText("v2\n")
            val later = requireNotNull(snapshots.snapshot(workspace.path))
            File(workspace, "app.txt").writeText("v3\n")
            assertNotNull(snapshots.restore(workspace.path, later))
            assertEquals("v2\n", File(workspace, "app.txt").readText())
            assertEquals("NEW=2\n", File(workspace, ".env").readText())

            // Moved aside, as the refusal asks, nothing is in the way any more.
            val aside = File(workspace.parentFile, "env-aside")
            File(workspace, ".env").renameTo(aside)
            assertNotNull(snapshots.restore(workspace.path, withEnv))
            assertEquals("v1\n", File(workspace, "app.txt").readText())
            assertEquals("NEW=2\n", aside.readText())
        }

    private fun gitIn(
        dir: File,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        process.inputStream.readBytes()
        assertEquals("git ${args.joinToString(" ")}", 0, process.waitFor())
    }

    /** An agent ran `git init` (or a scaffolder) in a subfolder and has not committed yet. */
    @Test
    fun `a nested repository with no commit yet does not stop the snapshot`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, "top.txt").writeText("top\n")
            val app = File(workspace, "app").apply { mkdirs() }
            gitIn(app, "init", "-q")
            File(app, "main.js").writeText("one\n")

            val before = assertNotNullAnd(snapshots.snapshot(workspace.path))
            File(app, "main.js").writeText("two\n")
            val after = assertNotNullAnd(snapshots.snapshot(workspace.path))

            val changes = requireNotNull(snapshots.diff(workspace.path, before, after))
            assertEquals(listOf("app/main.js"), changes.map { it.displayPath })
            assertNotNull("restored", snapshots.restore(workspace.path, before))
            assertEquals("one\n", File(app, "main.js").readText())
            assertTrue("the nested repository is left in place", File(app, ".git").isDirectory)
        }

    /** A workspace opened on a folder that holds several clones. */
    @Test
    fun `files inside a nested repository with commits are diffed and restored like any other`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            val lib = File(workspace, "libs/core").apply { mkdirs() }
            gitIn(lib, "init", "-q")
            File(lib, "core.kt").writeText("val x = 1\n")
            File(lib, ".gitignore").writeText("secret.env\n")
            gitIn(lib, "add", ".")
            gitIn(lib, "commit", "-qm", "init")
            val nestedHead = File(lib, ".git/HEAD").readText()

            val before = assertNotNullAnd(snapshots.snapshot(workspace.path))
            File(lib, "core.kt").writeText("val x = 2\n")
            File(lib, "added.kt").writeText("new\n")
            File(lib, "secret.env").writeText("token\n")
            val after = assertNotNullAnd(snapshots.snapshot(workspace.path))

            val changes = requireNotNull(snapshots.diff(workspace.path, before, after))
            assertEquals(setOf("libs/core/core.kt", "libs/core/added.kt"), changes.map { it.displayPath }.toSet())

            assertNotNull("restored", snapshots.restore(workspace.path, before))
            assertEquals("val x = 1\n", File(lib, "core.kt").readText())
            assertFalse(File(lib, "added.kt").exists())
            assertEquals("the nested repository's own ignored file stays", "token\n", File(lib, "secret.env").readText())
            assertEquals("its git data is never touched", nestedHead, File(lib, ".git/HEAD").readText())
        }

    @Test
    fun `a nested repository the workspace ignores stays out of snapshots`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            File(workspace, ".gitignore").writeText("vendor/\n")
            val vendor = File(workspace, "vendor/thing").apply { mkdirs() }
            gitIn(vendor, "init", "-q")
            File(vendor, "big.c").writeText("int x;\n")
            File(workspace, "top.txt").writeText("top\n")

            val before = assertNotNullAnd(snapshots.snapshot(workspace.path))
            File(vendor, "big.c").writeText("int y;\n")
            File(workspace, "top.txt").writeText("changed\n")
            val after = assertNotNullAnd(snapshots.snapshot(workspace.path))

            val changes = requireNotNull(snapshots.diff(workspace.path, before, after))
            assertEquals(listOf("top.txt"), changes.map { it.displayPath })
        }

    @Test
    fun `a repository inside a nested repository, with a space in its name, is snapshotted too`() =
        runBlocking {
            val (snapshots, workspace) = fixture()
            val outer = File(workspace, "outer repo").apply { mkdirs() }
            gitIn(outer, "init", "-q")
            File(outer, "o.txt").writeText("o1\n")
            val inner = File(outer, "packages/inner one").apply { mkdirs() }
            gitIn(inner, "init", "-q")
            File(inner, "i.txt").writeText("i1\n")

            val before = assertNotNullAnd(snapshots.snapshot(workspace.path))
            File(outer, "o.txt").writeText("o2\n")
            File(inner, "i.txt").writeText("i2\n")
            val after = assertNotNullAnd(snapshots.snapshot(workspace.path))

            val changes = requireNotNull(snapshots.diff(workspace.path, before, after))
            assertEquals(setOf("outer repo/o.txt", "outer repo/packages/inner one/i.txt"), changes.map { it.displayPath }.toSet())
            assertNotNull("restored", snapshots.restore(workspace.path, before))
            assertEquals("o1\n", File(outer, "o.txt").readText())
            assertEquals("i1\n", File(inner, "i.txt").readText())
        }

    private fun assertNotNullAnd(value: String?): String {
        assertNotNull("the snapshot was taken", value)
        return value!!
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
    fun `a workspace that holds the histories is never snapshotted`() =
        runBlocking {
            val (snapshots, _) = fixture()
            assertNull(snapshots.snapshot(temp.root.path))
            assertTrue(ShadowSnapshots.containsHistories(ShadowSnapshots.ROOT, "/root"))
            assertTrue(ShadowSnapshots.containsHistories(ShadowSnapshots.ROOT, "/"))
            assertFalse(ShadowSnapshots.containsHistories(ShadowSnapshots.ROOT, "/root/project"))
            assertFalse(ShadowSnapshots.containsHistories(ShadowSnapshots.ROOT, "/ro"))
        }

    @Test
    fun `after a snapshot runs out of time, sends skip that folder for a while`() =
        runBlocking {
            var now = 0L
            var calls = 0
            val snapshots =
                ShadowSnapshots(
                    { _, _, _ ->
                        calls++
                        LocalRuntimeCommandResult(exitCode = 124, output = "timed out")
                    },
                    Dispatchers.Unconfined,
                    root = "/snap",
                    clock = { now },
                )

            assertNull(snapshots.snapshot("/workspace/big", lockWaitMillis = 100))
            assertNull(snapshots.snapshot("/workspace/big", lockWaitMillis = 100))
            assertEquals("the second send did not wait for another attempt", 1, calls)

            now += 6 * 60_000L
            snapshots.snapshot("/workspace/big", lockWaitMillis = 100)
            assertEquals(2, calls)
        }

    @Test
    fun `each workspace gets its own history under the root`() {
        val a = ShadowSnapshots.gitDir("/snap", "/workspace/a")
        val b = ShadowSnapshots.gitDir("/snap", "/workspace/b")
        assertTrue(a.startsWith("/snap/"))
        assertTrue(a != b)
        assertEquals(a, ShadowSnapshots.gitDir("/snap", "/workspace/a"))
    }

    @Test
    fun `old snapshots are dropped once a workspace has more than it keeps`() =
        runBlocking {
            assumeTrue("git is needed for these tests", gitAvailable())
            val workspace = temp.newFolder("pruned")
            val root = temp.newFolder("pruned-snapshots")
            val snapshots = ShadowSnapshots(shell(), Dispatchers.Unconfined, root = root.path, keep = 2, pruneSlack = 1, pruneEvery = 1)
            val ids =
                (1..4).map { n ->
                    File(workspace, "a.txt").writeText("v$n\n")
                    requireNotNull(snapshots.snapshot(workspace.path))
                }

            val gitDir = ShadowSnapshots.gitDir(root.path, workspace.path)
            val refs =
                shell()(
                    "git --git-dir='$gitDir' for-each-ref refs/snapshots --format='%(objectname)'",
                    10,
                    4_000,
                ).output.lines().filter(String::isNotBlank)
            assertEquals(2, refs.size)
            assertTrue("the newest snapshot is kept", ids.last() in refs)
            File(workspace, "a.txt").writeText("changed\n")
            assertNotNull(snapshots.restore(workspace.path, ids.last()))
            assertEquals("v4\n", File(workspace, "a.txt").readText())
        }

    @Test
    fun `a history from before snapshots stood alone keeps its newest commits as snapshots`() =
        runBlocking {
            assumeTrue("git is needed for these tests", gitAvailable())
            val workspace = temp.newFolder("legacy")
            val root = temp.newFolder("legacy-snapshots")
            val gitDir = ShadowSnapshots.gitDir(root.path, workspace.path)
            val git = ShadowSnapshots.git(root.path, workspace.path)
            // The old layout: one chain of commits on HEAD and no refs/snapshots at all.
            assertEquals(
                0,
                shell()("mkdir -p '$gitDir' && git init -q --bare '$gitDir' && git --git-dir='$gitDir' config core.bare false", 10, 4_000)
                    .exitCode,
            )
            val legacy =
                (1..3).map { n ->
                    File(workspace, "a.txt").writeText("v$n\n")
                    val result = shell()("$git add -A -- ':/' && $git commit -q -m legacy && $git rev-parse HEAD", 10, 4_000)
                    result.output.lines().last(String::isNotBlank).trim()
                }

            val snapshots = ShadowSnapshots(shell(), Dispatchers.Unconfined, root = root.path, keep = 2, pruneEvery = 1_000)
            File(workspace, "a.txt").writeText("v4\n")
            val newest = requireNotNull(snapshots.snapshot(workspace.path))

            val refs =
                shell()("git --git-dir='$gitDir' for-each-ref refs/snapshots --format='%(objectname)'", 10, 4_000)
                    .output.lines().filter(String::isNotBlank).toSet()
            assertEquals(setOf(legacy[1], legacy[2], newest), refs)
            File(workspace, "a.txt").writeText("changed\n")
            assertNotNull(snapshots.restore(workspace.path, legacy[2]))
            assertEquals("v3\n", File(workspace, "a.txt").readText())
        }

    @Test
    fun `a deleted workspace's history is removed`() =
        runBlocking {
            assumeTrue("git is needed for these tests", gitAvailable())
            val root = temp.newFolder("swept-snapshots")
            val snapshots = ShadowSnapshots(shell(), Dispatchers.Unconfined, root = root.path)
            val gone = temp.newFolder("gone")
            File(gone, "a.txt").writeText("a\n")
            requireNotNull(snapshots.snapshot(gone.path))
            val goneHistory = File(ShadowSnapshots.gitDir(root.path, gone.path))
            assertTrue(goneHistory.isDirectory)
            gone.deleteRecursively()

            val kept = temp.newFolder("kept")
            File(kept, "b.txt").writeText("b\n")
            requireNotNull(snapshots.snapshot(kept.path))

            assertFalse(goneHistory.exists())
            assertTrue(File(ShadowSnapshots.gitDir(root.path, kept.path)).isDirectory)
        }
}
