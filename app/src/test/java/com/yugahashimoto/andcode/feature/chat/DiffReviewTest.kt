package com.yugahashimoto.andcode.feature.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffReviewTest {
    private val patch =
        listOf(
            "diff --git a/Login.kt b/Login.kt",
            "index 1111111..2222222 100644",
            "--- a/Login.kt",
            "+++ b/Login.kt",
            "@@ -10,3 +10,4 @@ class Login {",
            "     val a = 1",
            "-    val vm = remember { LoginViewModel() }",
            "+    val vm = viewModel<LoginViewModel>()",
            "+    val state = vm.state",
            "     val b = 2",
            "\\ No newline at end of file",
            "",
        ).joinToString("\n")

    @Test
    fun `numbers each line in the old and new file and counts changes`() {
        val parsed = parseUnifiedDiff(patch)
        assertEquals(2, parsed.additions)
        assertEquals(1, parsed.deletions)

        val kinds = parsed.rows.map { it.kind }
        assertEquals(List(4) { DiffRow.Kind.META }, kinds.take(4))
        assertEquals(DiffRow.Kind.HUNK, kinds[4])

        val body = parsed.rows.drop(5)
        assertEquals(DiffRow(DiffRow.Kind.CONTEXT, "    val a = 1", oldLine = 10, newLine = 10), body[0])
        assertEquals(DiffRow.Kind.REMOVED, body[1].kind)
        assertEquals(11, body[1].oldLine)
        assertEquals(null, body[1].newLine)
        assertEquals(11, body[2].newLine)
        assertEquals(12, body[3].newLine)
        assertEquals(12, body[4].oldLine)
        assertEquals(13, body[4].newLine)
        // "\ No newline" and the patch's own trailing newline are not rows.
        assertEquals(5, body.size)
    }

    @Test
    fun `only code rows take comments`() {
        val rows = parseUnifiedDiff(patch).rows
        assertFalse(rows.first { it.kind == DiffRow.Kind.HUNK }.commentable)
        assertFalse(rows.first { it.kind == DiffRow.Kind.META }.commentable)
        assertTrue(rows.first { it.kind == DiffRow.Kind.ADDED }.commentable)
    }

    @Test
    fun `a hunk ends at its line counts so the next file's header is not read as code`() {
        val parsed =
            parseUnifiedDiff(
                "--- a/A.kt\r\n+++ b/A.kt\r\n@@ -1 +1 @@\r\n-old\r\n+new\r\n" +
                    "diff --git a/B.kt b/B.kt\r\n--- a/B.kt\r\n+++ b/B.kt\r\n@@ -0,0 +1,1 @@\r\n+born\r\n",
            )
        assertEquals(2, parsed.additions)
        assertEquals(1, parsed.deletions)
        assertEquals("diff --git a/B.kt b/B.kt", parsed.rows[5].text)
        assertEquals(DiffRow.Kind.META, parsed.rows[5].kind)
        // Carriage returns of a CRLF patch are not part of the code.
        assertEquals("new", parsed.rows[4].text)
        assertEquals(DiffRow(DiffRow.Kind.ADDED, "born", null, 1), parsed.rows.last())
    }

    @Test
    fun `a diff without hunks keeps its header lines`() {
        val parsed = parseUnifiedDiff("diff --git a/logo.png b/logo.png\nBinary files differ\n")
        assertEquals(listOf(DiffRow.Kind.META, DiffRow.Kind.META), parsed.rows.map { it.kind })
    }

    @Test
    fun `a malformed hunk header leaves lines unnumbered instead of guessing`() {
        val rows = parseUnifiedDiff("@@ garbage @@\n+x\n").rows
        assertEquals(null, rows[1].newLine)
    }

    @Test
    fun `review message lists comments by file and line with the quoted code`() {
        val rows = parseUnifiedDiff(patch).rows
        val added = rows.indexOfFirst { it.kind == DiffRow.Kind.ADDED }
        val removed = rows.indexOfFirst { it.kind == DiffRow.Kind.REMOVED }
        val message =
            reviewCommentsMessage(
                listOf(
                    ReviewComment("b.kt", 0, rows[added].copy(newLine = 3), "rename this"),
                    ReviewComment("Login.kt", added, rows[added], " use hiltViewModel here "),
                    ReviewComment("Login.kt", removed, rows[removed], "why remove `remember`?"),
                ),
            )
        assertEquals(
            "Please address these review comments on your changes:\n" +
                "\n1. `Login.kt` removed line 11 (`val vm = remember { LoginViewModel() }`): why remove `remember`?" +
                "\n2. `Login.kt` line 11 (`val vm = viewModel<LoginViewModel>()`): use hiltViewModel here" +
                "\n3. `b.kt` line 3 (`val vm = viewModel<LoginViewModel>()`): rename this",
            message,
        )
    }

    @Test
    fun `review comments survive being saved and restored`() {
        val comments =
            mutableStateListOf(
                ReviewComment("app/A.kt", 3, DiffRow(DiffRow.Kind.ADDED, "val x = 1", null, 12), "Why 1?"),
                ReviewComment("app/B.kt", 0, DiffRow(DiffRow.Kind.REMOVED, "old()", 4, null), "Keep this"),
            )
        val scope = SaverScope { true }

        val saved = with(ReviewCommentsSaver) { scope.save(comments) }
        val restored = ReviewCommentsSaver.restore(saved!!)

        assertEquals(comments.toList(), restored!!.toList())
    }
}
