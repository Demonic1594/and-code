package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The chat drawer's name for an Antigravity session.
 *
 * The CLI does not name its conversations, so without this every chat sat in the drawer as
 * "Antigravity" no matter what it was about - the same gap [ClaudeCodeTarget] fills from the first
 * prompt, filled the same way here.
 */
class AntigravitySessionTitleTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): AntigravityTarget = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

    @Test
    fun `a new session falls back to the runtime name until it is named`() =
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            assertEquals("Antigravity", session.title)
            assertEquals("Antigravity", target.listSessions(null).single().title)
        }

    @Test
    fun `an explicit title is kept`() =
        runBlocking {
            val target = target()
            val session = target.createSession("Refactor the parser", "/workspace")
            assertEquals("Refactor the parser", session.title)
            assertEquals("Refactor the parser", target.listSessions(null).single().title)
        }

    @Test
    fun `renaming is visible in the session list`() =
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            assertEquals("Fix the login crash", target.renameSession(session.id, "Fix the login crash").title)
            assertEquals("Fix the login crash", target.listSessions(null).single().title)
        }

    /** The summarised name arrives after the send; a name the user gave meanwhile must win. */
    @Test
    fun `a background title replaces only the name it expected`() =
        runBlocking {
            val runtime = AntigravityRuntime(folder.root, { null })
            val target = AntigravityTarget(runtime)
            val session = target.createSession(null, "/workspace")
            runtime.setSessionTitle(session.id, "Fix the login…")

            target.renameSession(session.id, "Login bug")
            runtime.replaceSessionTitle(session.id, expected = "Fix the login…", title = "Fix login crash on Android 14")
            assertEquals("Login bug", target.listSessions(null).single().title)

            runtime.replaceSessionTitle(session.id, expected = "Login bug", title = "Login crash")
            assertEquals("Login crash", target.listSessions(null).single().title)
        }

    /** A turn's end is written over the record as it is then, not as the turn found it. */
    @Test
    fun `a finished turn keeps a name given while it ran`() {
        val atStart = AntigravitySessionRecord("s1", conversationId = null, workspace = "/workspace", title = "Fix the login…")
        val renamedMidTurn = atStart.copy(title = "Login bug")

        val after = renamedMidTurn.afterTurn(discoveredConversationId = "conv-1", now = 42)

        assertEquals("Login bug", after.title)
        assertEquals("conv-1", after.conversationId)
        assertEquals(1L, after.lastStep)
        assertEquals(42L, after.updatedAt)
        assertEquals("conv-1", after.afterTurn(discoveredConversationId = null, now = 43).conversationId)
    }

    /** A title survives a restart, so a reopened chat is not renamed back to the tool. */
    @Test
    fun `a title is persisted across runtime instances`() {
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            target.renameSession(session.id, "Fix the login crash")
        }
        runBlocking {
            assertEquals("Fix the login crash", target().listSessions(null).single().title)
        }
    }
}
