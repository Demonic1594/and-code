package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.SessionApproval
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a session opened in each permission mode asks the user before using tools. The schedules
 * feature warns by this, so it must match what each mode actually lets the agent do.
 */
class PermissionModeApprovalTest {
    @Test
    fun `claude code full access never asks and accept edits runs edits and commands unasked`() {
        assertEquals(SessionApproval.NEVER_ASKS, ClaudePermissionMode.FULL_ACCESS.sessionApproval(bridgeReady = true))
        assertEquals(
            SessionApproval.EDITS_AND_COMMANDS_PREAPPROVED,
            ClaudePermissionMode.ACCEPT_EDITS.sessionApproval(bridgeReady = true),
        )
        assertEquals(SessionApproval.ASKS, ClaudePermissionMode.ASK.sessionApproval(bridgeReady = true))
        assertEquals(SessionApproval.ASKS, ClaudePermissionMode.PLAN.sessionApproval(bridgeReady = false))
    }

    /** ClaudeCodeRuntime runs Ask as Accept edits until the PermissionRequest hook is installed. */
    @Test
    fun `claude code ask without the permission hook runs as accept edits`() {
        assertEquals(SessionApproval.EDITS_AND_COMMANDS_PREAPPROVED, ClaudePermissionMode.ASK.sessionApproval(bridgeReady = false))
    }

    @Test
    fun `antigravity modes map the same way`() {
        assertEquals(SessionApproval.NEVER_ASKS, AntigravityPermissionMode.FULL_ACCESS.sessionApproval)
        assertEquals(SessionApproval.EDITS_AND_COMMANDS_PREAPPROVED, AntigravityPermissionMode.ACCEPT_EDITS.sessionApproval)
        assertEquals(SessionApproval.ASKS, AntigravityPermissionMode.PLAN.sessionApproval)
    }
}
