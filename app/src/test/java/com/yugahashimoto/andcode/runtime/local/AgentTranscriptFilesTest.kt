package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AgentTranscriptFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val id = "3f2c9a10-5b7d-4e21-9c3a-0d1e2f3a4b5c"
    private val otherId = "9a8b7c6d-1111-2222-3333-444455556666"

    @Test
    fun `claude transcript, subagent folder, todos and file history are removed`() {
        val home = temp.newFolder(".claude")
        val project = File(home, "projects/-workspace-app").apply { mkdirs() }
        val transcript = File(project, "$id.jsonl").apply { writeText("{}") }
        val subagents = File(project, id).apply { mkdirs() }.also { File(it, "agent-1.jsonl").writeText("{}") }
        val todo = File(home, "todos/$id-agent-$id.json").apply { parentFile!!.mkdirs() }.apply { writeText("[]") }
        val history = File(home, "file-history/$id").apply { mkdirs() }
        val kept = File(project, "$otherId.jsonl").apply { writeText("{}") }

        val deleted = AgentTranscriptFiles.deleteClaude(home, listOf(id))

        assertEquals(4, deleted)
        listOf(transcript, subagents, todo, history).forEach { assertFalse(it.path, it.exists()) }
        assertTrue(kept.exists())
    }

    @Test
    fun `antigravity conversation and brain folder are removed`() {
        val home = temp.newFolder(".gemini")
        val conversation = File(home, "antigravity-cli/conversations/$id.pb").apply { parentFile!!.mkdirs() }.apply { writeText("x") }
        val brain = File(home, "antigravity-cli/brain/$id").apply { mkdirs() }
        val settings = File(home, "antigravity-cli/settings.json").apply { writeText("{}") }

        AgentTranscriptFiles.deleteAntigravity(home, listOf(id))

        assertFalse(conversation.exists())
        assertFalse(brain.exists())
        assertTrue(settings.exists())
    }

    @Test
    fun `ids that could match other files are ignored`() {
        val folder = temp.newFolder("projects")
        val file = File(folder, "abc.jsonl").apply { writeText("{}") }
        val dotted = File(folder, "..").canonicalFile

        assertEquals(0, AgentTranscriptFiles.deleteMatching(listOf(folder), listOf("abc", "", "../x", "a/b/c/d/e/f")))
        assertTrue(file.exists())
        assertTrue(dotted.exists())
    }

    @Test
    fun `a symbolic link inside a deleted folder is removed without touching its target`() {
        val workspace = temp.newFolder("workspace")
        val precious = File(workspace, "Main.kt").apply { writeText("keep") }
        val folder = temp.newFolder("file-history")
        val entry = File(folder, id).apply { mkdirs() }
        Files.createSymbolicLink(File(entry, "link").toPath(), workspace.toPath())

        AgentTranscriptFiles.deleteMatching(listOf(folder), listOf(id))

        assertFalse(entry.exists())
        assertTrue(precious.exists())
    }

    @Test
    fun `a prefix of another id does not match it`() {
        val folder = temp.newFolder("projects")
        val longer = File(folder, "${id}0.jsonl").apply { writeText("{}") }

        AgentTranscriptFiles.deleteMatching(listOf(folder), listOf(id))

        assertTrue(longer.exists())
    }
}
