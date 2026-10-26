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

    /** Verdict for the bytes of a file on disk, or for text about to be written. Null means editable. */
    fun refusalFor(bytes: ByteArray): FileEditFailure? =
        when {
            bytes.size > MAX_EDIT_BYTES -> FileEditFailure.TOO_LARGE
            bytes.any { it == 0.toByte() } || !isValidUtf8(bytes) -> FileEditFailure.NOT_TEXT
            else -> null
        }

    private fun isValidUtf8(bytes: ByteArray): Boolean =
        runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        }.isSuccess
}
