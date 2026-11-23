package com.yugahashimoto.andcode.feature.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * The app side of a human/agent handoff in the Guest Browser.
 *
 * The agent asks for the user when it cannot do something itself, signing in above all: the user
 * completes it in the visible browser, then hands control back. The agent polls the status file
 * in the guest home ([BROWSER_STATUS_PATH]), not this object, so the two processes share nothing but
 * the file protocol.
 *
 * [active] only drives the banner in the open browser; it is null once the agent has control again.
 * A handoff that the user never answers is left to the agent's timeout rather than cleared here,
 * because leaving the screen (rotation included) must not look like a hand-back.
 */
object BrowserHandoff {
    /** The handoff waiting on the user, or null while the agent has control. */
    var active by mutableStateOf<Session?>(null)
        private set

    /**
     * Shows the handoff banner after the watcher has recorded `awaiting_user` for [requestId] in
     * [rootfs]'s guest home. A newer handoff replaces an unanswered one, whose tool then reads that
     * its request was superseded.
     */
    fun begin(
        rootfs: File,
        requestId: String = "",
    ) {
        active = Session(rootfs, requestId)
    }

    /** The user finished what the agent could not do; it may continue. */
    fun handBack() = finish(BROWSER_STATUS_AGENT)

    /** The user declined to continue; the agent must stop waiting. */
    fun dismiss() = finish(BROWSER_STATUS_DISMISSED)

    // Only once the agent can read the answer: a banner taken down without it would leave the agent
    // waiting for a tap the user can no longer give. Tapping again tries the write again. A rootfs
    // that is gone has no agent left to answer, and a handoff a newer one replaced is not answered
    // over the newer one's status.
    private fun finish(state: String) {
        val session = active ?: return
        if (answerBrowserHandoff(session.rootfs, state, session.requestId) || !session.rootfs.isDirectory) active = null
    }

    class Session internal constructor(
        val rootfs: File,
        val requestId: String,
    )
}
