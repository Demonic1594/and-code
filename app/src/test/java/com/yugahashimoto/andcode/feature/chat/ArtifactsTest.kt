package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.isLocalServerUrl
import com.yugahashimoto.andcode.core.localServerUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun assistant(vararg parts: ChatPart) = ChatMessage(isUser = false, parts = parts.toList())

    @Test
    fun `previewable files are recognised by their extension`() {
        assertEquals(ArtifactKind.HTML, artifactKindOf("site/index.HTML"))
        assertEquals(ArtifactKind.SVG, artifactKindOf("logo.svg"))
        assertEquals(ArtifactKind.MARKDOWN, artifactKindOf("README.md"))
        assertEquals(ArtifactKind.IMAGE, artifactKindOf("shot.webp"))
        assertNull(artifactKindOf("Main.kt"))
        assertNull(artifactKindOf("Makefile"))
    }

    @Test
    fun `files come from patches and from the paths file tools wrote, newest first and once each`() {
        val artifacts =
            collectArtifacts(
                listOf(
                    assistant(
                        ChatPart.Patch("p1", listOf("/w/app/index.html", "/w/app/Main.kt"), "a1"),
                        ChatPart.Tool("t1", "Write", ToolStatus.COMPLETED, filePaths = listOf("/w/docs/plan.md")),
                    ),
                    assistant(ChatPart.Tool("t2", "edit", ToolStatus.COMPLETED, filePaths = listOf("/w/app/index.html"))),
                ),
            )

        assertEquals(
            listOf(Artifact.File("/w/app/index.html", ArtifactKind.HTML), Artifact.File("/w/docs/plan.md", ArtifactKind.MARKDOWN)),
            artifacts,
        )
    }

    @Test
    fun `local servers come from the agent's words and tool output, not the user's`() {
        val artifacts =
            collectArtifacts(
                listOf(
                    ChatMessage(isUser = true, parts = listOf(ChatPart.Text("u", "see http://localhost:9999"))),
                    assistant(
                        ChatPart.Tool(
                            "t1",
                            "bash",
                            ToolStatus.COMPLETED,
                            output = "  VITE ready\n  ➜  Local:   http://localhost:5173/\n  ➜  Network: http://0.0.0.0:5173/",
                        ),
                        ChatPart.Text("x", "The app is running at http://127.0.0.1:8000. Open https://example.com for docs."),
                    ),
                ),
            )

        assertEquals(
            listOf("http://127.0.0.1:8000", "http://127.0.0.1:5173/", "http://localhost:5173/"),
            artifacts.filterIsInstance<Artifact.Server>().map { it.url },
        )
    }

    @Test
    fun `local server urls are told apart from the internet`() {
        assertTrue(isLocalServerUrl("http://localhost:3000/app"))
        assertTrue(isLocalServerUrl("http://127.0.0.1"))
        assertFalse(isLocalServerUrl("https://localhost.example.com"))
        assertFalse(isLocalServerUrl("https://github.com"))
        assertEquals(listOf("http://localhost:3000"), localServerUrls("open http://localhost:3000, then reload."))
    }

    @Test
    fun `only real local hosts are taken, whatever their case or query`() {
        assertEquals(
            emptyList<String>(),
            localServerUrls("see https://localhost.example.com/docs and http://127.0.0.1.nip.io and http://localhost:123456"),
        )
        assertTrue(isLocalServerUrl("HTTP://LOCALHOST:8080"))
        assertTrue(isLocalServerUrl("http://localhost:3000?x=1"))
        // Only the host moves to 127.0.0.1; a path that happens to say 0.0.0.0 is left alone.
        assertEquals(listOf("http://127.0.0.1:8000/0.0.0.0"), localServerUrls("http://0.0.0.0:8000/0.0.0.0"))
    }

    @Test
    fun `a tool's files are read from each runtime's input`() {
        // OpenCode's write and edit, and Claude Code's Write.
        assertEquals(listOf("/w/index.html"), toolFilePaths(json("""{"filePath":"/w/index.html","content":"<p>"}""")))
        assertEquals(listOf("C:\\proj\\a.md"), toolFilePaths(json("""{"file_path":"C:\\proj\\a.md"}""")))
        // Antigravity's write_to_file.
        assertEquals(listOf("/w/logo.svg"), toolFilePaths(json("""{"TargetFile":"/w/logo.svg","CodeContent":"<svg/>"}""")))
        // Codex's file change list.
        assertEquals(
            listOf("/w/a.html", "/w/b.kt"),
            toolFilePaths(json("""[{"path":"/w/a.html","kind":"add"},{"path":"/w/b.kt","kind":"update"}]""")),
        )
        // A file Codex deleted is gone, and one it moved is where it went.
        assertEquals(
            listOf("/w/new.md"),
            toolFilePaths(
                json(
                    """[{"path":"/w/gone.html","kind":{"type":"delete"}},{"path":"/w/old.md","kind":{"type":"update","move_path":"/w/new.md"}}]""",
                ),
            ),
        )
        // OpenCode's apply_patch.
        assertEquals(
            listOf("site/index.html", "README.md", "docs/a.md"),
            toolFilePaths(
                json(
                    """{"patchText":"*** Begin Patch\n*** Add File: site/index.html\n+<p>\n*** Update File: README.md\n*** Update File: a.md\n*** Move to: docs/a.md\n*** Delete File: old.md\n*** End Patch"}""",
                ),
            ),
        )
        assertEquals(emptyList<String>(), toolFilePaths(json("""{"command":"ls"}""")))
        assertEquals(emptyList<String>(), toolFilePaths(null))
    }

    private fun openCodeTool(
        tool: String,
        input: String,
    ) = OpenCodePart(
        id = "t-$tool",
        type = "tool",
        tool = tool,
        state = mapOf("status" to JsonPrimitive("completed"), "input" to json(input)),
    ).toChatPart("m1")!!

    @Test
    fun `a file an OpenCode write made is an artifact, though its input is shown as text`() {
        val artifacts =
            collectArtifacts(
                listOf(
                    assistant(
                        openCodeTool("write", """{"filePath":"/workspace/game/index.html","content":"<html></html>"}"""),
                        // A file read is not something the agent made.
                        openCodeTool("read", """{"filePath":"/workspace/other.svg"}"""),
                    ),
                ),
            )

        assertEquals(listOf(Artifact.File("/workspace/game/index.html", ArtifactKind.HTML)), artifacts)
    }

    @Test
    fun `a path inside the workspace is read relative to it`() {
        assertEquals("app/index.html", workspaceRelativePath("/w/proj/app/index.html", "/w/proj/"))
        assertEquals("/elsewhere/a.md", workspaceRelativePath("/elsewhere/a.md", "/w/proj"))
        assertEquals("a.md", workspaceRelativePath("a.md", null))
    }
}
