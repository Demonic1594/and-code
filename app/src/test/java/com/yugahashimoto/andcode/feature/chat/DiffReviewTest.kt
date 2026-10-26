package com.yugahashimoto.andcode.feature.chat

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
            "@@ -10,4 +10,5 @@ class Login {",
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
    fun `a malformed hunk header leaves lines unnumbered instead of guessing`() {
        val rows = parseUnifiedDiff("@@ garbage @@\n+x\n").rows
        assertEquals(null, rows[1].newLine)
    }

    @Test
    fun `review message lists comments by file and line with the quoted code`() {
        val rows = parseUnifiedDiff(patch).rows
        val added = rows.first { it.kind == DiffRow.Kind.ADDED }
        val removed = rows.first { it.kind == DiffRow.Kind.REMOVED }
        val message =
            reviewCommentsMessage(
                listOf(
                    ReviewComment("b.kt", added.copy(newLine = 3), "rename this"),
                    ReviewComment("Login.kt", added, " use hiltViewModel here "),
                    ReviewComment("Login.kt", removed, "why remove `remember`?"),
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
}
