package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The OpenCode runtime reads files over HTTP but has no write endpoint, so editing is wired to the
 * host folder behind `/workspace`. These pin that wiring - the only place the edit button's
 * availability for OpenCode comes from.
 */
class LocalRuntimeTargetFileEditingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: File
    private val targets = mutableListOf<LocalRuntimeTarget>()

    @Before
    fun setUp() {
        workspace = temporaryFolder.newFolder("workspace")
        File(workspace, "app").mkdirs()
        File(workspace, "app/.env").writeText("API_URL=http://localhost:3000\n")
    }

    @After
    fun tearDown() = targets.forEach(LocalRuntimeTarget::destroy)

    @Test
    fun `offers editing and saves through the host workspace folder`() =
        runBlocking {
            val target = target(files = ClaudeWorkspaceFiles(workspace))

            val text = target.readFileForEditing("/workspace/app", ".env")
            target.writeFile("/workspace/app", ".env", text + "DEBUG=1\n", expectedContent = text)

            assertTrue(target.capabilities.fileEditing)
            assertEquals("API_URL=http://localhost:3000\n", text)
            assertEquals("API_URL=http://localhost:3000\nDEBUG=1\n", File(workspace, "app/.env").readText())
        }

    @Test
    fun `without host access the viewer stays read-only`() {
        val target = target(files = null)

        assertFalse(target.capabilities.fileEditing)
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { target.readFileForEditing("/workspace/app", ".env") }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { target.writeFile("/workspace/app", ".env", "changed", expectedContent = null) }
        }
        assertEquals("API_URL=http://localhost:3000\n", File(workspace, "app/.env").readText())
    }

    private fun target(files: ClaudeWorkspaceFiles?): LocalRuntimeTarget =
        LocalRuntimeTarget(
            LocalRuntimeManager(runtimeDirectory = temporaryFolder.newFolder(), abi = "arm64-v8a"),
            files = files,
        ).also(targets::add)
}
