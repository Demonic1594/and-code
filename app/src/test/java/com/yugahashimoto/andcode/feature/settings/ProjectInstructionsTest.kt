package com.yugahashimoto.andcode.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectInstructionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a missing instructions file is created empty`() {
        val project = folder.newFolder("it's a project")

        assertEquals(0, run(ProjectInstructions.ensureExistsCommand(project.path)))

        val file = File(project, ProjectInstructions.CLAUDE_FILE)
        assertTrue(file.isFile)
        assertEquals("", file.readText())
    }

    @Test
    fun `an existing instructions file is left as it is`() {
        val project = folder.newFolder("project")
        File(project, ProjectInstructions.CLAUDE_FILE).writeText("Use tabs.\n")

        assertEquals(0, run(ProjectInstructions.ensureExistsCommand(project.path)))

        assertEquals("Use tabs.\n", File(project, ProjectInstructions.CLAUDE_FILE).readText())
    }

    private fun run(command: String): Int = ProcessBuilder("sh", "-c", command).start().waitFor()
}
