package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Sets up GitHub git credentials inside the runtime rootfs.
 *
 * Two mechanisms are configured so git works regardless of which credential
 * helper the gitconfig ends up using (OpenCode itself may switch it to "store"):
 *  1. A `git-credential-opencode` helper script that reads OPENCODE_GITHUB_TOKEN
 *     from the environment (registered as `helper = opencode`).
 *  2. A `~/.git-credentials` store file (used by `helper = store`).
 *
 * Every file goes through [GuestFiles], since the guest can turn any of these paths, or a folder
 * above them, into a link to one of this app's own files. A `.gitconfig` that is a link (a user's
 * dotfiles, say) is left as it is rather than merged into; the helper script and the store are
 * still written, so the token reaches git through whichever helper that config names.
 */
class GitCredentialHelper(
    private val rootfs: File,
    private val token: () -> String?,
) {
    fun install(): File {
        GuestFiles.write(rootfs, HELPER_PATH, HELPER_SCRIPT, GuestFiles.OWNER_EXECUTABLE)
        writeCredentialStore()
        installGitHubCredentialConfig()
        return File(rootfs, HELPER_PATH)
    }

    fun remove() {
        GuestFiles.delete(rootfs, HELPER_PATH)
        GuestFiles.delete(rootfs, CREDENTIALS_PATH)
        removeGitHubCredentialConfig()
    }

    private fun writeCredentialStore() {
        val current = token()
        if (current.isNullOrBlank()) {
            GuestFiles.delete(rootfs, CREDENTIALS_PATH)
            return
        }
        GuestFiles.write(rootfs, CREDENTIALS_PATH, "https://x-access-token:$current@github.com\n", GuestFiles.OWNER_READ_WRITE)
    }

    private fun installGitHubCredentialConfig() {
        val existing = GuestFiles.readText(rootfs, GITCONFIG_PATH).orEmpty()
        val cleaned =
            existing
                .replace(GITHUB_CREDENTIAL_SECTION_MALFORMED, "")
                .replace(GITHUB_CREDENTIAL_SECTION, "")
                .trim()
        GuestFiles.write(
            rootfs,
            GITCONFIG_PATH,
            listOf(cleaned, GITHUB_CREDENTIAL_SECTION)
                .filter(String::isNotEmpty)
                .joinToString("\n\n", postfix = "\n"),
        )
    }

    private fun removeGitHubCredentialConfig() {
        val existing = GuestFiles.readText(rootfs, GITCONFIG_PATH) ?: return
        val remaining =
            existing
                .replace(GITHUB_CREDENTIAL_SECTION, "")
                .replace(GITHUB_CREDENTIAL_SECTION_MALFORMED, "")
                .trim()
        if (remaining.isEmpty()) GuestFiles.delete(rootfs, GITCONFIG_PATH) else GuestFiles.write(rootfs, GITCONFIG_PATH, "$remaining\n")
    }

    private companion object {
        const val HELPER_PATH = "usr/local/bin/git-credential-opencode"
        const val GITCONFIG_PATH = "root/.gitconfig"
        const val CREDENTIALS_PATH = "root/.git-credentials"
        const val GITHUB_CREDENTIAL_SECTION = "[credential \"https://github.com\"]\n\thelper = opencode"
        const val GITHUB_CREDENTIAL_SECTION_MALFORMED = "[credential \"https://github.com\"]\n\\thelper = opencode"
        val HELPER_SCRIPT =
            """
            #!/bin/sh
            case "${'$'}1" in
              get)
                read protocol
                read host
                token="${'$'}OPENCODE_GITHUB_TOKEN"
                [ -n "${'$'}token" ] && printf 'protocol=%s\nhost=%s\nusername=x-access-token\npassword=%s\n\n' "${'$'}protocol" "${'$'}host" "${'$'}token"
                ;;
            esac
            """.trimIndent()
    }
}
