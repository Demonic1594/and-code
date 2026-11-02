package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.IOException

/**
 * The agents' instructions files that do not teach `andcode-run`, and what can be done about them.
 *
 * AndCode stops rewriting an instructions file once the user (or an agent) has changed it, so an
 * edited CLAUDE.md or AGENTS.md never gets the section on keeping servers running, and that agent
 * falls back to `nohup`, which dies when the phone sleeps. This finds those files so the app can
 * say so once, and adds the section to one when the user asks.
 */
interface RunInstructions {
    /** Guest paths (such as `/root/.claude/CLAUDE.md`) without the section, minus those dismissed. */
    fun missing(): List<String>

    /** Appends the section to [path]; false when that could not be done. */
    fun add(path: String): Boolean

    /** Stops reporting [paths]. */
    fun dismiss(paths: List<String>)
}

/**
 * [RunInstructions] over the installed runtime's guest filesystem. [rootfs] is null while no
 * runtime is installed, and [blurb] reads the bundled agent context the section comes from.
 * Dismissals are kept in [dismissedFile], outside the guest, as one path per line.
 */
class GuestRunInstructions(
    private val rootfs: () -> File?,
    private val blurb: () -> String?,
    private val dismissedFile: File,
) : RunInstructions {
    override fun missing(): List<String> {
        val root = rootfs() ?: return emptyList()
        val dismissed = dismissed()
        return instructionFilesWithoutRunSection(root).map { "/$it" }.filterNot { it in dismissed }
    }

    override fun add(path: String): Boolean {
        val root = rootfs() ?: return false
        val section = blurb()?.let(::runSectionOf) ?: return false
        return appendRunSection(root, path.removePrefix("/"), section)
    }

    override fun dismiss(paths: List<String>) {
        try {
            dismissedFile.parentFile?.mkdirs()
            dismissedFile.writeText((dismissed() + paths).joinToString("\n"))
        } catch (e: IOException) {
            // The notice simply shows again next time.
        }
    }

    private fun dismissed(): Set<String> =
        try {
            if (dismissedFile.isFile) dismissedFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
        } catch (e: IOException) {
            emptySet()
        }
}
