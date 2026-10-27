package com.yugahashimoto.andcode.feature.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** What a part of the app's storage is, for the breakdown. */
enum class StorageArea {
    PROJECTS,
    ATTACHMENTS,
    AGENTS,
    AGENT_DATA,
    LINUX,
    CACHES,
    LOGS,
    LEFTOVERS,
    TEMP,
    BACKUP,
    VOICE,
    OTHER,
}

/**
 * A place on disk that belongs to one [StorageArea]. A [pattern] ending in `*` matches every entry
 * of its parent folder with that prefix (`update-staging-*`).
 *
 * [cleanable] places can be emptied from the storage screen. They hold only what the app or the
 * agents fetch again, or write for diagnosis, so emptying one loses nothing a user made. Inside the
 * Linux environment only caches known to copy rather than link are cleanable: a tool that hard-links
 * from its cache (uv does) leaves a project's files pointing into it.
 */
data class StoragePlace(
    val area: StorageArea,
    val pattern: String,
    val cleanable: Boolean = false,
    /** Emptied in place rather than removed, for folders the app expects to exist. */
    val keepFolder: Boolean = true,
)

data class AreaUsage(
    val area: StorageArea,
    val bytes: Long,
    /** The folders that make it up, for the screen to open. */
    val folders: List<File>,
)

data class Cleanup(
    val area: StorageArea,
    val folders: List<File>,
    val bytes: Long,
)

/**
 * Where everything the app keeps lives, relative to its files folder, and how the scan's totals
 * divide between those places. Places never nest, except inside the Linux environment, whose own
 * remainder is what is left once the places inside it are taken out; that way nothing is counted
 * twice and the areas add up to the total.
 */
class StorageAreas(
    private val filesDir: File,
    private val cacheDir: File,
) {
    val roots: List<File> get() = listOf(filesDir, cacheDir)

    private val rootfs = File(filesDir, ROOTFS)

    val places: List<StoragePlace> =
        listOf(
            StoragePlace(StorageArea.ATTACHMENTS, "runtime/workspace/.andcode-attachments"),
            StoragePlace(StorageArea.PROJECTS, "runtime/workspace"),
            StoragePlace(StorageArea.AGENTS, "$ROOTFS/root/.local/share/claude"),
            StoragePlace(StorageArea.AGENTS, "$ROOTFS/usr/local/bin/codex*"),
            StoragePlace(StorageArea.AGENTS, "$ROOTFS/usr/local/bin/opencode*"),
            StoragePlace(StorageArea.AGENTS, "$ROOTFS/usr/local/lib/node_modules"),
            StoragePlace(StorageArea.AGENTS, "runtime/environment/antigravity-rootfs"),
            StoragePlace(StorageArea.AGENTS, "runtime/command-suite"),
            StoragePlace(StorageArea.AGENT_DATA, "$ROOTFS/root/.claude"),
            StoragePlace(StorageArea.AGENT_DATA, "$ROOTFS/root/.claude.json"),
            StoragePlace(StorageArea.AGENT_DATA, "$ROOTFS/root/.codex"),
            StoragePlace(StorageArea.AGENT_DATA, "$ROOTFS/root/.gemini"),
            StoragePlace(StorageArea.AGENT_DATA, "$ROOTFS/root/.local/share/opencode"),
            StoragePlace(StorageArea.AGENT_DATA, "runtime/*.json"),
            StoragePlace(StorageArea.CACHES, "runtime/cache", cleanable = true),
            StoragePlace(StorageArea.CACHES, "$ROOTFS/root/.npm/_cacache", cleanable = true),
            StoragePlace(StorageArea.CACHES, "$ROOTFS/root/.npm/_logs", cleanable = true),
            StoragePlace(StorageArea.CACHES, "$ROOTFS/root/.cache/pip", cleanable = true),
            StoragePlace(StorageArea.CACHES, "catalog-cache", cleanable = true),
            StoragePlace(StorageArea.CACHES, CACHE_DIR, cleanable = true),
            StoragePlace(StorageArea.LOGS, "runtime/logs", cleanable = true),
            StoragePlace(StorageArea.LOGS, "diagnostics", cleanable = true),
            StoragePlace(StorageArea.LEFTOVERS, "runtime/environment.staging", cleanable = true, keepFolder = false),
            StoragePlace(StorageArea.LEFTOVERS, "runtime/environment.failed", cleanable = true, keepFolder = false),
            StoragePlace(StorageArea.LEFTOVERS, "runtime/update-staging-*", cleanable = true, keepFolder = false),
            StoragePlace(StorageArea.LEFTOVERS, "runtime/antigravity-extract-*", cleanable = true, keepFolder = false),
            // A clean the app was killed in the middle of. One in the cache folder goes with the cache.
            StoragePlace(StorageArea.LEFTOVERS, "$TRASH*", cleanable = true, keepFolder = false),
            StoragePlace(StorageArea.TEMP, "runtime/proot-tmp"),
            StoragePlace(StorageArea.TEMP, "runtime/tmp"),
            // Kept by an update so a bad one can be rolled back, and read to recover an
            // interrupted one: never offered for deletion.
            StoragePlace(StorageArea.BACKUP, "runtime/environment.rollback"),
            StoragePlace(StorageArea.VOICE, "vosk"),
        )

    /** The files or folders a place currently matches. */
    fun resolve(place: StoragePlace): List<File> {
        val base = if (place.pattern.startsWith(CACHE_DIR)) cacheDir else filesDir
        val relative = place.pattern.removePrefix(CACHE_DIR).removePrefix("/")
        if (relative.isEmpty()) return listOf(base).filter { it.exists() }
        val parent = File(base, relative.substringBeforeLast('/', ""))
        val name = relative.substringAfterLast('/')
        if (!name.contains('*')) return listOf(File(parent, name)).filter { it.exists() }
        val prefix = name.substringBefore('*')
        val suffix = name.substringAfter('*')
        return parent.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(suffix) }.sortedBy { it.name }
    }

    /** Sizes per area from a scan; files are sized directly, as the scan keeps only folders. */
    fun breakdown(scan: ScanResult): List<AreaUsage> {
        val byArea = linkedMapOf<StorageArea, Pair<Long, MutableList<File>>>()
        var inRootfs = 0L
        var counted = 0L

        fun add(
            area: StorageArea,
            file: File,
            bytes: Long,
        ) {
            val (sum, folders) = byArea[area] ?: (0L to mutableListOf())
            folders += file
            byArea[area] = (sum + bytes) to folders
        }

        val claimed = mutableSetOf<String>()
        places.forEach { place ->
            resolve(place).forEach { file ->
                val path = file.absolutePath
                // Attachments sit inside the workspace: the workspace's own size leaves them out below.
                if (path in claimed) return@forEach
                claimed += path
                val bytes = sizeOf(scan, file)
                add(place.area, file, bytes)
                counted += bytes
                if (path.startsWith(rootfs.absolutePath + "/")) inRootfs += bytes
            }
        }
        // The workspace contains the attachments: take them out so they are not counted twice.
        val attachments = byArea[StorageArea.ATTACHMENTS]?.first ?: 0L
        byArea[StorageArea.PROJECTS]?.let { (sum, folders) -> byArea[StorageArea.PROJECTS] = (sum - attachments) to folders }
        counted -= attachments

        val rootfsBytes = sizeOf(scan, rootfs)
        if (rootfsBytes > 0) add(StorageArea.LINUX, rootfs, (rootfsBytes - inRootfs).coerceAtLeast(0))
        counted += (rootfsBytes - inRootfs).coerceAtLeast(0)
        add(StorageArea.OTHER, filesDir, (scan.bytes - counted).coerceAtLeast(0))

        return byArea.map { (area, value) -> AreaUsage(area, value.first, value.second) }
            .filter { it.bytes > 0 }
            .sortedByDescending { it.bytes }
    }

    /** What can be cleaned now, largest first, each with what emptying it would free. */
    fun cleanups(scan: ScanResult): List<Cleanup> =
        places.filter { it.cleanable }
            .groupBy { it.area }
            .map { (area, group) ->
                val folders = group.flatMap(::resolve)
                Cleanup(area, folders, folders.sumOf { sizeOf(scan, it) })
            }.filter { it.bytes > 0 }
            .sortedByDescending { it.bytes }

    /**
     * Empties every folder of [cleanup]; returns the bytes freed. [busy] is asked again before each
     * folder, so an install that starts part way through stops the rest.
     */
    fun clean(
        cleanup: Cleanup,
        busy: () -> Boolean = { false },
    ): Long =
        cleanup.folders.sumOf { folder ->
            val place = places.firstOrNull { it.cleanable && folder in resolve(it) }
            when {
                place == null || busy() || !withoutLinks(folder) -> 0L
                inRootfs(folder) -> if (place.keepFolder) StorageScanner.clear(folder, keepLinkData = true) else 0L
                place.keepFolder -> {
                    val entries =
                        if (Files.isDirectory(
                                folder.toPath(),
                                NOFOLLOW_LINKS,
                            )
                        ) {
                            folder.listFiles().orEmpty().toList()
                        } else {
                            emptyList()
                        }
                    discard(entries, folder)
                }
                else -> discard(listOf(folder), folder)
            }
        }

    /**
     * Moves [targets] into a trash folder, then deletes that. The move is a rename, so it takes
     * only what is there now: a folder an install creates again under the same name a moment later
     * is not touched by the delete that follows.
     */
    private fun discard(
        targets: List<File>,
        near: File,
    ): Long {
        if (targets.isEmpty()) return 0L
        val base =
            if (near.absolutePath.let {
                    it == cacheDir.absolutePath ||
                        it.startsWith(
                            cacheDir.absolutePath + "/",
                        )
                }
            ) {
                cacheDir
            } else {
                filesDir
            }
        val trash = File(base, "$TRASH${System.nanoTime()}")
        if (!trash.mkdirs()) return 0L
        targets.forEachIndexed { index, target ->
            runCatching { Files.move(target.toPath(), trash.toPath().resolve(index.toString()), ATOMIC_MOVE) }
        }
        return StorageScanner.delete(trash)
    }

    /**
     * Deletes [file] from the browser when [deletable] allows it. Inside the Linux environment
     * hard-link data stays, as it does when cleaning.
     */
    fun delete(file: File): Long = if (deletable(file)) StorageScanner.delete(file, keepLinkData = inRootfs(file)) else 0L

    private fun inRootfs(file: File): Boolean = file.absolutePath.startsWith(rootfs.absolutePath + "/")

    /**
     * Whether [file] is inside one of the [roots] with no link on the way down to it, itself
     * included. Through a link, emptying or browsing a folder would reach whatever it points at.
     */
    fun withoutLinks(file: File): Boolean {
        val path = file.absoluteFile.toPath()
        if (path.any { it.toString() == ".." }) return false
        val root = roots.map { it.absoluteFile.toPath() }.firstOrNull { path.startsWith(it) } ?: return false
        var current = path
        while (current != root) {
            if (Files.isSymbolicLink(current)) return false
            current = current.parent ?: return false
        }
        return true
    }

    /**
     * Whether the user may delete [file] from the browser: anything in their projects, or in a
     * place that only holds what can be fetched or written again. The Linux environment, the agents
     * and their data are left alone; removing a piece of them would break them in ways that are
     * hard to see.
     */
    fun deletable(file: File): Boolean {
        // The file may be a link (removing one leaves its target), but nothing above it may be.
        if (!withoutLinks(file.absoluteFile.parentFile ?: return false)) return false
        if (file.path.split('/').contains("..")) return false
        val path = file.absoluteFile.normalize().path
        return places
            .filter { it.cleanable || it.area == StorageArea.PROJECTS || it.area == StorageArea.ATTACHMENTS }
            .any { place ->
                resolve(place).any { match ->
                    val root = match.absoluteFile.normalize().path
                    // A place's own folder goes only when nothing expects it back (an install leftover).
                    path.startsWith("$root/") || (path == root && !place.keepFolder)
                }
            }
    }

    private fun sizeOf(
        scan: ScanResult,
        file: File,
    ): Long =
        when {
            file.isDirectory -> scan.find(file)?.bytes ?: 0L
            // A single file such as a chat store: not kept by the scan, so measured here.
            file.isFile -> file.length()
            else -> 0L
        }

    companion object {
        const val ROOTFS = "runtime/environment/rootfs"

        /** Marks a pattern that is relative to the cache folder rather than the files folder. */
        const val CACHE_DIR = "<cache>"

        /** Where a clean moves what it removes before deleting it. */
        private const val TRASH = ".storage-trash-"
    }
}
