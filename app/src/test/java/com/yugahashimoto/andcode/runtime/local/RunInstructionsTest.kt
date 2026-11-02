package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunInstructionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val blurb =
        """
        # AndCode
        Intro.

        ## Servers and other long-running commands: use `andcode-run`
        - ALWAYS start servers with `andcode-run start`.

        ## Showing your work in the app: artifacts
        Artifacts.
        """.trimIndent()

    private fun instructions(rootfs: File) =
        GuestRunInstructions(
            rootfs = { rootfs },
            blurb = { blurb },
            dismissedFile = File(temp.root, "dismissed.txt"),
        )

    @Test
    fun `the run section is the andcode-run heading up to the next one`() {
        assertEquals(
            "## Servers and other long-running commands: use `andcode-run`\n- ALWAYS start servers with `andcode-run start`.\n",
            runSectionOf(blurb),
        )
        assertEquals(null, runSectionOf("# Nothing about it\n"))
    }

    @Test
    fun `only an edited file without the section is reported, and adding it keeps the user's text`() {
        val rootfs = temp.newFolder("rootfs")
        val claude = File(rootfs, "root/.claude/CLAUDE.md").apply { parentFile!!.mkdirs() }
        claude.writeText("My own rules.")
        File(rootfs, "root/.codex/AGENTS.md").apply { parentFile!!.mkdirs() }.writeText("Use andcode-run start for servers.")
        val run = instructions(rootfs)

        assertEquals(listOf("/root/.claude/CLAUDE.md"), run.missing())

        assertTrue(run.add("/root/.claude/CLAUDE.md"))
        assertTrue(claude.readText().startsWith("My own rules.\n\n## Servers"))
        assertEquals(emptyList<String>(), run.missing())
    }

    @Test
    fun `a dismissed file is not reported again, and nothing outside the known files is written`() {
        val rootfs = temp.newFolder("rootfs")
        File(rootfs, "root/.gemini/GEMINI.md").apply { parentFile!!.mkdirs() }.writeText("Mine.")
        val run = instructions(rootfs)

        run.dismiss(run.missing())

        assertEquals(emptyList<String>(), instructions(rootfs).missing())
        assertFalse(run.add("/etc/profile"))
        assertFalse(run.add("/root/.claude/CLAUDE.md"))
    }
}
