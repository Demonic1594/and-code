package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption

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
        val path = commandFile.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        // Only a small file of its own: a link could point at one of this app's files, and a big file
        // would be read again every second. Opened without following a link, and read no further than
        // the limit, so swapping one in after the check does not get around it.
        val bytes = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) readAtMost(path, MAX_COMMAND_BYTES + 1) else null
        if (bytes == null || bytes.size > MAX_COMMAND_BYTES) {
            commandFile.delete()
            return null
        }
        val command = JSONObject(bytes.decodeToString())
        val url = command.optString("url")
        val uri = runCatching { URI(url) }.getOrNull()
        val createdAtMs = command.optLong("createdAtMs", 0L)
        val fresh = clock().let { now -> createdAtMs in (now - MAX_COMMAND_AGE_MILLIS)..(now + MAX_CLOCK_SKEW_MILLIS) }
        val valid =
            fresh && command.optString("action") == "open" &&
                uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()
        // A guest script from before writes it in place rather than renaming it there, so one that does
        // not parse may be half written and is read again on the next poll. A complete one is used once and removed, whether it opens or
        // not (a request from long before the user came back, say); if it cannot be removed, it is
        // not opened, so it never opens twice.
        url.takeIf { commandFile.delete() && valid }
    }.getOrNull()
}

/** Up to [limit] bytes of the file at [path], opened without following a link. */
private fun readAtMost(
    path: java.nio.file.Path,
    limit: Int,
): ByteArray =
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = input.read(buffer, read, limit - read)
            if (count < 0) break
            read += count
        }
        buffer.copyOf(read)
    }

/**
 * How long an agent's command waits to be opened. The watcher only reads it while the app is on
 * screen, so this also drops a request made long before the user came back.
 */
private const val MAX_COMMAND_AGE_MILLIS = 5 * 60_000L

/** Far more than any command (an address and a time), far less than a file worth reading every second. */
private const val MAX_COMMAND_BYTES = 16 * 1024

/** The guest reads the same kernel clock; this only absorbs the write and read landing apart. */
private const val MAX_CLOCK_SKEW_MILLIS = 60_000L
