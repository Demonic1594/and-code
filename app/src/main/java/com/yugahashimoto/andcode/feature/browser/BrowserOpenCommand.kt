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

/**
 * Where the app tells the guest's browser tool which browser the agent is on and which page is the
 * hidden one ([AgentBrowserState.statusJson]), beside [BROWSER_COMMAND_PATH].
 */
internal const val BROWSER_STATE_PATH = "root/.local/share/and-code/browser-state.json"

internal const val BROWSER_STATUS_AWAITING_USER = "awaiting_user"
internal const val BROWSER_STATUS_AGENT = "agent"
internal const val BROWSER_STATUS_DISMISSED = "dismissed"

private const val TAG = "BrowserCommand"

/** Which browser an agent works in: the Guest Browser on the user's screen, or a hidden one of its own. */
enum class BrowserMode(
    val id: String,
) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ;

    companion object {
        /** The mode [id] names; none named is [VISIBLE], as every command was before there were modes. */
        fun fromId(id: String): BrowserMode? = if (id.isEmpty()) VISIBLE else entries.firstOrNull { it.id == id }
    }
}

/** What an agent's browser tool asked of the app, through the command file. */
sealed interface BrowserCommand {
    /** Names the request in the status the app writes back, so the tool knows its answer has come. */
    val requestId: String

    /**
     * Opens [url] in the browser [mode] names: the full-screen Guest Browser, or the hidden one. A
     * visible open without [url] hands the hidden page to the user as it is, as the notice's View does.
     * A visible one closes or hands over only the hidden page [ownHidden] names: the asking agent's own,
     * by the request it last answered, never another agent's.
     */
    data class Open(
        val url: String?,
        val mode: BrowserMode = BrowserMode.VISIBLE,
        override val requestId: String = "",
        val ownHidden: String? = null,
    ) : BrowserCommand

    /** Pins [url], a page on this device, inside the conversation as a live preview. */
    data class Preview(
        val url: String,
        override val requestId: String = "",
    ) : BrowserCommand

    /**
     * Hands the page the user is already on to them and waits until they hand it back.
     *
     * It never carries a URL: acting on one here would reload the sign-in the user just completed.
     * The outcome goes to [BROWSER_STATUS_PATH] in [rootfs], the one the asking agent runs in. The
     * hidden page is brought on screen first only when it is the asking agent's own ([ownHidden]).
     */
    data class Handoff(
        val rootfs: File,
        override val requestId: String = "",
        val ownHidden: String? = null,
    ) : BrowserCommand

    /** Closes the hidden browser. */
    data class Close(
        override val requestId: String = "",
    ) : BrowserCommand
}

/**
 * Records how the handoff [requestId] stands for the agent's tool to read, in the guest home of
 * [rootfs]; false when it could not be written. The guest owns that home, so the write goes through
 * [GuestFiles]: a link planted on the way is refused and one at the file is replaced, never written
 * through.
 */
internal fun writeBrowserStatus(
    rootfs: File,
    state: String,
    requestId: String = "",
): Boolean =
    try {
        GuestFiles.write(rootfs, BROWSER_STATUS_PATH, JSONObject().put("state", state).put("requestId", requestId).toString())
        true
    } catch (error: IOException) {
        Log.w(TAG, "The handoff status could not be written", error)
        false
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
 * Read from the rootfs that agent runs in, whichever folder is open: the browsers are the whole
 * app's. Only a command stamped in the last few minutes counts; an older one, or one that is not a
 * command, is dropped. One that [accept] turns down (a visible open while the app is in the
 * background) stays in place for a later read. A consumed handoff is recorded as `awaiting_user`
 * before it is handed on, so the agent never reads a stale answer from an earlier one.
 */
internal fun consumeBrowserCommand(
    agent: LocalAgent?,
    runtimeDirectory: File,
    clock: () -> Long = System::currentTimeMillis,
    accept: (BrowserCommand) -> Boolean = { true },
): BrowserCommand? {
    // A remote runtime's agent is not on this phone.
    if (agent == null) return null
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
        // say); if it cannot be removed, it is not carried out, so it never runs twice. One the app
        // cannot act on yet stays as it is.
        val command = browserCommandOf(JSONObject(bytes.decodeToString()), rootfs, clock())
        if (command != null && !accept(command)) {
            null
        } else {
            command
                .takeIf { commandFile.delete() }
                ?.also { consumed ->
                    if (consumed is BrowserCommand.Handoff) writeBrowserStatus(rootfs, BROWSER_STATUS_AWAITING_USER, consumed.requestId)
                }
        }
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
    val url = json.optString("url").takeIf { it.isNotEmpty() }
    val requestId = json.optString("requestId").take(MAX_REQUEST_ID_LENGTH)
    val ownHidden = json.optString("hidden").take(MAX_REQUEST_ID_LENGTH).takeIf { it.isNotEmpty() }
    // An open or a preview must name a real web page, never a javascript: or file: URL that would let
    // the agent reach past the browser. A preview stays on this device: the inline preview is a live
    // WebView inside the conversation, and an arbitrary public page is never loaded into it. A handoff
    // must not name one at all: it acts on the page the user is already on.
    return when (json.optString("action")) {
        "open" -> openCommandOf(json, url, requestId)?.copy(ownHidden = ownHidden)
        "preview" -> url?.takeIf(::isLoopbackPreviewUrl)?.let { BrowserCommand.Preview(it, requestId) }
        "handoff" -> BrowserCommand.Handoff(rootfs, requestId, ownHidden).takeIf { url == null }
        "close" -> BrowserCommand.Close(requestId)
        else -> null
    }
}

/** An open in the browser [json] names: only the visible one can be opened without an address, to show the hidden page. */
private fun openCommandOf(
    json: JSONObject,
    url: String?,
    requestId: String,
): BrowserCommand.Open? {
    val mode = BrowserMode.fromId(json.optString("mode")) ?: return null
    return when {
        url != null -> BrowserCommand.Open(url, mode, requestId).takeIf { isWebAddress(url) }
        // Without an address only the hand-over, which names the visible browser itself: a command with
        // neither, as every command was before there were modes, opens nothing.
        mode == BrowserMode.VISIBLE && json.has("mode") -> BrowserCommand.Open(null, mode, requestId)
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

/** The script's request ids are short; a longer one is cut rather than copied into every status. */
private const val MAX_REQUEST_ID_LENGTH = 64
