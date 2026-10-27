package com.yugahashimoto.andcode.feature.storage

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue

/** A folder and everything below it, measured. Files are not kept, only their totals. */
class DirNode(
    val file: File,
) {
    var bytes: Long = 0
        internal set
    var files: Int = 0
        internal set
    val children: MutableList<DirNode> = mutableListOf()

    fun child(name: String): DirNode? = children.firstOrNull { it.file.name == name }

    /** The node for [target], when it is this folder or below it. */
    fun find(target: File): DirNode? {
        val path = target.absoluteFile.normalize().path
        val own = file.absoluteFile.normalize().path
        if (path == own) return this
        if (!path.startsWith("$own/")) return null
        var node: DirNode = this
        path.removePrefix("$own/").split('/').forEach { part -> node = node.child(part) ?: return null }
        return node
    }
}

data class LargeFile(
    val file: File,
    val bytes: Long,
)

data class ScanResult(
    val roots: List<DirNode>,
    val largest: List<LargeFile>,
) {
    val bytes: Long get() = roots.sumOf { it.bytes }

    fun find(target: File): DirNode? = roots.firstNotNullOfOrNull { it.find(target) }
}

/**
 * Measures folders the way `du` does, without following symbolic links: the Linux environment is
 * full of them, some pointing at `/` or back up the tree, and following one would count (or
 * later delete) things that are not the app's. Folders the app cannot open - PRoot leaves a few in
 * the rootfs with no permissions - are counted as empty rather than stopping the scan.
 */
object StorageScanner {
    const val LARGEST_FILES = 50
    private const val PROGRESS_EVERY = 2_000

    fun scan(
        roots: List<File>,
        largestFiles: Int = LARGEST_FILES,
        isActive: () -> Boolean = { true },
        onProgress: (filesSeen: Int) -> Unit = {},
    ): ScanResult {
        val largest = PriorityQueue<LargeFile>(compareBy { it.bytes })
        var seen = 0
        val nodes =
            roots.filter { it.isDirectory }.map { root ->
                val stack = ArrayDeque<DirNode>()
                var top: DirNode? = null
                Files.walkFileTree(
                    root.toPath(),
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            if (!isActive()) return FileVisitResult.TERMINATE
                            val node = DirNode(dir.toFile())
                            stack.lastOrNull()?.children?.add(node)
                            if (top == null) top = node
                            stack.addLast(node)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            // A link takes no room of its own worth counting, and what it points at is
                            // counted where it lives, if that is in here at all.
                            val bytes = if (attrs.isRegularFile) attrs.size() else 0L
                            stack.lastOrNull()?.let {
                                it.bytes += bytes
                                it.files++
                            }
                            if (bytes > 0) {
                                largest.add(LargeFile(file.toFile(), bytes))
                                if (largest.size > largestFiles) largest.poll()
                            }
                            if (++seen % PROGRESS_EVERY == 0) {
                                if (!isActive()) return FileVisitResult.TERMINATE
                                onProgress(seen)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: IOException,
                        ): FileVisitResult = FileVisitResult.CONTINUE

                        override fun postVisitDirectory(
                            dir: Path,
                            exc: IOException?,
                        ): FileVisitResult {
                            val done = stack.removeLast()
                            done.children.sortByDescending { it.bytes }
                            stack.lastOrNull()?.let {
                                it.bytes += done.bytes
                                it.files += done.files
                            }
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                top ?: DirNode(root)
            }
        onProgress(seen)
        return ScanResult(nodes, largest.sortedByDescending { it.bytes })
    }

    /**
     * Whether [path] holds the data of a hard link made inside the Linux environment. PRoot's
     * `--link2symlink` turns every hard link into links to one `.l2s.*` file, named by its absolute
     * path, which may sit in a cache while the links to it sit in a project; removing it would break
     * those files.
     */
    fun isLinkData(path: Path): Boolean = path.fileName?.toString()?.startsWith(LINK_DATA_PREFIX) == true

    /**
     * Deletes [target] and everything below it without following links: a link is removed, never
     * what it points at. Returns the bytes freed. Whatever cannot be deleted is left and skipped, as
     * is hard-link data when [keepLinkData] is set (with the folders that hold it).
     */
    fun delete(
        target: File,
        keepLinkData: Boolean = false,
    ): Long {
        val path = target.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return 0
        if (keepLinkData && isLinkData(path)) return 0
        var freed = 0L
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            val bytes = if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.size(path) else 0L
            return if (runCatching { Files.delete(path) }.isSuccess) bytes else 0L
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (keepLinkData && isLinkData(file)) return FileVisitResult.CONTINUE
                    val bytes = if (attrs.isRegularFile) attrs.size() else 0L
                    if (runCatching { Files.delete(file) }.isSuccess) freed += bytes
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // A folder that cannot be opened may still be removable once empty.
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return freed
    }

    /**
     * Empties [directory] but keeps it, for caches the app expects to find in place. A link standing
     * where the folder should be is left alone: listing through it would empty its target.
     */
    fun clear(
        directory: File,
        keepLinkData: Boolean = false,
    ): Long {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return 0
        return directory.listFiles().orEmpty().sumOf { delete(it, keepLinkData) }
    }

    private const val LINK_DATA_PREFIX = ".l2s."
}
