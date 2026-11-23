package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.util.pollWhileActive
import kotlinx.coroutines.flow.Flow

private const val POLL_INTERVAL_MILLIS = 1000L

/**
 * Reads the browser commands an in-guest agent writes ([BROWSER_COMMAND_PATH], e.g.
 * `{"action":"open","mode":"hidden","url":"https://example.com/","requestId":"k3","createdAtMs":1800000000000}`)
 * and hands each to [onCommand], polling only while [active]: while the app is on screen or an agent
 * is working, which is when the guest can be writing one. Otherwise the loop stays parked rather than
 * reading the disk every second for the life of the process.
 *
 * An `open` fills the Guest Browser or the hidden one, a `preview` pins a page on this device inside
 * the conversation, a `handoff` asks the user to take over (to sign in, say) and pauses the agent
 * until they hand the browser back, and a `close` closes the hidden browser.
 *
 * [poll] suspends while it reads the command file, so a gate that closes in that instant can drop
 * the command it has just consumed. That is a window of milliseconds at the moment the guest stops
 * running, when nothing is left waiting for an answer.
 */
internal suspend fun watchBrowserCommands(
    active: Flow<Boolean>,
    poll: suspend () -> BrowserCommand?,
    onCommand: suspend (BrowserCommand) -> Unit,
    intervalMillis: Long = POLL_INTERVAL_MILLIS,
) {
    active.pollWhileActive(intervalMillis) { poll()?.let { onCommand(it) } }
}
