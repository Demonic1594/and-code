package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Where the guest's browser tool (andcode-browser-mcp.py) leaves its command, relative to the rootfs.
 *
 * In the guest's home, outside every workspace: a command file inside the workspace could also
 * come with a cloned or pulled repository and open whatever page that repository chose.
 */
internal const val BROWSER_COMMAND_PATH = "root/.local/share/and-code/browser-command.json"

/**
 * The URL of the browser command an agent of the active local runtime left, consumed, or null.
 *
 * Read from the rootfs that agent runs in, whichever folder it is working in: the Guest Browser
 * is one for the whole app.
 */
internal fun consumeBrowserOpenCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
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
        val uri = URI(url)
        val valid = command.optString("action") == "open" && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        // The agent writes this file directly. Deleting before parsing can discard a partial write;
        // only consume a complete, valid command, and do not reopen it if deletion fails.
        url.takeIf { valid && commandFile.delete() }
    }.getOrNull()
}
