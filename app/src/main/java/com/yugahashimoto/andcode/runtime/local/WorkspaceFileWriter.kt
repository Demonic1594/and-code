package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.FileEditException
import com.yugahashimoto.andcode.runtime.FileEditFailure
import com.yugahashimoto.andcode.runtime.FileEditPolicy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saves edits to a file inside a workspace directory on the device.
 *
 * Plain files and `java.io` only, so the rules that protect the user's files - where a write may
 * land, what may be overwritten, and that a failed save leaves the old file intact - are covered by
 * ordinary unit tests instead of needing a device.
 *
 * Both the root and the target are resolved to their canonical form before anything is compared, so
 * `..` segments and symlinks cannot be used to reach a file outside the workspace. The check is made
 * once, immediately before the write; it does not defend against another process swapping a
 * directory for a symlink in that window, which would need the agent in the guest to be hostile to
 * its own workspace.
 */
object WorkspaceFileWriter {
    /**
     * Replaces the text file at [relativePath] under [root] with [content].
     *
     * When [expectedContent] is given it is what the caller last read, and the write only goes ahead
     * if the file still holds exactly that: an agent is usually working in the same workspace, and
     * saving over its change unseen would lose it.
     *
     * @throws FileEditException with the reason, before touching the file, whenever it is refused.
     */
    fun write(
        root: File,
        relativePath: String,
        content: String,
        expectedContent: String?,
    ) {
        val target = editableTarget(root, relativePath)
        val replacement = content.toByteArray(Charsets.UTF_8)
        FileEditPolicy.refusalFor(replacement)?.let { refuse(it, "The new content cannot be saved as text") }
        val current = readEditableBytes(target)
        if (expectedContent != null && !current.contentEquals(expectedContent.toByteArray(Charsets.UTF_8))) {
            refuse(FileEditFailure.CHANGED_ON_DISK, "${target.name} changed on disk since it was opened")
        }
        replaceAtomically(target, replacement)
    }

    /**
     * The exact text of the file at [relativePath] under [root], for the editor to start from.
     *
     * Read here rather than taken from whatever the viewer showed: a server that trims or otherwise
     * normalises what it returns would make the first save rewrite the whole file, and would never
     * match the content [write] checks against. The same refusals apply as for a write, so a file
     * that could not be saved is turned away before the user types a character into it.
     */
    fun readForEditing(
        root: File,
        relativePath: String,
    ): String = String(readEditableBytes(editableTarget(root, relativePath)), Charsets.UTF_8)

    /**
     * The canonical file for [relativePath] under [root], or null when it is [root] itself or lies
     * outside it. Symlinks are followed before the comparison, so a link inside the workspace that
     * points out of it resolves to a path that fails the check.
     */
    fun resolveInside(
        root: File,
        relativePath: String,
    ): File? {
        val canonicalRoot = canonicalOrNull(root) ?: return null
        val resolved = canonicalOrNull(File(canonicalRoot, relativePath)) ?: return null
        return resolved.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun editableTarget(
        root: File,
        relativePath: String,
    ): File {
        val target =
            resolveInside(root, relativePath)
                ?: refuse(FileEditFailure.OUTSIDE_WORKSPACE, "$relativePath is outside the workspace")
        if (!target.isFile) refuse(FileEditFailure.NOT_FOUND, "$relativePath is not a file")
        // Looked at before reading, so a huge file is never pulled into memory just to be refused.
        if (target.length() > FileEditPolicy.MAX_EDIT_BYTES) refuse(FileEditFailure.TOO_LARGE, "${target.name} is too large to edit")
        // Replacing by rename would otherwise get past a read-only file the user marked on purpose.
        if (!target.canWrite()) refuse(FileEditFailure.READ_ONLY, "${target.name} is read-only")
        return target
    }

    private fun readEditableBytes(target: File): ByteArray {
        val bytes = readCurrent(target)
        FileEditPolicy.refusalFor(bytes)?.let { refuse(it, "${target.name} is not editable text") }
        return bytes
    }

    private fun readCurrent(target: File): ByteArray =
        try {
            target.readBytes()
        } catch (error: IOException) {
            refuse(FileEditFailure.WRITE_FAILED, "Could not read ${target.name}", error)
        }

    /**
     * Writes to a sibling temporary file and renames it over [target], so a crash, a full disk or a
     * killed process leaves either the old file or the new one - never half of each.
     *
     * The temporary file sits in the same directory because a rename only stays atomic within one
     * filesystem. It is hidden and always removed, and the replaced file's permission bits are
     * copied across so editing a script does not quietly drop its executable bit.
     */
    private fun replaceAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val directory = target.parentFile ?: refuse(FileEditFailure.WRITE_FAILED, "${target.name} has no parent folder")
        var temporary: File? = null
        try {
            val created = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
            temporary = created
            FileOutputStream(created).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            copyPermissions(from = target, to = created)
            Files.move(created.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            refuse(FileEditFailure.WRITE_FAILED, "Could not save ${target.name}", error)
        } finally {
            // After a successful move there is nothing at this path any more.
            temporary?.delete()
        }
    }

    /** Best effort: some filesystems (shared storage) have no POSIX permissions to copy. */
    private fun copyPermissions(
        from: File,
        to: File,
    ) {
        runCatching {
            Files.setPosixFilePermissions(to.toPath(), Files.getPosixFilePermissions(from.toPath()))
        }
    }

    private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun refuse(
        failure: FileEditFailure,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw FileEditException(failure, message, cause)

    private const val TEMP_PREFIX = ".andcode-edit-"
    private const val TEMP_SUFFIX = ".tmp"
}
