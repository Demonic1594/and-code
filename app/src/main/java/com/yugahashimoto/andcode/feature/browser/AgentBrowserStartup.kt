package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.AndCodeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Builds the hidden browser and starts its bridge to the guest and its background notification. The
 * agents' browser tools (andcode-browser-mcp.py) ask for a browser through a command file in the guest
 * home, read while [active] (the app on screen, or an agent working), so a hidden page can be opened
 * while the agent works with the app in the background.
 */
internal fun AndCodeApplication.startAgentBrowser(
    scope: CoroutineScope,
    active: Flow<Boolean>,
): HiddenAgentBrowser {
    val runtimes = runtimeRegistry
    val foreground = appForeground.foreground
    val browser =
        HiddenAgentBrowser(
            context = this,
            runtimeDirectory = File(filesDir, "runtime"),
            selectedAgent = { runtimes.selected.value?.agent },
            agentControl =
                preferences.state
                    .map { it.agentBrowserControl }
                    .stateIn(scope, SharingStarted.Eagerly, preferences.state.value.agentBrowserControl),
            foreground = foreground,
        )
    scope.launch { browser.run(active, runtimes.selected.map { it?.id }) }
    scope.launch { AgentBrowsingNotification(this@startAgentBrowser).run(browser.state, foreground) }
    return browser
}
