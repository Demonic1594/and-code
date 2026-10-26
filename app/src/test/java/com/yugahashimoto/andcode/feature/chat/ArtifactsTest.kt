package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.isLocalServerUrl
import com.yugahashimoto.andcode.core.localServerUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {
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
                        ChatPart.Tool(
                            "t1",
                            "write",
                            ToolStatus.COMPLETED,
                            input = """{"file_path":"/w/docs\/plan.md","content":"# Plan"}""",
                        ),
                    ),
                    assistant(ChatPart.Tool("t2", "edit", ToolStatus.COMPLETED, input = """{"filePath":"/w/app/index.html"}""")),
                    // A file read is not something the agent made.
                    assistant(ChatPart.Tool("t3", "read", ToolStatus.COMPLETED, input = """{"filePath":"/w/other.svg"}""")),
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
    fun `a path inside the workspace is read relative to it`() {
        assertEquals("app/index.html", workspaceRelativePath("/w/proj/app/index.html", "/w/proj/"))
        assertEquals("/elsewhere/a.md", workspaceRelativePath("/elsewhere/a.md", "/w/proj"))
        assertEquals("a.md", workspaceRelativePath("a.md", null))
    }
}
