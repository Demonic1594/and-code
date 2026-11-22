package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import androidx.annotation.StringRes
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.locale.AppLanguage

/**
 * An installer failure, worded for the user.
 *
 * The installers run without a Context, so they cannot read a string resource. They throw the
 * wording as a resource and its arguments instead, and whatever shows the failure, a screen or the
 * runtime's notification, reads it in the app's language through [describeFailure]. [message]
 * keeps the English wording for logs and crash reports.
 */
class InstallerException(
    @StringRes val messageRes: Int,
    val formatArgs: List<Any>,
    englishMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(englishMessage, cause) {
    /** An argument that is itself a string resource, read in the same language as the message. */
    data class Text(
        @StringRes val res: Int,
    )

    /** The wording as [getString] reads it; a [Text] argument is read by it too. */
    fun localized(getString: (res: Int, args: Array<Any>) -> String): String =
        getString(messageRes, formatArgs.map { if (it is Text) getString(it.res, emptyArray()) else it }.toTypedArray())
}

/**
 * What the user reads about [error]: an [InstallerException] in the language picked in AndCode, any
 * other error by its own message. Null when it has none, for the caller's own fallback.
 */
fun Context.describeFailure(error: Throwable): String? {
    if (error !is InstallerException) return error.message
    val text = AppLanguage.current(this)
    return error.localized { res, args -> text.getString(res, *args) }
}

/**
 * Every installer failure the user can be shown, one object per installer, in one place so a test
 * can hold each English message to its string resource. The English here is what the installers
 * threw before; keep the two in step.
 */
internal object InstallerErrors {
    private val retryHint = InstallerException.Text(R.string.installer_error_retry_hint)

    /** [DebianRootfsInstaller]: the Debian environment Antigravity runs in. */
    object Debian {
        fun tokenHttp(code: Int) =
            failure(R.string.installer_error_debian_token_http, "Debian registry token request failed with HTTP $code", code)

        fun tokenMissing() =
            failure(R.string.installer_error_debian_token_missing, "Debian registry token response did not contain a token")

        fun activateFailed() = failure(R.string.installer_error_debian_activate, "Unable to activate Debian Antigravity rootfs")

        fun ptyMissing() = failure(R.string.installer_error_debian_pty_missing, "Debian PTY utility was not installed")

        fun aptRefused(refused: Throwable): InstallerException {
            val reason = refused.message.orEmpty()
            return failure(
                R.string.installer_error_debian_apt_refused,
                "Cannot configure apt in the Antigravity Linux environment: $reason. " +
                    "Replace that link with a real folder or file inside the environment, then try again.",
                reason,
                cause = refused,
            )
        }

        fun toolsTimedOut() =
            failure(
                R.string.installer_error_debian_tools_timeout,
                "Debian development tool installation timed out. $PACKAGE_INSTALL_RETRY_HINT",
                retryHint,
            )

        fun packagesFailed(logTail: String) =
            failure(
                R.string.installer_error_debian_packages_failed,
                "Unable to install Debian runtime packages. $PACKAGE_INSTALL_RETRY_HINT\n\nLast log lines:\n$logTail",
                retryHint,
                logTail,
            )

        fun githubCliTimedOut() = failure(R.string.installer_error_github_cli_timeout, "GitHub CLI installation timed out")

        fun githubCliFailed(logTail: String) =
            failure(R.string.installer_error_github_cli_failed, "Unable to install GitHub CLI: $logTail", logTail)

        fun glibcLoaderMissing(loaderName: String) =
            failure(R.string.installer_error_glibc_loader_missing, "Debian glibc loader is missing: $loaderName", loaderName)
    }

    /** [LocalRuntimeInstaller]: the shared Linux environment every agent runs in. */
    object Environment {
        fun toolsTimedOut() =
            failure(
                R.string.installer_error_tools_timeout,
                "Development tool installation timed out. $PACKAGE_INSTALL_RETRY_HINT",
                retryHint,
            )

        fun packagesFailed(logTail: String) =
            failure(
                R.string.installer_error_packages_failed,
                "Unable to install runtime packages. $PACKAGE_INSTALL_RETRY_HINT\n\nLast log lines:\n$logTail",
                retryHint,
                logTail,
            )

        fun missing() = failure(R.string.installer_error_environment_missing, "The Linux environment is not installed")

        fun antigravityMissing() =
            failure(R.string.installer_error_antigravity_environment_missing, "The Antigravity Linux environment is not installed")

        fun openCodeBinaryMissing() =
            failure(R.string.installer_error_opencode_binary_missing, "OpenCode archive did not contain the opencode binary")

        fun openCodeNotExecutable() = failure(R.string.installer_error_opencode_not_executable, "Unable to mark OpenCode executable")

        fun prootMissing() = failure(R.string.installer_error_proot_missing, "PRoot launcher is unavailable")
    }

    /** [ClaudeCodeInstaller]. */
    object Claude {
        fun timedOut(minutes: Long) =
            failure(
                R.string.installer_error_claude_timeout,
                "Claude Code package operation timed out after $minutes minutes. $PACKAGE_INSTALL_RETRY_HINT",
                minutes,
                retryHint,
            )

        /** [details] is the log excerpt that follows the hint, starting on a new line (or empty). */
        fun failed(
            update: Boolean,
            exitCode: Int,
            primary: String,
            details: String,
        ) = failure(
            if (update) R.string.installer_error_claude_update_failed else R.string.installer_error_claude_install_failed,
            "Claude Code ${if (update) "update" else "installation"} failed (exit $exitCode): " +
                "$primary\n$PACKAGE_INSTALL_RETRY_HINT$details",
            exitCode,
            primary,
            retryHint,
            details,
        )

        fun binaryMissing(path: String) =
            failure(R.string.installer_error_claude_binary_missing, "Claude Code reported success but $path is missing", path)
    }

    /** [CodexInstaller]. */
    object Codex {
        fun unsupportedAbi(abi: String) = failure(R.string.installer_error_codex_abi, "Unsupported Android ABI for Codex: $abi", abi)

        fun downloadHttp(code: Int) = failure(R.string.installer_error_codex_download_http, "Codex download failed with HTTP $code", code)

        fun downloadEmpty() = failure(R.string.installer_error_codex_download_empty, "Codex download response had no body")

        fun checksumMismatch(
            expected: String,
            actual: String,
        ) = failure(
            R.string.installer_error_codex_checksum,
            "SHA-512 mismatch for Codex download: expected $expected, got $actual",
            expected,
            actual,
        )

        fun tarballIncomplete(missing: String) =
            failure(R.string.installer_error_codex_tarball_incomplete, "Codex tarball does not contain $missing", missing)

        fun binaryMissing(path: String) =
            failure(R.string.installer_error_codex_binary_missing, "Codex reported a successful download but $path is missing", path)
    }

    /** [AntigravityInstaller]. */
    object Antigravity {
        /** States the gate from [AntigravityManifest.MIN_FREE_BYTES] itself, so the two cannot drift apart. */
        fun lowSpace(availableBytes: Long): InstallerException {
            val minimumMegabytes = AntigravityManifest.MIN_FREE_BYTES / BYTES_PER_MEGABYTE
            return failure(
                R.string.installer_error_antigravity_space_needed,
                "Antigravity needs at least $minimumMegabytes MB free space (available $availableBytes bytes)",
                minimumMegabytes,
                availableBytes,
            )
        }

        private const val BYTES_PER_MEGABYTE = 1024L * 1024L

        fun binaryMissing() =
            failure(R.string.installer_error_antigravity_binary_missing, "Official Antigravity archive did not contain an agy binary")
    }

    private fun failure(
        @StringRes res: Int,
        english: String,
        vararg args: Any,
        cause: Throwable? = null,
    ) = InstallerException(res, args.toList(), english, cause)
}
