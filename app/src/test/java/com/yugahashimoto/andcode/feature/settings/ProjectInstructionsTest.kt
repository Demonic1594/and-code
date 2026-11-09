package com.yugahashimoto.andcode.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    fun `a new AGENTS md starts from the project's CLAUDE md, so OpenCode keeps reading those rules`() {
        val project = folder.newFolder("project")
        File(project, ProjectInstructions.CLAUDE_FILE).writeText("Claude rules.\n")

        assertEquals(0, run(ProjectInstructions.ensureExistsCommand(project.path, ProjectInstructions.AGENTS_FILE)))

        assertEquals("Claude rules.\n", File(project, "AGENTS.md").readText())
        assertEquals("Claude rules.\n", File(project, ProjectInstructions.CLAUDE_FILE).readText())
    }

    @Test
    fun `a new AGENTS md is empty when the project has no CLAUDE md`() {
        val project = folder.newFolder("project")

        assertEquals(0, run(ProjectInstructions.ensureExistsCommand(project.path, ProjectInstructions.AGENTS_FILE)))

        assertEquals("", File(project, "AGENTS.md").readText())
        assertFalse(File(project, ProjectInstructions.CLAUDE_FILE).exists())
    }

    @Test
    fun `an existing AGENTS md is not replaced by the CLAUDE md`() {
        val project = folder.newFolder("project")
        File(project, ProjectInstructions.CLAUDE_FILE).writeText("Claude rules.\n")
        File(project, "AGENTS.md").writeText("Agent rules.\n")

        assertEquals(0, run(ProjectInstructions.ensureExistsCommand(project.path, ProjectInstructions.AGENTS_FILE)))

        assertEquals("Agent rules.\n", File(project, "AGENTS.md").readText())
    }

    @Test
    fun `a CLAUDE md that cannot be read fails the AGENTS md copy and leaves no empty file behind`() {
        val project = folder.newFolder("project")
        val claude = File(project, ProjectInstructions.CLAUDE_FILE).apply { writeText("Claude rules.\n") }
        assumeTrue(claude.setReadable(false, false) && !claude.canRead())

        assertNotEquals(0, run(ProjectInstructions.ensureExistsCommand(project.path, ProjectInstructions.AGENTS_FILE)))

        assertFalse(File(project, "AGENTS.md").exists())
    }

    private fun run(command: String): Int = ProcessBuilder("sh", "-c", command).start().waitFor()
}
