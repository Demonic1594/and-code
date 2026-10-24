package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs [WorkspaceShell]'s commands through the host's own `sh` and `git`, standing in for the PRoot
 * shell. That checks the quoting, the chunked base64 write and the git flags for real instead of
 * comparing command strings.
 */
class WorkspaceShellTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repo: File
    private val shell =
        WorkspaceShell { command, timeout ->
            val process =
                ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .apply { environment()["HOME"] = temp.root.absolutePath }
                    .start()
            process.waitFor(timeout, TimeUnit.SECONDS)
            LocalRuntimeCommandResult(process.exitValue(), process.inputStream.bufferedReader().readText())
        }

    private fun sh(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).directory(repo).redirectErrorStream(true).start()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.inputStream.bufferedReader().readText()
    }

    @Before
    fun setUp() {
        assumeTrue("needs git and base64 on the test host", File("/usr/bin/git").exists() || File("/bin/git").exists())
        repo = temp.newFolder("repo with 'quote'")
        sh("git init -q -b main . && git config user.email t@example.com && git config user.name Test")
        File(repo, "a.txt").writeText("one\n")
        sh("git add -A && git commit -qm init")
    }

    @Test
    fun `writeFile round-trips large unicode text and keeps the file mode`() =
        runBlocking {
            val script = File(repo, "run.sh").apply { writeText("#!/bin/sh\n") }
            script.setExecutable(true)
            val content = buildString { repeat(20_000) { append("línea $it — 'quoted' \"x\" \$HOME `tick`\n") } }
            val result = shell.writeFile(script.absolutePath, content)
            assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
            assertEquals(content, script.readText())
            assertTrue(script.canExecute())
            assertTrue(repo.listFiles()!!.none { it.name.endsWith(".andcode-save") })
        }

    @Test
    fun `writeFile handles empty content`() =
        runBlocking {
            val file = File(repo, "a.txt")
            assertTrue(shell.writeFile(file.absolutePath, "").isSuccess)
            assertEquals("", file.readText())
        }

    @Test
    fun `commit, branches and history`() =
        runBlocking {
            File(repo, "b.txt").writeText("two\n")
            assertTrue(shell.commitAll(repo.absolutePath, "Add b: it's \"new\"").isSuccess)
            val commits = shell.recentCommits(repo.absolutePath).getOrThrow()
            assertEquals("Add b: it's \"new\"", commits.first().message)
            assertEquals(2, commits.size)

            assertTrue(shell.createBranch(repo.absolutePath, "feature/x").isSuccess)
            assertEquals(listOf("feature/x", "main"), shell.branches(repo.absolutePath).getOrThrow().sorted())
            assertTrue(shell.checkout(repo.absolutePath, "main").isSuccess)
        }

    @Test
    fun `commit falls back to a placeholder identity when none is configured`() =
        runBlocking {
            sh("git config --unset user.email && git config --unset user.name")
            File(repo, "c.txt").writeText("three\n")
            val result = shell.commitAll(repo.absolutePath, "no identity")
            assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
            assertTrue(sh("git log -1 --format=%ae").contains("andcode@localhost"))
        }

    @Test
    fun `a failing command surfaces git's own message`() =
        runBlocking {
            val result = shell.checkout(repo.absolutePath, "does-not-exist")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("does-not-exist"))
        }

    @Test
    fun `guest path joins relative paths onto the workspace`() {
        assertEquals("/workspace/src/a.kt", WorkspaceShell.guestPath("/workspace/", "./src/a.kt"))
        assertEquals("/etc/hosts", WorkspaceShell.guestPath("/workspace", "/etc/hosts"))
    }
}
