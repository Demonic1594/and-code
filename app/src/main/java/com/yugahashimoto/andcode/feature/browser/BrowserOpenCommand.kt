package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * The URL of the browser command waiting in the active guest workspace, consumed, or null.
 *
 * Resolves the workspace to the same host folder its PRoot process sees. A command written before
 * [notBefore] (epoch millis, when the app began watching this workspace) is deleted unopened: the
 * agent writes its command while the workspace is open, whereas one that came with a cloned or
 * checked-out repository was there already, and would open whatever page that repository chose.
 */
internal fun consumeBrowserOpenCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    deviceStorage: DeviceStorage.Mounts,
    notBefore: Long = 0L,
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
        val stale = commandFile.isFile && commandFile.lastModified() < notBefore
        if (stale) commandFile.delete()
        if (stale || !commandFile.isFile) return null
        val command = JSONObject(commandFile.readText())
        val url = command.optString("url")
        val uri = URI(url)
        val valid = command.optString("action") == "open" && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        // The agent writes this file directly. Deleting before parsing can discard a partial write;
        // only consume a complete, valid command, and do not reopen it if deletion fails.
        url.takeIf { valid && commandFile.delete() }
    }.getOrNull()
}
