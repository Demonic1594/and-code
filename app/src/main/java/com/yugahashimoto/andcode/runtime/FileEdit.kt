package com.yugahashimoto.andcode.runtime

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Why a workspace file could not be opened for editing or saved. */
enum class FileEditFailure {
    /** The path leaves the workspace root, by `..` or through a symlink. */
    OUTSIDE_WORKSPACE,
    NOT_FOUND,

    /** Binary data, or text that is not valid UTF-8 and would be corrupted by decoding and re-encoding it. */
    NOT_TEXT,
    TOO_LARGE,

    /**
     * A single line past [FileEditPolicy.MAX_LINE_CHARS]. The editor lays each line out as one row,
     * and a minified single-line file inside the size cap still freezes layout until the system
     * kills the app as unresponsive (verified on device at a 199k-character line).
     */
    LINE_TOO_LONG,

    /** The file is marked read-only; replacing it by rename would get past that, so it is refused up front. */
    READ_ONLY,

    /** The file on disk no longer holds what the editor loaded, so saving would silently discard that change. */
    CHANGED_ON_DISK,
    WRITE_FAILED,
}

/** Thrown by [OpenCodeBackend.readFileForEditing] and [OpenCodeBackend.writeFile]; [failure] says why. */
class FileEditException(
    val failure: FileEditFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * What the in-app editor will touch: small UTF-8 text without NUL bytes.
 *
 * The editor holds the whole file as one string, so size is capped to keep typing responsive. The
 * encoding rule is about data loss rather than taste: a Latin-1 file decoded as UTF-8 comes back
 * with replacement characters, so saving it would corrupt every accented letter in it.
 */
object FileEditPolicy {
    const val MAX_EDIT_BYTES = 256L * 1024

    /** [MAX_EDIT_BYTES] as the figure shown to the user. */
    const val MAX_EDIT_KIBIBYTES = MAX_EDIT_BYTES / 1024

    /**
     * Longest single line the editor will lay out as one row. Normal code stays far below this;
     * minified files do not, and on them the editor's text layout never finishes: the system
     * reports an ANR and kills the app. Refusing the edit costs little - a minified file is not
     * something anyone edits by hand on a phone. Counted in bytes, so a multi-byte script's line
     * reaches the cap slightly early - the safe direction.
     */
    const val MAX_LINE_BYTES = 20_000L

    /** Verdict for the bytes of a file on disk, or for text about to be written. Null means editable. */
    fun refusalFor(bytes: ByteArray): FileEditFailure? =
        when {
            bytes.size > MAX_EDIT_BYTES -> FileEditFailure.TOO_LARGE
            bytes.any { it == 0.toByte() } || !isValidUtf8(bytes) -> FileEditFailure.NOT_TEXT
            else -> longestLineIn(bytes)?.let { if (it > MAX_LINE_BYTES) FileEditFailure.LINE_TOO_LONG else null }
        }

    /** The longest line's length in bytes, or null when [bytes] holds no text lines at all. */
    private fun longestLineIn(bytes: ByteArray): Long? {
        var longest = 0L
        var current = 0L
        for (byte in bytes) {
            if (byte == '\n'.code.toByte()) {
                if (current > longest) longest = current
                current = 0
            } else {
                current++
            }
        }
        return maxOf(longest, current)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean =
        runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        }.isSuccess
}
