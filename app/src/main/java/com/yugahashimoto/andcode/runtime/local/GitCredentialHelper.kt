package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Sets up GitHub git credentials inside the runtime rootfs.
 *
 * A `git-credential-opencode` helper script, registered for github.com as `helper = opencode`,
 * hands git the token from the guest's environment: `OPENCODE_GITHUB_TOKEN` (OpenCode and Claude
 * Code) or else `GH_TOKEN` (Codex). The token is never written to disk in the guest. Builds before
 * this one also wrote it to `~/.git-credentials` for `helper = store`; that file is deleted here, and
 * a `helper = store` some tool configured still works, because git asks the helpers in turn and
 * this one answers when the store has nothing.
 *
 * Every file goes through [GuestFiles], since the guest can turn any of these paths, or a folder
 * above them, into a link to one of this app's own files. A `.gitconfig` that is a link (a user's
 * dotfiles, say) is left as it is rather than merged into; the helper script is still written, for
 * a config that names it.
 */
class GitCredentialHelper(
    private val rootfs: File,
) {
    fun install(): File {
        GuestFiles.write(rootfs, HELPER_PATH, HELPER_SCRIPT, GuestFiles.OWNER_EXECUTABLE)
        GuestFiles.delete(rootfs, LEGACY_CREDENTIALS_PATH)
        installGitHubCredentialConfig()
        return File(rootfs, HELPER_PATH)
    }

    fun remove() {
        GuestFiles.delete(rootfs, HELPER_PATH)
        GuestFiles.delete(rootfs, LEGACY_CREDENTIALS_PATH)
        removeGitHubCredentialConfig()
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

        /** Where builds before this one stored the token in plain text; only ever deleted now. */
        const val LEGACY_CREDENTIALS_PATH = "root/.git-credentials"
        const val GITHUB_CREDENTIAL_SECTION = "[credential \"https://github.com\"]\n\thelper = opencode"
        const val GITHUB_CREDENTIAL_SECTION_MALFORMED = "[credential \"https://github.com\"]\n\\thelper = opencode"
        val HELPER_SCRIPT =
            """
            #!/bin/sh
            case "${'$'}1" in
              get)
                read protocol
                read host
                token="${'$'}{OPENCODE_GITHUB_TOKEN:-${'$'}GH_TOKEN}"
                [ -n "${'$'}token" ] && printf 'protocol=%s\nhost=%s\nusername=x-access-token\npassword=%s\n\n' "${'$'}protocol" "${'$'}host" "${'$'}token"
                ;;
            esac
            """.trimIndent()
    }
}
