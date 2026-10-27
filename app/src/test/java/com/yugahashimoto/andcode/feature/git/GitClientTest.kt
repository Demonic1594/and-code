package com.yugahashimoto.andcode.feature.git

import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs [GitClient] against a real repository through the host's `sh` and `git`, standing in for
 * the PRoot shell, so the flags, quoting and parsing are checked for real.
 */
class GitClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repo: File
    private val client =
        GitClient { command, timeout ->
            val process =
                ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .apply { environment()["HOME"] = temp.root.absolutePath }
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(timeout, TimeUnit.SECONDS)
            LocalRuntimeCommandResult(process.exitValue(), output)
        }

    @Test
    fun `only a stash reference is accepted as one`() {
        assertEquals("stash@{0}", GitClient.validStash("stash@{0}"))
        assertEquals("stash@{12}", GitClient.validStash(" stash@{12} "))
        listOf("stash@{}", "stash@{a}", "stash@{0}x", "stash@{0}; rm -rf /", "HEAD").forEach { assertNull(it, GitClient.validStash(it)) }
    }

    private fun sh(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return output
    }

    private val dir get() = repo.absolutePath

    @Before
    fun setUp() {
        assumeTrue("needs git on the test host", File("/usr/bin/git").exists() || File("/bin/git").exists())
        repo = temp.newFolder("repo with 'quote'")
        sh("git init -q -b main . && git config user.email t@example.com && git config user.name Test")
        File(repo, "a.txt").writeText("one\n")
        File(repo, "old name.txt").writeText("rename me\n")
        sh("git add -A && git commit -qm 'first commit'")
    }

    @Test
    fun `status separates staged, unstaged, untracked and renamed files`() =
        runBlocking {
            File(repo, "a.txt").writeText("one\ntwo\n")
            File(repo, "new file.kt").writeText("x\n")
            sh("git mv 'old name.txt' 'new name.txt'")
            File(repo, "staged.txt").writeText("s\n")
            sh("git add staged.txt")

            val status = client.status(dir).getOrThrow()

            assertEquals("main", status.branch)
            assertFalse(status.noCommits)
            val byPath = status.changes.associateBy { it.path }
            assertEquals(' ', byPath.getValue("a.txt").index)
            assertEquals('M', byPath.getValue("a.txt").worktree)
            assertTrue(byPath.getValue("new file.kt").untracked)
            assertEquals("old name.txt", byPath.getValue("new name.txt").originalPath)
            assertTrue(byPath.getValue("new name.txt").staged)
            assertEquals(setOf("new name.txt", "staged.txt"), status.staged.map { it.path }.toSet())
            assertEquals(setOf("a.txt", "new file.kt"), status.unstaged.map { it.path }.toSet())
        }

    @Test
    fun `staging, unstaging and committing move files through the index`() =
        runBlocking {
            File(repo, "a.txt").writeText("changed\n")
            File(repo, "b.txt").writeText("b\n")

            client.stage(dir, listOf("a.txt")).getOrThrow()
            assertEquals(listOf("a.txt"), client.status(dir).getOrThrow().staged.map { it.path })

            client.stageAll(dir).getOrThrow()
            assertEquals(2, client.status(dir).getOrThrow().staged.size)

            client.unstage(dir, listOf("b.txt"), noCommits = false).getOrThrow()
            assertEquals(listOf("a.txt"), client.status(dir).getOrThrow().staged.map { it.path })

            client.commit(dir, "Change a\n\nWith a body", amend = false).getOrThrow()
            val status = client.status(dir).getOrThrow()
            assertTrue(status.staged.isEmpty())
            assertEquals(listOf("b.txt"), status.unstaged.map { it.path })

            val log = client.log(dir).getOrThrow()
            assertEquals(listOf("Change a", "first commit"), log.map { it.subject })
            assertTrue(log.first().refs.any { it.contains("main") })
            assertEquals(1, log.first().parents.size)
        }

    @Test
    fun `amending replaces the last commit`() =
        runBlocking {
            File(repo, "a.txt").writeText("changed\n")
            client.stageAll(dir).getOrThrow()
            client.commit(dir, "typo", amend = false).getOrThrow()
            client.commit(dir, "Fixed message", amend = true).getOrThrow()

            assertEquals(listOf("Fixed message", "first commit"), client.log(dir).getOrThrow().map { it.subject })
            assertEquals("Fixed message", client.lastCommitMessage(dir).getOrThrow())
        }

    @Test
    fun `diffs cover staged, unstaged and untracked files`() =
        runBlocking {
            File(repo, "a.txt").writeText("one\nstaged\n")
            sh("git add a.txt")
            File(repo, "a.txt").writeText("one\nstaged\nunstaged\n")
            File(repo, "fresh.txt").writeText("brand new\n")
            val status = client.status(dir).getOrThrow().changes.associateBy { it.path }

            val staged = client.diff(dir, status.getValue("a.txt"), staged = true).getOrThrow()
            val unstaged = client.diff(dir, status.getValue("a.txt"), staged = false).getOrThrow()
            val untracked = client.diff(dir, status.getValue("fresh.txt"), staged = false).getOrThrow()

            assertTrue(staged, staged.contains("+staged") && !staged.contains("+unstaged"))
            assertTrue(unstaged, unstaged.contains("+unstaged") && !unstaged.contains("+staged"))
            assertTrue(untracked, untracked.contains("+brand new"))
        }

    @Test
    fun `commit details list files and their diffs`() =
        runBlocking {
            sh("git mv 'old name.txt' renamed.txt && echo z > z.txt && git add -A && git commit -qm 'Rename and add' -m 'Details here'")
            val head = client.log(dir).getOrThrow().first()

            val details = client.commitDetails(dir, head.hash).getOrThrow()

            assertEquals("Rename and add", details.commit.subject)
            assertEquals("Details here", details.body)
            val files = details.files.associateBy { it.path }
            assertEquals('R', files.getValue("renamed.txt").status)
            assertEquals("old name.txt", files.getValue("renamed.txt").originalPath)
            assertEquals('A', files.getValue("z.txt").status)
            assertTrue(client.commitFileDiff(dir, head.hash, "z.txt").getOrThrow().contains("+z"))
        }

    @Test
    fun `branches can be created, listed, switched and deleted`() =
        runBlocking {
            client.createBranch(dir, "feature/x").getOrThrow()
            var branches = client.branches(dir).getOrThrow()
            assertEquals("feature/x", branches.single { it.current }.name)

            client.checkout(dir, "main").getOrThrow()
            client.deleteBranch(dir, "feature/x", force = false).getOrThrow()
            branches = client.branches(dir).getOrThrow()
            assertEquals(listOf("main"), branches.map { it.name })
            assertTrue(client.createBranch(dir, "-f").exceptionOrNull() is InvalidRefException)
        }

    @Test
    fun `stashes are saved, listed, applied and dropped`() =
        runBlocking {
            File(repo, "a.txt").writeText("work in progress\n")
            client.stashPush(dir, "wip on a").getOrThrow()
            assertTrue(client.status(dir).getOrThrow().changes.isEmpty())

            val stash = client.stashes(dir).getOrThrow().single()
            assertEquals("stash@{0}", stash.ref)
            assertTrue(stash.message.contains("wip on a"))

            client.stashApply(dir, stash.ref, pop = false).getOrThrow()
            assertEquals("work in progress\n", File(repo, "a.txt").readText())
            client.stashDrop(dir, stash.ref).getOrThrow()
            assertTrue(client.stashes(dir).getOrThrow().isEmpty())
            assertTrue(client.stashDrop(dir, "stash@{0}; rm -rf /").exceptionOrNull() is InvalidRefException)
        }

    @Test
    fun `discard restores a tracked file and deletes an untracked one`() =
        runBlocking {
            File(repo, "a.txt").writeText("oops\n")
            File(repo, "junk.txt").writeText("junk\n")
            val changes = client.status(dir).getOrThrow().changes.associateBy { it.path }

            client.discard(dir, changes.getValue("a.txt")).getOrThrow()
            client.discard(dir, changes.getValue("junk.txt")).getOrThrow()

            assertEquals("one\n", File(repo, "a.txt").readText())
            assertFalse(File(repo, "junk.txt").exists())
        }

    @Test
    fun `a repository with no commits reports it and can unstage`() =
        runBlocking {
            val empty = temp.newFolder("empty")
            ProcessBuilder("sh", "-c", "git init -q -b trunk . && echo a > a.txt && git add a.txt").directory(empty).start().waitFor()

            val status = client.status(empty.absolutePath).getOrThrow()
            assertTrue(status.noCommits)
            assertEquals("trunk", status.branch)
            assertNull(status.upstream)

            client.unstageAll(empty.absolutePath, noCommits = true).getOrThrow()
            assertTrue(client.status(empty.absolutePath).getOrThrow().changes.single().untracked)
        }

    @Test
    fun `the branch header reads upstream and ahead or behind counts`() {
        val status = GitClient.parseStatus("## main...origin/main [ahead 2, behind 1]\u0000 M a.kt\u0000")
        assertEquals("origin/main", status.upstream)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(listOf("a.kt"), status.unstaged.map { it.path })
        assertNull(GitClient.parseStatus("## HEAD (no branch)\u0000").branch)
    }

    @Test
    fun `paths are literal, so brackets and stars touch only that file`() =
        runBlocking {
            File(repo, "u/[id]").mkdirs()
            File(repo, "u/i").mkdirs()
            File(repo, "u/[id]/p.ts").writeText("x\n")
            File(repo, "u/i/p.ts").writeText("keep\n")
            File(repo, "b*.log").writeText("x\n")
            File(repo, "bcd.log").writeText("keep\n")
            val changes = client.status(dir).getOrThrow().changes.associateBy { it.path }

            client.stage(dir, listOf("u/[id]/p.ts")).getOrThrow()
            assertEquals(listOf("u/[id]/p.ts"), client.status(dir).getOrThrow().staged.map { it.path })

            client.discard(dir, changes.getValue("b*.log")).getOrThrow()
            assertFalse(File(repo, "b*.log").exists())
            assertTrue(File(repo, "bcd.log").exists())
            assertTrue(File(repo, "u/i/p.ts").exists())
        }

    @Test
    fun `before the first commit a file edited since staging still unstages`() =
        runBlocking {
            val empty = temp.newFolder("unborn")
            ProcessBuilder(
                "sh",
                "-c",
                "git init -q -b main . && echo a > a.txt && git add a.txt && echo b >> a.txt",
            ).directory(empty).start().waitFor()

            client.unstage(empty.absolutePath, listOf("a.txt"), noCommits = true).getOrThrow()

            assertTrue(client.status(empty.absolutePath).getOrThrow().changes.single().untracked)
        }
}
