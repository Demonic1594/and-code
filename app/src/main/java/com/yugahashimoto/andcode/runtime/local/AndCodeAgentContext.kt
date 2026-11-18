package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal const val AND_CODE_AGENT_CONTEXT_ASSET = "and-code-agent-context.md"

private const val RUNTIME_CONTEXT_PATH = "root/.config/and-code/agent-context.md"

/**
 * Records the hash of what AndCode itself last wrote to each path in [AGENT_CONTEXT_PATHS], so a
 * later run can tell "still what we wrote" apart from "the user edited this since". One line per
 * entry, `<relativePath>\t<sha256>`.
 */
private const val WRITTEN_HASHES_PATH = "root/.config/and-code/agent-context-written.tsv"

/**
 * Size past which the sidecar is ignored rather than read. A legitimate one holds a line per
 * [AGENT_CONTEXT_PATHS] entry -- a short path plus a 64-character hash, a few hundred bytes in
 * total -- so this is already far more room than it can honestly need; it exists only so a
 * guest-planted file cannot make runtime startup read an arbitrary amount into memory.
 */
private const val MAX_WRITTEN_HASHES_BYTES = 64L * 1024

private val AGENT_CONTEXT_PATHS =
    listOf(
        "root/.config/opencode/and-code-context.md",
        "root/.claude/CLAUDE.md",
        "root/.gemini/GEMINI.md",
        // Codex reads global instructions from $CODEX_HOME/AGENTS.md; CODEX_HOME is /root/.codex.
        "root/.codex/AGENTS.md",
    )

internal fun ensureAndCodeAgentContext(
    rootfs: File,
    context: Context,
) {
    val agentContext =
        context.assets.open(AND_CODE_AGENT_CONTEXT_ASSET).use { input ->
            input.readBytes()
        }
    installAndCodeAgentContext(rootfs, agentContext)
}

/**
 * Writes the AndCode environment blurb into each coding agent's instructions file (Claude Code's
 * CLAUDE.md, Gemini's GEMINI.md, and OpenCode's instructions file).
 *
 * These are the exact files users write their own custom instructions into, and this runs on
 * every runtime start, so it must never clobber content the user has added. A path is only
 * (re)written when its current content still matches the hash of what *we* wrote there last time
 * -- once the file diverges from that (the user edited it, or created it themselves), it is left
 * alone from then on, even as the bundled blurb changes in later app updates.
 *
 * Every path this function touches lives inside the PRoot guest filesystem, which an agent can
 * write arbitrary content into -- including replacing one of these paths (or a parent directory)
 * with a symlink to one of this app's own files. Every read and write below goes through
 * [GuestFiles], which never follows such a link: an instructions file that is a link is left alone
 * like any other file the user has taken over, and a linked folder above one is refused.
 *
 * This function is best-effort from top to bottom: it must never abort or throw out of runtime
 * startup, which calls it on every launch (four call sites in `LocalRuntimeInstaller`). Every
 * filesystem-touching step -- the staging copy, the written-hashes sidecar, and each instruction
 * file -- is handled independently, and a filesystem problem on any single one of them is skipped
 * rather than propagated:
 * - The staging copy at [RUNTIME_CONTEXT_PATH] is only a convenience artifact nothing else reads,
 *   so an unwritable path there is simply skipped.
 * - The written-hashes sidecar being refused, unreadable, oversized, or unwritable does not
 *   stop the instruction files below from being (re)installed. Any of those is treated as
 *   an empty history: a target that already exists and differs from the current blurb is then
 *   treated as user-edited and left alone, which is the safe direction. A sidecar that can't be
 *   persisted afterwards simply means the write is retried on the next run; the install itself
 *   still succeeds.
 * - Each instruction file is handled independently: reading or writing one target can throw (a
 *   link, a folder in its place, a parent path replaced with a file, permission errors) without
 *   affecting the others, and without recording that target's hash as successfully written.
 *
 * The trade-off is that a failed install is silent: there is no signal back to the caller when a
 * path was skipped, by design, since surfacing or retrying failures is not worth risking runtime
 * startup over a best-effort convenience file.
 *
 * The blurb's hash is computed directly from [agentContext] (see [sha256Hex]) rather than from
 * the staging file, so installing the instruction files never depends on that staging copy
 * existing.
 */
internal fun installAndCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    val agentContextHash = sha256Hex(agentContext)

    // Best-effort: this is only the app's own staging copy of the blurb; nothing below depends on
    // it existing, so a failing write here may not stop the real install. The bytes go down
    // unconditionally rather than being compared against what is on disk first: rewriting a
    // kilobyte on each runtime start is cheaper than reading back whatever the guest left there.
    try {
        GuestFiles.write(rootfs, RUNTIME_CONTEXT_PATH, agentContext)
    } catch (e: IOException) {
        // Skip the staging copy -- see the comment above.
    }

    // A refused or unreadable sidecar degrades to "no recorded hashes" rather than aborting -- see
    // the safe-to-manage comment below for why that is still correct.
    val writtenHashes = readWrittenHashes(rootfs).toMutableMap()
    var hashesChanged = false

    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        // Best-effort per target: reading or writing this one path can throw (a link, a folder in
        // its place, a parent replaced with a file, permission errors). That must skip only this
        // target, not abort the others or runtime startup, which is what the KDoc promises. If the
        // write below fails partway, writtenHashes is never touched, so this target's hash is not
        // recorded as successfully written -- a later run will not mistake a failed write for
        // "still what we wrote".
        try {
            // A file too large to read here cannot be the blurb, nor what was last written, so the
            // refusal skipping it is the same answer the hash comparison would give.
            val currentHash = GuestFiles.readBytes(rootfs, relativePath)?.let(::sha256Hex)
            // Safe to (re)write when there is nothing there yet, when it already holds exactly the
            // current blurb (a no-op either way), or when it still matches the last content we wrote.
            // Anything else means the user has put their own content in the file since. When the
            // sidecar could not be read, writtenHashes is empty, so this only stays safe for the
            // first two cases -- an existing, differing file is (correctly) left alone.
            val safeToManage =
                currentHash == null || currentHash == agentContextHash || currentHash == writtenHashes[relativePath]
            if (!safeToManage) {
                // The file no longer matches what we last wrote: the user (or the agent, on their
                // behalf) has customized it since. Leave their content alone.
                return@forEach
            }
            if (currentHash != agentContextHash) {
                GuestFiles.write(rootfs, relativePath, agentContext)
            }
            if (writtenHashes[relativePath] != agentContextHash) {
                writtenHashes[relativePath] = agentContextHash
                hashesChanged = true
            }
        } catch (e: IOException) {
            // Skip just this target -- see the comment above.
        }
    }

    // Persisting the sidecar is also best-effort: if the write fails, the files above are already
    // installed correctly, and the sidecar write is simply retried on the next run.
    if (hashesChanged) {
        writeWrittenHashes(rootfs, writtenHashes)
    }
}

/** Lowercase-hex SHA-256 of [bytes], in the same format as [RuntimeArchive.sha256]. */
private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Reads the sidecar [writeWrittenHashes] left behind, bounded in both size and content.
 *
 * The file lives in the guest filesystem, where an agent can replace it with something enormous,
 * so anything larger than [MAX_WRITTEN_HASHES_BYTES] is refused outright by [GuestFiles]. That size
 * cap is what actually protects startup here: an `OutOfMemoryError` from reading a huge file whole
 * is an `Error`, not an [IOException], so the catch below would not stop it. Only keys naming a
 * real [AGENT_CONTEXT_PATHS] entry are kept, which bounds the returned map no matter what the file
 * holds.
 *
 * Best-effort otherwise: an [IOException] (a link, a transient I/O error, permissions, etc.) must
 * not abort [installAndCodeAgentContext] or the runtime startup that calls it, so it is treated the
 * same as "no sidecar yet" -- an empty map.
 */
private fun readWrittenHashes(rootfs: File): Map<String, String> {
    val text =
        try {
            GuestFiles.readText(rootfs, WRITTEN_HASHES_PATH, MAX_WRITTEN_HASHES_BYTES)
        } catch (e: IOException) {
            null
        } ?: return emptyMap()
    return text
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator < 0) return@mapNotNull null
            val path = line.substring(0, separator)
            if (path !in AGENT_CONTEXT_PATHS) return@mapNotNull null
            path to line.substring(separator + 1)
        }.toMap()
}

/**
 * Best-effort: an [IOException] persisting the sidecar must not fail the install that already
 * succeeded in (re)writing the instruction files above -- it is simply retried on the next run.
 */
private fun writeWrittenHashes(
    rootfs: File,
    hashes: Map<String, String>,
) {
    try {
        GuestFiles.write(rootfs, WRITTEN_HASHES_PATH, hashes.entries.joinToString("\n") { (path, hash) -> "$path\t$hash" })
    } catch (e: IOException) {
        // Ignored: see the KDoc above.
    }
}

private object RunSection {
    /** What a file must mention for its agent to know how to keep servers running. */
    const val MARKER = "andcode-run"

    /** Size past which an instructions file is not read for the run section (they are a few kilobytes). */
    const val MAX_FILE_BYTES = 256L * 1024
}

/**
 * The part of the bundled blurb that teaches `andcode-run`: from its heading to the next one.
 * Null when the blurb has no such section.
 */
internal fun runSectionOf(agentContext: String): String? {
    val lines = agentContext.lines()
    val start = lines.indexOfFirst { it.startsWith("## ") && RunSection.MARKER in it }
    if (start < 0) return null
    val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
    return lines.subList(start, end).joinToString("\n").trimEnd() + "\n"
}

/**
 * The instructions files ([AGENT_CONTEXT_PATHS], relative to [rootfs]) that exist but do not
 * mention `andcode-run`: the user or an agent changed them, so [installAndCodeAgentContext] leaves
 * them alone, and that agent was never told how to keep a server running. Files that are absent,
 * unmanageable or too large to be instructions are not reported.
 */
internal fun instructionFilesWithoutRunSection(rootfs: File): List<String> =
    AGENT_CONTEXT_PATHS.filter { relativePath ->
        try {
            val text = GuestFiles.readText(rootfs, relativePath, RunSection.MAX_FILE_BYTES)
            text != null && RunSection.MARKER !in text
        } catch (e: IOException) {
            false
        }
    }

/**
 * Appends [section] to the instructions file at [relativePath], at the user's request, keeping
 * everything already in it. False when the path is not one of [AGENT_CONTEXT_PATHS], cannot be
 * managed safely (see [GuestFiles]), or the write fails.
 */
internal fun appendRunSection(
    rootfs: File,
    relativePath: String,
    section: String,
): Boolean {
    if (relativePath !in AGENT_CONTEXT_PATHS) return false
    return try {
        val existing = GuestFiles.readText(rootfs, relativePath, RunSection.MAX_FILE_BYTES) ?: return false
        val separator =
            if (existing.endsWith("\n\n")) {
                ""
            } else if (existing.endsWith("\n")) {
                "\n"
            } else {
                "\n\n"
            }
        GuestFiles.write(rootfs, relativePath, existing + separator + section)
        true
    } catch (e: IOException) {
        false
    }
}
