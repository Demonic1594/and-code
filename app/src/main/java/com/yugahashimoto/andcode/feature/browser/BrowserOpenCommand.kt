package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Where the guest's browser tool (andcode-browser-mcp.py) leaves its command, relative to the rootfs.
 *
 * In the guest home, outside every workspace: a command file in the project could also come with a
 * cloned or pulled repository, and open whatever page that repository chose.
 */
internal const val BROWSER_COMMAND_PATH = "root/.local/share/and-code/browser-command.json"

/**
 * The URL of the browser command an agent of the active local runtime left, consumed, or null.
 *
 * Read from the rootfs that agent runs in, whichever folder is open: the Guest Browser is one for the
 * whole app. Only a command stamped in the last few minutes opens; an older one is dropped.
 */
internal fun consumeBrowserOpenCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    clock: () -> Long = System::currentTimeMillis,
): String? {
    // A remote workspace's agent is not on this phone, and with no workspace open none is running.
    if (workspacePath == null || agent == null) return null
    return runCatching {
        val rootfs =
            File(runtimeDirectory, "environment/antigravity-rootfs")
                .takeIf { agent == LocalAgent.ANTIGRAVITY && it.isDirectory }
                ?: File(runtimeDirectory, "environment/rootfs")
        val commandFile = File(rootfs, BROWSER_COMMAND_PATH)
        if (!commandFile.isFile) return null
        val command = JSONObject(commandFile.readText())
        val url = command.optString("url")
        val uri = runCatching { URI(url) }.getOrNull()
        val createdAtMs = command.optLong("createdAtMs", 0L)
        val fresh = clock().let { now -> createdAtMs in (now - MAX_COMMAND_AGE_MILLIS)..(now + MAX_CLOCK_SKEW_MILLIS) }
        val valid =
            fresh && command.optString("action") == "open" &&
                uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()
        // The agent writes this file directly, so one that does not parse may be half written and is
        // read again on the next poll. A complete one is used once and removed, whether it opens or
        // not (a request from long before the user came back, say); if it cannot be removed, it is
        // not opened, so it never opens twice.
        url.takeIf { commandFile.delete() && valid }
    }.getOrNull()
}

/**
 * How long an agent's command waits to be opened. The watcher only reads it while the app is on
 * screen, so this also drops a request made long before the user came back.
 */
private const val MAX_COMMAND_AGE_MILLIS = 5 * 60_000L

/** The guest reads the same kernel clock; this only absorbs the write and read landing apart. */
private const val MAX_CLOCK_SKEW_MILLIS = 60_000L
