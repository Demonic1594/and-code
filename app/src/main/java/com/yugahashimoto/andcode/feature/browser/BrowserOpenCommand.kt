package com.yugahashimoto.andcode.feature.browser

import android.util.Log
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.local.GuestFiles
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
 * Where the app records how a handoff stands (`browser_handoff`), relative to the rootfs, beside
 * [BROWSER_COMMAND_PATH]: the agent's tool polls it until the user hands the browser back.
 */
internal const val BROWSER_STATUS_PATH = "root/.local/share/and-code/browser-status.json"

internal const val BROWSER_STATUS_AWAITING_USER = "awaiting_user"
internal const val BROWSER_STATUS_AGENT = "agent"
internal const val BROWSER_STATUS_DISMISSED = "dismissed"

private const val TAG = "BrowserCommand"

/** What an agent's browser tool asked of the app, through the command file. */
sealed interface BrowserCommand {
    /** Opens the full-screen Guest Browser at [url]. */
    data class Open(
        val url: String,
    ) : BrowserCommand

    /** Pins [url], a page on this device, inside the conversation as a live preview. */
    data class Preview(
        val url: String,
    ) : BrowserCommand

    /**
     * Hands the page the user is already on to them and waits until they hand it back.
     *
     * It never carries a URL: acting on one here would reload the sign-in the user just completed.
     * The outcome goes to [BROWSER_STATUS_PATH] in [rootfs], the one the asking agent runs in.
     */
    data class Handoff(
        val rootfs: File,
    ) : BrowserCommand
}

/**
 * Records how a handoff stands for the agent's tool to read, in the guest home of [rootfs]. The guest
 * owns that home, so the write goes through [GuestFiles]: a link planted on the way is refused and one
 * at the file is replaced, never written through.
 */
internal fun writeBrowserStatus(
    rootfs: File,
    state: String,
) {
    try {
        GuestFiles.write(rootfs, BROWSER_STATUS_PATH, JSONObject().put("state", state).toString())
    } catch (error: IOException) {
        Log.w(TAG, "The handoff status could not be written", error)
    }
}

/** The rootfs the active local agent runs in: Antigravity's own Debian one when it has it. */
internal fun guestRootfs(
    agent: LocalAgent,
    runtimeDirectory: File,
): File =
    File(runtimeDirectory, "environment/antigravity-rootfs").takeIf { agent == LocalAgent.ANTIGRAVITY && it.isDirectory }
        ?: File(runtimeDirectory, "environment/rootfs")

/**
 * The host folder the active guest workspace is, the same one its PRoot process sees, or null when the
 * workspace is remote or the runtime is not local. The inline preview reads a static site and watches
 * for changes there.
 */
internal fun activeWorkspaceHostDirectory(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    deviceStorage: DeviceStorage.Mounts,
): File? {
    if (workspacePath == null || agent == null) return null
    return WorkspaceFolders.hostDirectory(
        guestRootfs(agent, runtimeDirectory),
        File(runtimeDirectory, "workspace"),
        workspacePath,
        deviceStorage,
    )
}

/**
 * The browser command an agent of the active local runtime left, consumed, or null.
 *
 * Read from the rootfs that agent runs in, whichever folder is open: the Guest Browser is one for the
 * whole app. Only a command stamped in the last few minutes counts; an older one is dropped. A
 * consumed handoff is recorded as `awaiting_user` before it is handed on, so the agent never reads a
 * stale answer from an earlier one.
 */
internal fun consumeBrowserCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    clock: () -> Long = System::currentTimeMillis,
): BrowserCommand? {
    // A remote workspace's agent is not on this phone, and with no workspace open none is running.
    if (workspacePath == null || agent == null) return null
    return runCatching {
        val rootfs = guestRootfs(agent, runtimeDirectory)
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
        // A guest script from before writes it in place rather than renaming it there, so one that does
        // not parse may be half written and is read again on the next poll. A complete one is used once
        // and removed, whether it is carried out or not (a request from long before the user came back,
        // say); if it cannot be removed, it is not carried out, so it never runs twice.
        browserCommandOf(JSONObject(bytes.decodeToString()), rootfs, clock())
            .takeIf { commandFile.delete() }
            ?.also { consumed -> if (consumed is BrowserCommand.Handoff) writeBrowserStatus(rootfs, BROWSER_STATUS_AWAITING_USER) }
    }.getOrNull()
}

/** The command [json] holds, if it is a fresh and well-formed one. */
private fun browserCommandOf(
    json: JSONObject,
    rootfs: File,
    now: Long,
): BrowserCommand? {
    val createdAtMs = json.optLong("createdAtMs", 0L)
    if (createdAtMs !in (now - MAX_COMMAND_AGE_MILLIS)..(now + MAX_CLOCK_SKEW_MILLIS)) return null
    val url = json.optString("url")
    // An open or a preview must name a real web page, never a javascript: or file: URL that would let
    // the agent reach past the browser. A preview stays on this device: the inline preview is a live
    // WebView inside the conversation, and an arbitrary public page is never loaded into it. A handoff
    // must not name one at all: it acts on the page the user is already on.
    return when (json.optString("action")) {
        "open" -> BrowserCommand.Open(url).takeIf { isWebAddress(url) }
        "preview" -> BrowserCommand.Preview(url).takeIf { isLoopbackPreviewUrl(url) }
        "handoff" -> BrowserCommand.Handoff(rootfs).takeIf { url.isBlank() }
        else -> null
    }
}

/** An http(s) address with a host: never file:, content:, javascript: or data:. */
private fun isWebAddress(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
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
 * How long an agent's command waits to be carried out. The watcher only reads it while the app is on
 * screen, so this also drops a request made long before the user came back.
 */
private const val MAX_COMMAND_AGE_MILLIS = 5 * 60_000L

/** Far more than any command (an address and a time), far less than a file worth reading every second. */
private const val MAX_COMMAND_BYTES = 16 * 1024

/** The guest reads the same kernel clock; this only absorbs the write and read landing apart. */
private const val MAX_CLOCK_SKEW_MILLIS = 60_000L
