package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import java.io.File

object AntigravitySandboxLauncher {
    /**
     * `script` allocates a PTY but only copies a window size from its own controlling terminal.
     * ProcessBuilder gives it pipes, so the slave PTY reports 0x0 and the official Bubble Tea TUI
     * renders an empty frame - including the sign-in screen that carries the OAuth URL. The size is
     * therefore set explicitly, and the width is chosen so the OAuth URL is never wrapped.
     */
    const val PTY_ROWS = 24
    const val PTY_COLUMNS = 1000

    /** Where [command] mounts the app's workspace; the parent of every project folder made in the app. */
    const val WORKSPACE_GUEST_DIR = "/workspace"

    /**
     * The folder agy starts in: the chat's own [directory], as Claude Code's `-w` and Codex's `cwd`
     * are, when agy's guest shares it ([isSharedGuestPath]) and [hostDirectory] finds it on the
     * device; [WORKSPACE_GUEST_DIR] otherwise.
     *
     * agy runs in a Debian guest of its own when one is installed, while the folder picker browses
     * the shared Alpine guest, so a folder such as `/root/project` there does not exist for agy. For a
     * folder its guest lacks - that one, or a project whose files were deleted - PRoot would start agy
     * in `/` instead, with the whole guest as its workspace.
     */
    fun workingDirectoryFor(
        directory: String,
        hostDirectory: (String) -> File?,
    ): String = directory.takeIf { isSharedGuestPath(it) && hostDirectory(it)?.isDirectory == true } ?: WORKSPACE_GUEST_DIR

    /**
     * Whether guest [path] is under the bind mounts every guest gets the same: the app's workspace and
     * device storage. Anything else is a folder of one guest's own.
     */
    fun isSharedGuestPath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = WorkspaceFolders.normalize(path)
        return normalized == WORKSPACE_GUEST_DIR ||
            WorkspaceFolders.isInsideWorkspaceRoot(normalized) ||
            DeviceStorage.isDeviceStoragePath(normalized)
    }

    /**
     * [workingDirectory] is the folder agy starts in, and agy takes that folder as its workspace: it
     * reads the project's `AGENTS.md` and `GEMINI.md` from there up to the repository root (see
     * docs/ANTIGRAVITY.md). A chat passes its own folder. Sign-in, `agy models` and chat naming belong
     * to no project and keep the default.
     */
    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: String,
        arguments: List<String>,
        pty: Boolean,
        workingDirectory: String = WORKSPACE_GUEST_DIR,
    ): List<String> =
        buildList {
            val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
            add(runtime.commandSuite.proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            add("-b")
            add("$workspaceHostDir:$WORKSPACE_GUEST_DIR")
            // Empty until the user grants all-files access, so the sandbox is unchanged without it.
            addAll(DeviceStorage.bindArguments())
            add("-w")
            add(workingDirectory.ifBlank { WORKSPACE_GUEST_DIR })
            if (pty) {
                add("/usr/bin/script")
                add("-qefc")
                add(ptyShellCommand(arguments))
                add("/dev/null")
            } else {
                add("/usr/local/bin/agy")
                addAll(arguments)
            }
        }

    /** `stty` runs first so the TUI has a real window size, then `exec` hands the PTY to agy. */
    internal fun ptyShellCommand(arguments: List<String>): String =
        "stty rows $PTY_ROWS cols $PTY_COLUMNS 2>/dev/null; exec " +
            (listOf("/usr/local/bin/agy") + arguments).joinToString(" ", transform = ::shellQuote)

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        tmp: File,
        githubToken: String? = null,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), tmp) +
            mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
                "HOME" to "/root",
                "TERM" to "xterm-256color",
                "AGY_CLI_DISABLE_AUTO_UPDATE" to "1",
                "AGY_CLI_HIDE_ACCOUNT_INFO" to "1",
                "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
                "CI" to "1",
            ) + REMOTE_SESSION +
            githubToken.orEmpty().takeIf { it.isNotBlank() }?.let { mapOf("GH_TOKEN" to it) }.orEmpty()

    /**
     * The guest has no D-Bus session and no local browser, which is exactly the remote-shell shape
     * the official CLI already supports: it then keeps tokens in a file under the guest `$HOME`
     * instead of blocking on a keyring, and prints the OAuth URL for manual transfer.
     *
     * This must be applied to *every* agy invocation, not only sign-in: the token store is selected
     * per process, so a later `agy models` launched without these markers would look in the keyring
     * and report the user as signed out.
     */
    private val REMOTE_SESSION =
        mapOf(
            "SSH_CONNECTION" to "127.0.0.1 22 127.0.0.1 22",
            "SSH_CLIENT" to "127.0.0.1 22 22",
            "SSH_TTY" to "/dev/pts/0",
        )
}
