package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AntigravityPrintArgumentsTest {
    @Test
    fun `the prompt comes last, right after --print`() {
        val arguments =
            antigravityPrintArguments(
                prompt = "--mode is a word here",
                conversationId = "conversation-1",
                model = null,
                variant = null,
                permissionMode = AntigravityPermissionMode.ACCEPT_EDITS,
                workingDirectory = "/workspace/my-app",
                attachmentDirectory = null,
            )

        assertEquals(
            listOf(
                "--output-format",
                "stream-json",
                "--conversation",
                "conversation-1",
                "--mode",
                "accept-edits",
                "--print",
                "--mode is a word here",
            ),
            arguments,
        )
    }

    /**
     * agy's workspace is the folder it starts in plus each `--add-dir`, and the attachments sit in
     * the shared `/workspace`, outside any project folder.
     */
    @Test
    fun `attachments outside the project folder join agy's workspace`() {
        val arguments =
            antigravityPrintArguments(
                prompt = "@/workspace/.andcode-attachments/s/3/0-a.png\nWhat is this?",
                conversationId = null,
                model = null,
                variant = null,
                permissionMode = AntigravityPermissionMode.PLAN,
                workingDirectory = "/workspace/my-app",
                attachmentDirectory = "/workspace/.andcode-attachments/s/3",
            )

        assertEquals(
            listOf(
                "--output-format",
                "stream-json",
                "--mode",
                "plan",
                "--add-dir",
                "/workspace/.andcode-attachments/s/3",
                "--print",
                "@/workspace/.andcode-attachments/s/3/0-a.png\nWhat is this?",
            ),
            arguments,
        )
    }

    @Test
    fun `attachments already inside the folder agy starts in add nothing`() {
        listOf("/workspace", "/workspace/", "/").forEach { workingDirectory ->
            val arguments =
                antigravityPrintArguments(
                    prompt = "hi",
                    conversationId = null,
                    model = null,
                    variant = null,
                    permissionMode = AntigravityPermissionMode.FULL_ACCESS,
                    workingDirectory = workingDirectory,
                    attachmentDirectory = "/workspace/.andcode-attachments/s/3",
                )

            assertFalse(workingDirectory, "--add-dir" in arguments)
        }
    }

    @Test
    fun `a folder that only shares a name prefix is not taken for a parent`() {
        val arguments =
            antigravityPrintArguments(
                prompt = "hi",
                conversationId = null,
                model = null,
                variant = null,
                permissionMode = AntigravityPermissionMode.FULL_ACCESS,
                workingDirectory = "/workspace/.andcode",
                attachmentDirectory = "/workspace/.andcode-attachments/s/3",
            )

        assertEquals("/workspace/.andcode-attachments/s/3", arguments[arguments.indexOf("--add-dir") + 1])
    }
}
