package com.yugahashimoto.andcode.feature.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

private const val DEFAULT_INTERVAL_MILLIS = 1500L
private const val DEBOUNCE_MILLIS = 500L
private const val MAX_FILES = 4000
private const val MAX_DEPTH = 12

/**
 * Reported for the files the page shows when that is not known: the next watch then reloads the page.
 * A real fingerprint is a hash and could in theory equal it; that costs one needless reload.
 */
internal const val UNKNOWN_FINGERPRINT = Long.MIN_VALUE

private val WEB_EXTENSIONS =
    setOf(
        "html",
        "htm",
        "css",
        "js",
        "mjs",
        "cjs",
        "jsx",
        "ts",
        "tsx",
        "vue",
        "svelte",
        "json",
        "svg",
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "avif",
        "ico",
        "woff",
        "woff2",
        "ttf",
        "otf",
        "wasm",
        "map",
        "xml",
        "txt",
    )

private val WATCH_EXCLUDED_DIRS =
    setOf(
        "node_modules",
        ".git",
        "build",
        "dist",
        ".gradle",
        ".idea",
        ".next",
        ".nuxt",
        "target",
        "__pycache__",
        ".venv",
        "venv",
        "coverage",
        "out",
    )

/**
 * Reloads a live preview when the agent writes to web-facing files under [rootDir].
 *
 * Polling rather than a recursive FileObserver: it survives new directories without re-registering
 * and is bounded by the extension filter and [MAX_FILES], so a large repository costs a short walk
 * rather than a watch on every inode. Cancellation (leaving the preview, backgrounding the app)
 * stops the loop through [delay].
 *
 * [shown] is the fingerprint an earlier watch reported through [onShown] for the page on screen:
 * files that changed while nothing watched (the app in the background) reload it as soon as this
 * watch starts. [onShown] receives the fingerprint of the files the page shows from then on.
 */
internal suspend fun watchWorkspaceChanges(
    rootDir: File,
    shown: Long? = null,
    intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    onShown: (Long) -> Unit = {},
    fingerprint: (File) -> Long = ::workspaceFingerprint,
    onChanged: () -> Unit,
) {
    // The first scan reports even if the watch stops meanwhile (the app going to the background
    // straight away). Stopped before it finished, the scan may have read files the agent changed after
    // the page loaded, so it reports [UNKNOWN_FINGERPRINT] and the next watch reloads the page.
    val watch = currentCoroutineContext().job
    var previous =
        withContext(NonCancellable + Dispatchers.IO) {
            fingerprint(rootDir).also { scanned -> onShown(if (watch.isActive) scanned else UNKNOWN_FINGERPRINT) }
        }
    if (shown != null && shown != previous) onChanged()
    while (true) {
        delay(intervalMillis)
        val next = withContext(Dispatchers.IO) { fingerprint(rootDir) }
        if (next == previous) continue
        // Let a burst of writes settle so one agent step causes one reload, not one per file.
        delay(DEBOUNCE_MILLIS)
        previous = withContext(Dispatchers.IO) { fingerprint(rootDir) }
        onShown(previous)
        onChanged()
    }
}

/** Cheap content fingerprint of the web files under [rootDir]; changes when any of them change. */
internal fun workspaceFingerprint(rootDir: File): Long {
    var hash = 1125899906842597L
    var count = 0
    // A linked folder is not walked: the guest could link one back to its parent and make the walk endless.
    val files =
        rootDir
            .walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { directory ->
                directory == rootDir ||
                    directory.name !in WATCH_EXCLUDED_DIRS && !directory.name.startsWith(".") && !Files.isSymbolicLink(directory.toPath())
            }
            .filter { file -> file.isFile && file.extension.lowercase() in WEB_EXTENSIONS }
    for (file in files) {
        if (count++ >= MAX_FILES) break
        hash = hash * 31 + file.relativeTo(rootDir).invariantSeparatorsPath.hashCode()
        hash = hash * 31 + file.length()
        hash = hash * 31 + file.lastModified()
    }
    return hash
}

/**
 * What the live-reload watch knows of the files the page on screen shows, across the watch stopping
 * and starting again (the app going to the background and back): one per page.
 */
internal class ShownPage {
    private val lock = Any()
    private var runs = 0
    private var fingerprint: Long? = null

    /**
     * Starts a watch run. The first takes its baseline from its own first scan. A later one with
     * nothing recorded started before an earlier run's first scan reported, so the page may show
     * older files than its scan will read: it compares against [UNKNOWN_FINGERPRINT] and reloads.
     */
    fun startRun(): WatchRun =
        synchronized(lock) {
            val run = ++runs
            val shown = if (run == 1) null else fingerprint ?: UNKNOWN_FINGERPRINT
            // A run that a newer one replaced reports nothing: its scan may finish after the newer run's.
            // The check and the write are one step, so a report cannot land just after a newer run began.
            WatchRun(shown) { value -> synchronized(lock) { if (runs == run) fingerprint = value } }
        }
}

/** One run of the live-reload watch: what it compares against, and where it reports what the page shows. */
internal class WatchRun(
    val shown: Long?,
    val onShown: (Long) -> Unit,
)
