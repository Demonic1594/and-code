package com.yugahashimoto.andcode.feature.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitMessagePromptTest {
    private fun request(
        subjects: List<String> = emptyList(),
        untracked: List<String> = emptyList(),
        truncated: Boolean = false,
    ) = CommitMessageRequest(
        stat = " src/a.kt | 2 +-\n 1 file changed",
        diff = "diff --git a/src/a.kt b/src/a.kt\n-old\n+new\n",
        diffTruncated = truncated,
        untracked = untracked,
        recentSubjects = subjects,
    )

    @Test
    fun `the prompt carries the files, the diff and the repository's recent subjects`() {
        val prompt = CommitMessagePrompt.build(request(subjects = listOf("feat(chat): rename a chat", "fix: crash")))

        assertTrue(prompt.contains("src/a.kt | 2 +-"))
        assertTrue(prompt.contains("+new"))
        assertTrue(prompt.contains("  feat(chat): rename a chat\n  fix: crash"))
        assertFalse(prompt.contains("Write it in English"))
    }

    @Test
    fun `a repository with no commits gets English, and new files are listed`() {
        val prompt = CommitMessagePrompt.build(request(untracked = listOf("README.md")))

        assertTrue(prompt.contains("Write it in English"))
        assertTrue(prompt.contains("New files:\nREADME.md"))
    }

    @Test
    fun `a cut diff is said to be cut, and a long list of new files is shortened`() {
        val prompt = CommitMessagePrompt.build(request(untracked = List(60) { "f$it" }, truncated = true))

        assertTrue(prompt.contains("only its beginning"))
        assertTrue(prompt.contains("… and 10 more"))
        assertFalse(prompt.contains("f55"))
    }

    @Test
    fun `the reply is cleaned of fences, labels and quotes`() {
        assertEquals("Add a login screen", CommitMessagePrompt.clean("```\nAdd a login screen\n```"))
        assertEquals("Add a login screen", CommitMessagePrompt.clean("Commit message: Add a login screen"))
        assertEquals("Add a login screen", CommitMessagePrompt.clean("\"Add a login screen\""))
        assertEquals(
            "fix: keep the draft\n\nThe draft was lost on rotation.",
            CommitMessagePrompt.clean("fix: keep the draft   \r\n\r\n\r\n\r\nThe draft was lost on rotation.\n"),
        )
    }

    @Test
    fun `an empty reply is no message`() {
        assertNull(CommitMessagePrompt.clean(null))
        assertNull(CommitMessagePrompt.clean("  \n```\n```\n"))
        assertNull(CommitMessagePrompt.clean("\"\""))
    }
}
