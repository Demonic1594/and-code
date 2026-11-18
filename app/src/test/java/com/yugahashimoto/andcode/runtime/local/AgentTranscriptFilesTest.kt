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
        val rootfs = temp.newFolder("rootfs")
        val home = File(rootfs, "root/.claude").apply { mkdirs() }
        val project = File(home, "projects/-workspace-app").apply { mkdirs() }
        val transcript = File(project, "$id.jsonl").apply { writeText("{}") }
        val subagents = File(project, id).apply { mkdirs() }.also { File(it, "agent-1.jsonl").writeText("{}") }
        val todo = File(home, "todos/$id-agent-$id.json").apply { parentFile!!.mkdirs() }.apply { writeText("[]") }
        val history = File(home, "file-history/$id").apply { mkdirs() }
        val kept = File(project, "$otherId.jsonl").apply { writeText("{}") }

        val deleted = AgentTranscriptFiles.deleteClaude(rootfs, listOf(id))

        assertEquals(4, deleted)
        listOf(transcript, subagents, todo, history).forEach { assertFalse(it.path, it.exists()) }
        assertTrue(kept.exists())
    }

    @Test
    fun `antigravity conversation and brain folder are removed`() {
        val rootfs = temp.newFolder("rootfs")
        val home = File(rootfs, "root/.gemini").apply { mkdirs() }
        val conversation = File(home, "antigravity-cli/conversations/$id.pb").apply { parentFile!!.mkdirs() }.apply { writeText("x") }
        val brain = File(home, "antigravity-cli/brain/$id").apply { mkdirs() }
        val settings = File(home, "antigravity-cli/settings.json").apply { writeText("{}") }

        AgentTranscriptFiles.deleteAntigravity(rootfs, listOf(id))

        assertFalse(conversation.exists())
        assertFalse(brain.exists())
        assertTrue(settings.exists())
    }

    @Test
    fun `ids that could match other files are ignored`() {
        val folder = temp.newFolder("projects")
        val file = File(folder, "abc.jsonl").apply { writeText("{}") }
        val dotted = File(folder, "..").canonicalFile

        assertEquals(0, AgentTranscriptFiles.deleteMatching(temp.root, listOf("projects"), listOf("abc", "", "../x", "a/b/c/d/e/f")))
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

        AgentTranscriptFiles.deleteMatching(temp.root, listOf(folder.name), listOf(id))

        assertFalse(entry.exists())
        assertTrue(precious.exists())
    }

    @Test
    fun `a matching entry that is itself a link loses only the link`() {
        val workspace = temp.newFolder("workspace")
        val precious = File(workspace, "Main.kt").apply { writeText("keep") }
        val folder = temp.newFolder("brain")
        val link = File(folder, id)
        Files.createSymbolicLink(link.toPath(), workspace.toPath())

        AgentTranscriptFiles.deleteMatching(temp.root, listOf(folder.name), listOf(id))

        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(precious.exists())
    }

    @Test
    fun `a prefix of another id does not match it`() {
        val folder = temp.newFolder("projects")
        val longer = File(folder, "${id}0.jsonl").apply { writeText("{}") }

        AgentTranscriptFiles.deleteMatching(temp.root, listOf(folder.name), listOf(id))

        assertTrue(longer.exists())
    }

    /**
     * The guest can make `~/.claude/todos` a link to the app's data folder, and Claude's own output
     * supplies the id (`shared_prefs` passes as one): deleting the chat must not reach through it.
     */
    @Test
    fun `a transcript folder the guest linked to app data is never listed or deleted through`() {
        val appData = temp.newFolder("app-data")
        val prefs = File(appData, "shared_prefs").apply { mkdirs() }.also { File(it, "secure.xml").writeText("<map/>") }
        val rootfs = temp.newFolder("rootfs-linked")
        File(rootfs, "root/.claude").mkdirs()
        Files.createSymbolicLink(File(rootfs, "root/.claude/todos").toPath(), appData.toPath())

        AgentTranscriptFiles.deleteClaude(rootfs, listOf("shared_prefs"))

        assertTrue(File(prefs, "secure.xml").exists())
    }
}
