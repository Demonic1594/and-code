package com.yugahashimoto.andcode.runtime.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** A path in a guest-writable tree that [GuestFiles] would not act on: a link, or not what it should be. */
internal class GuestPathRefusedException(
    path: String,
    reason: String,
) : IOException("$path $reason")

/**
 * Reads and writes files in a directory tree that code in the PRoot guest can also write to: a
 * rootfs, the workspace, the Claude permission bridge. Every call site that touches such a tree
 * goes through here instead of `java.io.File`.
 *
 * Guest code runs as this app's own Linux user, so it can make any name in those trees a symbolic
 * link, and PRoot only changes how the *guest* resolves it. When the app opens the same name the
 * kernel resolves the link against the real filesystem, so `root/.claude.json` pointing at
 * `/data/data/<package>/shared_prefs/x.xml` would have the app overwrite its own preferences with
 * content it chose, or read them back into the guest. That is a confused deputy, and these
 * functions refuse to be one.
 *
 * **How far it checks: every component below the root, and the root itself.** A tree's root is
 * named by the app (for example `environment/rootfs` or `workspace`), and its parent is the app's own
 * directory, out of the guest's reach, so the parent is opened normally. From there each step is a
 * `SecureDirectoryStream` call relative to the folder opened one step before, made with
 * `O_NOFOLLOW`: `openat` for the next folder, `openat` with `O_CREAT | O_EXCL` for a new file,
 * `renameat` to put it in place, `unlinkat` to delete. A link anywhere on the way, including at the
 * root, fails that step, and swapping a folder for a link after it was checked does not help either,
 * because nothing is looked up through a path the guest can change again. A canonical-path check
 * such as `AndCodeAgentContext`'s former `manageablePathOrNull` cannot give that: the guest can swap
 * the folder between the check and the write, and every write the app makes on its behalf (a bridge
 * reply, for instance) is another attempt.
 *
 * Links are refused even when they would stay inside the tree. The host cannot resolve a guest link
 * the way the guest does (an absolute target means the rootfs, `/workspace` is a bind mount), so the
 * only rule that is both safe and predictable is not to follow any. The link at the very end of a
 * path is the exception for writes: [write] replaces it with a regular file by renaming over it, as
 * replacing a guest script always has. A caller that merges into an existing file reads it first,
 * and [readBytes] refuses a link, so a user's own linked config is left alone instead of replaced.
 *
 * The one step still made by path is creating a missing folder: `SecureDirectoryStream` has no
 * `mkdirat`. A swap at that moment can only leave an empty folder of the expected name somewhere
 * else, and the next step, opening it relative to its checked parent, then fails.
 *
 * Android has given every app `SecureDirectoryStream` since API 26, this app's minimum: the platform
 * needs `openat`, `fstatat`, `renameat`, `unlinkat`, `fdopendir` and `futimesat` from bionic, and the
 * last of them arrived in API 26. If a platform ever returns a plain `DirectoryStream`, every call
 * here fails with an [IOException] rather than fall back to following links.
 */
internal object GuestFiles {
    /** A file found by [listFiles]. */
    data class Entry(
        val name: String,
        val lastModifiedMillis: Long,
    )

    /** Larger than any config, request or script the app reads from a guest tree. */
    const val DEFAULT_MAX_READ_BYTES: Long = 4L * 1024 * 1024

    /** `rw-------`: a credential or a file only the owner should read. */
    val OWNER_READ_WRITE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /** `rwx------`: an executable only the owner should read. */
    val OWNER_EXECUTABLE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

    /** `rwxr-xr-x`: a script or binary on the guest's `PATH`. */
    val EXECUTABLE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwxr-xr-x")

    fun write(
        root: File,
        relativePath: String,
        text: String,
        permissions: Set<PosixFilePermission>? = null,
    ) = write(root, relativePath, text.toByteArray(Charsets.UTF_8), permissions)

    fun write(
        root: File,
        relativePath: String,
        bytes: ByteArray,
        permissions: Set<PosixFilePermission>? = null,
    ) = write(root, relativePath, permissions) { it.write(bytes) }

    /**
     * Puts what [content] writes at [relativePath] under [root], creating missing folders.
     *
     * Written to a new temporary file beside the target, flushed to disk, then renamed over it: a
     * reader never sees half a file, a script that is running keeps the copy it opened, and a link
     * at the target is replaced rather than written through. The file gets [permissions] when given,
     * otherwise those of the regular file it replaces, otherwise the platform default for a new
     * file. [content] need not leave the stream it is given open; closing it only flushes.
     *
     * @throws GuestPathRefusedException when the root or a folder on the way is a link or not a
     *   folder, or a folder is in the target's place.
     */
    fun write(
        root: File,
        relativePath: String,
        permissions: Set<PosixFilePermission>? = null,
        content: (OutputStream) -> Unit,
    ) {
        val names = components(relativePath)
        withParent(root, names, create = true) { folder, name -> replace(folder, describe(root, names), name, permissions, content) }
    }

    /**
     * Creates the folder [relativePath] under [root] and any missing above it.
     *
     * @throws GuestPathRefusedException when the root or a folder on the way is a link or not a folder.
     */
    fun createDirectories(
        root: File,
        relativePath: String,
    ) {
        val names = components(relativePath)
        openTree(root, create = true)!!.use { tree -> openFolder(tree, root, names, create = true)!!.close() }
    }

    /**
     * The bytes at [relativePath] under [root], or null when nothing is there (no file, or a folder
     * on the way missing).
     *
     * @throws GuestPathRefusedException when the file, the root or a folder on the way is a link,
     *   when something other than a regular file is there (a folder, or a fifo, whose open would
     *   block), or when the file is larger than [maxBytes]. The type is checked just before the
     *   open, and NIO cannot open with `O_NONBLOCK`, so a guest that swaps in a fifo in between can
     *   still stall the reading thread; it cannot redirect the read.
     */
    fun readBytes(
        root: File,
        relativePath: String,
        maxBytes: Long = DEFAULT_MAX_READ_BYTES,
    ): ByteArray? {
        val names = components(relativePath)
        val path = describe(root, names)
        return withParent(root, names, create = false) { folder, name ->
            val attributes = attributesOrNull(folder, name) ?: return@withParent null
            readRefusal(attributes, maxBytes)?.let { throw GuestPathRefusedException(path, it) }
            folder.newByteChannel(name, READ).use { channel ->
                readAtMost(Channels.newInputStream(channel), maxBytes) ?: throw GuestPathRefusedException(path, "grew past $maxBytes bytes")
            }
        }
    }

    /** [readBytes] as UTF-8 text. */
    fun readText(
        root: File,
        relativePath: String,
        maxBytes: Long = DEFAULT_MAX_READ_BYTES,
    ): String? = readBytes(root, relativePath, maxBytes)?.toString(Charsets.UTF_8)

    /** True when [relativePath] under [root] is a regular file reached without passing a link. */
    fun isRegularFile(
        root: File,
        relativePath: String,
    ): Boolean {
        val names = components(relativePath)
        return try {
            withParent(root, names, create = false) { folder, name -> attributesOrNull(folder, name)?.isRegularFile } == true
        } catch (refused: IOException) {
            false
        }
    }

    /**
     * Deletes the file or link at [relativePath] under [root] - a link itself, never what it points
     * at. False when there was nothing to delete (a folder is not deleted either), or no way to reach
     * it without passing a link: nothing this app wrote can be there.
     */
    fun delete(
        root: File,
        relativePath: String,
    ): Boolean {
        val names = components(relativePath)
        return try {
            withParent(root, names, create = false) { folder, name ->
                val attributes = attributesOrNull(folder, name)
                if (attributes != null && !attributes.isDirectory) folder.deleteFile(name)
                attributes != null && !attributes.isDirectory
            } == true
        } catch (refused: GuestPathRefusedException) {
            false
        }
    }

    /**
     * The regular files directly in [relativeDirectory] under [root] (links, folders and anything
     * else left out), or an empty list when the folder is not there.
     *
     * @throws GuestPathRefusedException when the folder, the root or a folder on the way is a link.
     */
    fun listFiles(
        root: File,
        relativeDirectory: String,
    ): List<Entry> {
        val names = components(relativeDirectory)
        val tree = openTree(root, create = false) ?: return emptyList()
        return tree.use {
            openFolder(tree, root, names, create = false)?.use { folder ->
                folder.childNames().mapNotNull { name ->
                    attributesOrNull(folder, relative(name))
                        ?.takeIf(BasicFileAttributes::isRegularFile)
                        ?.let { Entry(name, it.lastModifiedTime().toMillis()) }
                }
            }.orEmpty()
        }
    }

    /** The names of everything directly in [relativeDirectory] under [root], or an empty list when it is not there. */
    fun listNames(
        root: File,
        relativeDirectory: String,
    ): List<String> {
        val names = components(relativeDirectory)
        val tree = openTree(root, create = false) ?: return emptyList()
        return tree.use { openFolder(tree, root, names, create = false)?.use { it.childNames() }.orEmpty() }
    }

    /** The real folders (not links to one) directly in [relativeDirectory] under [root]. */
    fun listFolders(
        root: File,
        relativeDirectory: String,
    ): List<String> {
        val names = components(relativeDirectory)
        val tree = openTree(root, create = false) ?: return emptyList()
        return tree.use {
            openFolder(tree, root, names, create = false)?.use { folder ->
                folder.childNames().filter { attributesOrNull(folder, relative(it))?.isDirectory == true }
            }.orEmpty()
        }
    }

    /**
     * Deletes [relativePath] under [root] and everything under it, reached and removed without
     * following a link anywhere: the form to use whenever the folders above the target are the
     * guest's too, since [deleteTree] trusts the target's parent.
     *
     * @return true when nothing is left there; false when it could not be reached without passing
     *   a link (nothing is deleted then) or not everything went.
     */
    fun deleteTree(
        root: File,
        relativePath: String,
    ): Boolean {
        val names = components(relativePath)
        return try {
            withParent(root, names, create = false) { folder, name ->
                removeEntry(folder, name, depth = 0)
                attributesOrNull(folder, name) == null
            } ?: true
        } catch (error: IOException) {
            false
        }
    }

    /**
     * Deletes [target] and everything under it without following a link: a link is removed itself,
     * never what it points at. [target]'s parent must be the app's own (use the root-relative form
     * otherwise); [target] may be a link.
     *
     * `File.deleteRecursively` must never be used on a guest tree: it walks into a linked folder,
     * so one link planted by the guest turns "delete the runtime" into "delete what it points at".
     *
     * @return true when [target] no longer exists.
     */
    fun deleteTree(target: File): Boolean {
        val path = target.toPath().toAbsolutePath().normalize()
        val parent = path.parent ?: return false
        return try {
            secureStream(parent).use { folder ->
                removeEntry(folder, path.fileName, depth = 0)
                attributesOrNull(folder, path.fileName) == null
            }
        } catch (error: NoSuchFileException) {
            true
        } catch (error: IOException) {
            false
        }
    }

    /**
     * Copies the tree at [source] into [destination], replacing files of the same name: folders and
     * regular files (with their permission bits) are copied, links are recreated as the same links
     * and never followed, anything else is skipped. An entry that cannot be copied is skipped too,
     * so one unreadable file does not stop the rest.
     *
     * [source] is guest-writable; [destination] must be a tree no guest code is running in yet.
     *
     * @throws GuestPathRefusedException when [source] or [destination] is itself a link.
     */
    fun copyTree(
        source: File,
        destination: File,
    ) {
        val sourceTree = openTree(source, create = false) ?: return
        sourceTree.use {
            openTree(destination, create = true)!!.use { destinationTree ->
                copyChildren(
                    TreeCursor(sourceTree, source.toPath().toAbsolutePath().normalize()),
                    TreeCursor(destinationTree, destination.toPath().toAbsolutePath().normalize()),
                    depth = 0,
                )
            }
        }
    }
}

/** Deeper than any honest tree; stops a guest-made pathological one from exhausting file descriptors. */
private const val MAX_TREE_DEPTH = 256

private val NO_FOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)
private val READ: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
private val CREATE_NEW: Set<OpenOption> = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
private val random = SecureRandom()

/** An open folder in a tree, and its path, which only [GuestFiles.copyTree] uses for links. */
private class TreeCursor(
    val folder: SecureDirectoryStream<Path>,
    val path: Path,
)

/**
 * Runs [block] with the folder holding the last of [names] under [root], opened one step at a time
 * without following a link, and that last name; null when a folder on the way is missing and
 * [create] is false.
 */
private inline fun <T> withParent(
    root: File,
    names: List<String>,
    create: Boolean,
    block: (SecureDirectoryStream<Path>, Path) -> T,
): T? {
    val tree = openTree(root, create) ?: return null
    return tree.use {
        openFolder(tree, root, names.dropLast(1), create)?.use { folder -> block(folder, relative(names.last())) }
    }
}

/** Opens [root] without following a link at it; null when it is missing and [create] is false. */
private fun openTree(
    root: File,
    create: Boolean,
): SecureDirectoryStream<Path>? {
    val path = root.toPath().toAbsolutePath().normalize()
    val parent = path.parent ?: throw GuestPathRefusedException(root.path, "has no parent folder")
    if (create) Files.createDirectories(parent)
    if (!create && !Files.isDirectory(parent)) return null
    return secureStream(parent).use { parentFolder ->
        openChild(parentFolder, path.fileName, path, create, root.path)
    }
}

/** Opens [names] one at a time below [tree]; null when one is missing and [create] is false. */
private fun openFolder(
    tree: SecureDirectoryStream<Path>,
    root: File,
    names: List<String>,
    create: Boolean,
): SecureDirectoryStream<Path>? {
    // A stream of its own for the tree itself, so every step below can close the one before it.
    var current = tree.newDirectoryStream(relative("."), *NO_FOLLOW).asSecure()
    var path = root.toPath().toAbsolutePath().normalize()
    try {
        names.forEachIndexed { index, name ->
            path = path.resolve(name)
            val next = openChild(current, relative(name), path, create, describe(root, names.take(index + 1)))
            current.close()
            current = next ?: return null
        }
        return current
    } catch (error: Throwable) {
        current.close()
        throw error
    }
}

/**
 * Opens the folder [name] inside [parent] with `O_NOFOLLOW`, first creating it at [absolute] when
 * it is missing and [create] is true.
 */
private fun openChild(
    parent: SecureDirectoryStream<Path>,
    name: Path,
    absolute: Path,
    create: Boolean,
    description: String,
): SecureDirectoryStream<Path>? {
    var attributes = attributesOrNull(parent, name)
    if (attributes == null) {
        if (!create) return null
        try {
            // By path: SecureDirectoryStream has no mkdirat (see the GuestFiles comment).
            Files.createDirectory(absolute)
        } catch (raced: FileAlreadyExistsException) {
            // Made meanwhile; it is checked below like any other.
        }
        attributes = attributesOrNull(parent, name) ?: throw NoSuchFileException(description)
    }
    folderRefusal(attributes)?.let { throw GuestPathRefusedException(description, it) }
    return parent.newDirectoryStream(name, *NO_FOLLOW).asSecure()
}

private fun folderRefusal(attributes: BasicFileAttributes): String? =
    when {
        attributes.isSymbolicLink -> "is a link"
        !attributes.isDirectory -> "is not a folder"
        else -> null
    }

private fun readRefusal(
    attributes: BasicFileAttributes,
    maxBytes: Long,
): String? =
    when {
        attributes.isSymbolicLink -> "is a link"
        !attributes.isRegularFile -> "is not a regular file"
        attributes.size() > maxBytes -> "is larger than $maxBytes bytes"
        else -> null
    }

private fun replace(
    folder: SecureDirectoryStream<Path>,
    description: String,
    target: Path,
    permissions: Set<PosixFilePermission>?,
    content: (OutputStream) -> Unit,
) {
    val existing = attributesOrNull(folder, target)
    if (existing?.isDirectory == true) throw GuestPathRefusedException(description, "is a folder")
    val mode =
        permissions ?: existing?.takeIf(BasicFileAttributes::isRegularFile)?.let {
            folder.getFileAttributeView(target, PosixFileAttributeView::class.java, *NO_FOLLOW).readAttributes().permissions()
        }
    // Short and independent of the target's name, which may already be near the 255-byte limit.
    val temporary = relative(".guestfiles-${java.lang.Long.toHexString(random.nextLong())}.tmp")
    try {
        folder.newByteChannel(temporary, CREATE_NEW).use { channel ->
            val output = Channels.newOutputStream(channel).buffered()
            content(KeepOpenOutputStream(output))
            output.flush()
            (channel as? FileChannel)?.force(false)
        }
        mode?.let { folder.getFileAttributeView(temporary, PosixFileAttributeView::class.java, *NO_FOLLOW).setPermissions(it) }
        folder.move(temporary, folder, target)
    } catch (error: Throwable) {
        runCatching { folder.deleteFile(temporary) }
        throw error
    }
}

private fun removeEntry(
    folder: SecureDirectoryStream<Path>,
    name: Path,
    depth: Int,
) {
    val attributes = attributesOrNull(folder, name) ?: return
    if (!attributes.isDirectory) {
        folder.deleteFile(name)
        return
    }
    if (depth < MAX_TREE_DEPTH) {
        // A folder this app cannot open (PRoot's bind-mount points come out of a tarball with no
        // permissions) is usually empty, so removing it is still worth trying below.
        runCatching {
            folder.newDirectoryStream(name, *NO_FOLLOW).asSecure().use { child ->
                child.childNames().forEach { entry -> runCatching { removeEntry(child, relative(entry), depth + 1) } }
            }
        }
    }
    folder.deleteDirectory(name)
}

private fun copyChildren(
    source: TreeCursor,
    destination: TreeCursor,
    depth: Int,
) {
    if (depth >= MAX_TREE_DEPTH) return
    source.folder.childNames().forEach { entry -> runCatching { copyEntry(source, destination, entry, depth) } }
}

private fun copyEntry(
    source: TreeCursor,
    destination: TreeCursor,
    entry: String,
    depth: Int,
) {
    val name = relative(entry)
    val attributes = attributesOrNull(source.folder, name) ?: return
    val existing = attributesOrNull(destination.folder, name)
    val destinationPath = destination.path.resolve(entry)
    when {
        attributes.isDirectory ->
            source.folder.newDirectoryStream(name, *NO_FOLLOW).asSecure().use { sourceChild ->
                val destinationChild = openChild(destination.folder, name, destinationPath, true, destinationPath.toString())!!
                destinationChild.use {
                    copyChildren(TreeCursor(sourceChild, source.path.resolve(entry)), TreeCursor(it, destinationPath), depth + 1)
                }
            }
        attributes.isRegularFile -> {
            val view = source.folder.getFileAttributeView(name, PosixFileAttributeView::class.java, *NO_FOLLOW)
            val mode = view.readAttributes().permissions()
            source.folder.newByteChannel(name, READ).use { channel ->
                replace(destination.folder, destinationPath.toString(), name, mode) { output ->
                    Channels.newInputStream(channel).copyTo(output)
                }
            }
        }
        attributes.isSymbolicLink && existing?.isDirectory != true -> {
            // Only the link's text is read by path; at worst that is another link's text, which the
            // guest could have written itself, and it is recreated as a link, never followed.
            val linkTarget = Files.readSymbolicLink(source.path.resolve(entry))
            if (existing != null) destination.folder.deleteFile(name)
            Files.createSymbolicLink(destinationPath, linkTarget)
        }
    }
}

private fun secureStream(folder: Path): SecureDirectoryStream<Path> = Files.newDirectoryStream(folder).asSecure()

private fun DirectoryStream<Path>.asSecure(): SecureDirectoryStream<Path> {
    if (this is SecureDirectoryStream<Path>) return this
    close()
    throw IOException("This platform cannot open folders without following links")
}

private fun attributesOrNull(
    folder: SecureDirectoryStream<Path>,
    name: Path,
): BasicFileAttributes? =
    try {
        folder.getFileAttributeView(name, BasicFileAttributeView::class.java, *NO_FOLLOW).readAttributes()
    } catch (missing: NoSuchFileException) {
        null
    }

/** [name] as a path relative to a folder, as every `SecureDirectoryStream` call needs. */
private fun relative(name: String): Path = Paths.get(name)

private fun SecureDirectoryStream<Path>.childNames(): List<String> = map { it.fileName.toString() }

/** The names of [relativePath], refusing anything that could step out of the tree or be ambiguous. */
private fun components(relativePath: String): List<String> {
    val names = relativePath.split('/')
    require(relativePath.isNotEmpty() && names.none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
        "Not a plain relative path: \"$relativePath\""
    }
    return names
}

private fun describe(
    root: File,
    names: List<String>,
): String = (listOf(root.path) + names).joinToString("/")

/** Up to [maxBytes] of [input], or null when there is more. */
private fun readAtMost(
    input: InputStream,
    maxBytes: Long,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
}

/** Lets [replace] flush and sync after the content is written, even if the writer closed its stream. */
private class KeepOpenOutputStream(
    output: OutputStream,
) : FilterOutputStream(output) {
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = out.write(bytes, offset, length)

    override fun close() = flush()
}
