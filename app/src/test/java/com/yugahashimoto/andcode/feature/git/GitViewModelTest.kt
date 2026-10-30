package com.yugahashimoto.andcode.feature.git

import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Drives [GitViewModel] over a real repository, as [GitClientTest] does for the client. */
@OptIn(ExperimentalCoroutinesApi::class)
class GitViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: File

    private val client by lazy {
        GitClient(ioDispatcher = dispatcher) { command, timeout, _ ->
            val process =
                ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .apply { environment()["HOME"] = temp.root.absolutePath }
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(timeout, TimeUnit.SECONDS)
            LocalRuntimeCommandResult(process.exitValue(), output)
        }
    }

    private fun sh(command: String) {
        ProcessBuilder("sh", "-c", command).directory(repo).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
    }

    @Before
    fun setUp() {
        assumeTrue("needs git on the test host", File("/usr/bin/git").exists() || File("/bin/git").exists())
        Dispatchers.setMain(dispatcher)
        repo = temp.newFolder("repo")
        sh("git init -q -b main . && git config user.email t@example.com && git config user.name Test")
        File(repo, "a.txt").writeText("one\n")
        sh("git add -A && git commit -qm first")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads status, branches and history on open`() =
        runTest(dispatcher) {
            File(repo, "b.txt").writeText("b\n")
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("main", state.status?.branch)
            assertEquals(listOf("b.txt"), state.status?.unstaged?.map { it.path })
            assertEquals(listOf("main"), state.branches.map { it.name })
            assertEquals(listOf("first"), state.commits.map { it.subject })
            assertNull(state.loadError)
        }

    @Test
    fun `committing with nothing staged commits every change`() =
        runTest(dispatcher) {
            File(repo, "a.txt").writeText("two\n")
            File(repo, "c.txt").writeText("c\n")
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            viewModel.setCommitMessage("  Everything  ")
            viewModel.commit()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(GitOperation.COMMIT, state.done)
            assertTrue(state.status!!.changes.isEmpty())
            assertEquals(listOf("Everything", "first"), state.commits.map { it.subject })
        }

    @Test
    fun `a bad branch name reports the localizable marker`() =
        runTest(dispatcher) {
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            viewModel.createBranch("-x")
            advanceUntilIdle()

            assertEquals(GitViewModel.ERROR_INVALID_REF, viewModel.state.value.error)
            viewModel.consumeResult()
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `a diff opens for a change and closes again`() =
        runTest(dispatcher) {
            File(repo, "a.txt").writeText("one\nmore\n")
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            viewModel.openDiff(viewModel.state.value.status!!.unstaged.single(), staged = false)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.diff?.text.orEmpty().contains("+more"))

            viewModel.closeDiff()
            assertNull(viewModel.state.value.diff)
        }

    @Test
    fun `a folder outside any repository says so`() =
        runTest(dispatcher) {
            val viewModel = GitViewModel(client, temp.newFolder("plain").absolutePath)
            advanceUntilIdle()

            assertNull(viewModel.state.value.status)
            assertNotNull(viewModel.state.value.loadError)
        }

    @Test
    fun `a commit right after staging commits only what was staged`() =
        runTest(dispatcher) {
            File(repo, "a.txt").writeText("two\n")
            File(repo, "secret.env").writeText("token\n")
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            // The screen still shows nothing staged when the commit is sent: git decides, not the screen.
            sh("git add a.txt")
            viewModel.setCommitMessage("Only a")
            viewModel.commit()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("secret.env"), state.status!!.unstaged.map { it.path })
            assertEquals("", state.commitMessage)
        }

    @Test
    fun `unstaging a rename unstages both of its paths`() =
        runTest(dispatcher) {
            sh("git mv a.txt b.txt")
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            viewModel.unstage(viewModel.state.value.status!!.staged.single())
            advanceUntilIdle()

            val status = viewModel.state.value.status!!
            assertTrue(status.staged.isEmpty())
            assertEquals(setOf("a.txt", "b.txt"), status.unstaged.map { it.path }.toSet())
        }

    @Test
    fun `amend starts from the latest commit message without replacing one being written`() =
        runTest(dispatcher) {
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            viewModel.setAmend(true)
            advanceUntilIdle()
            assertEquals("first", viewModel.state.value.commitMessage)

            viewModel.setAmend(false)
            viewModel.setCommitMessage("mine")
            viewModel.setAmend(true)
            advanceUntilIdle()
            assertEquals("mine", viewModel.state.value.commitMessage)
        }

    @Test
    fun `loading more history twice does not repeat a page`() =
        runTest(dispatcher) {
            repeat(GitClient.LOG_PAGE + 5) { sh("git commit -q --allow-empty -m c$it") }
            val viewModel = GitViewModel(client, repo.absolutePath)
            advanceUntilIdle()

            viewModel.loadMoreCommits()
            viewModel.loadMoreCommits()
            advanceUntilIdle()

            val hashes = viewModel.state.value.commits.map { it.hash }
            assertEquals(GitClient.LOG_PAGE + 6, hashes.size)
            assertEquals(hashes.size, hashes.toSet().size)
        }
}
