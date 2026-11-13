package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Resolves the active guest workspace to the same host folder its PRoot process sees. */
internal fun consumeBrowserOpenCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    deviceStorage: DeviceStorage.Mounts,
    clock: () -> Long = System::currentTimeMillis,
): String? {
    // A remote workspace can share a path with a local one, but its commands are not on this phone.
    if (workspacePath == null || agent == null) return null
    return runCatching {
        val rootfs =
            File(runtimeDirectory, "environment/antigravity-rootfs")
                .takeIf { agent == LocalAgent.ANTIGRAVITY && it.isDirectory }
                ?: File(runtimeDirectory, "environment/rootfs")
        val workspace =
            WorkspaceFolders.hostDirectory(rootfs, File(runtimeDirectory, "workspace"), workspacePath, deviceStorage)
                ?: return null
        val commandFile = File(workspace, ".and-code/browser-command.json")
        if (!commandFile.isFile) return null
        val command = JSONObject(commandFile.readText())
        val url = command.optString("url")
        val uri = URI(url)
        // The agent's browser_show stamps each command. A file that came with a cloned repository
        // carries an old time or none, so it never opens a page on its own; it is also left where it
        // is, since deleting it would change the user's checkout.
        val createdAtMs = command.optLong("createdAtMs", 0L)
        val fresh = clock().let { now -> createdAtMs in (now - MAX_COMMAND_AGE_MILLIS)..(now + MAX_CLOCK_SKEW_MILLIS) }
        val valid =
            fresh && command.optString("action") == "open" &&
                uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        // The agent writes this file directly. Deleting before parsing can discard a partial write;
        // only consume a complete, valid command, and do not reopen it if deletion fails.
        url.takeIf { valid && commandFile.delete() }
    }.getOrNull()
}

/**
 * How long an agent's command waits to be opened. The watcher only reads it while the app is on
 * screen, so this also drops a request made long before the user came back.
 */
private const val MAX_COMMAND_AGE_MILLIS = 5 * 60_000L

/** The guest reads the same kernel clock; this only absorbs the write and read landing apart. */
private const val MAX_CLOCK_SKEW_MILLIS = 60_000L
