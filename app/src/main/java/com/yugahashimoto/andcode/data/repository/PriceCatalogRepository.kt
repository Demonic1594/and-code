package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the [PriceCatalog] current: the bundled asset snapshot until the first refresh, the
 * stored copy afterwards, and a network refresh when the stored one is older than a week.
 *
 * The refresh is a plain GET with no identifiers (see PRIVACY.md's third-party services), run
 * off the main thread and never blocking the page that triggered it; a failed refresh keeps the
 * last good copy and its date, and the next attempt waits for the next due check.
 */
class PriceCatalogRepository(
    private val scope: CoroutineScope,
    private val seedJson: () -> String?,
    private val storageDir: File,
    private val fetch: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS,
) {
    private val mutableCatalog = MutableStateFlow<PriceCatalog?>(null)
    val catalog: StateFlow<PriceCatalog?> = mutableCatalog.asStateFlow()

    private val refreshMutex = Mutex()
    private var lastCheckAt = 0L

    @Volatile
    private var loaded = false

    init {
        // Off the caller's thread: this runs from Application.onCreate, and the seed alone is
        // roughly half a megabyte at most to read and decode. Consumers already follow the nullable flow. The
        // mutex keeps a refresh that finishes first from being overwritten by the slower seed.
        scope.launch {
            refreshMutex.withLock {
                withContext(ioDispatcher) { loadStored() }
            }
            loaded = true
        }
    }

    /**
     * Starts a refresh when the stored snapshot is due, off the caller's path. Seed-first: even
     * with no stored copy and no network, the bundled snapshot serves estimates with its date.
     * The due check waits for the initial load, so a fresh stored copy is not re-fetched.
     */
    fun refreshIfDue() {
        val fetchedAt = mutableCatalog.value?.fetchedAt
        if (loaded && fetchedAt != null && (now() - fetchedAt) < refreshIntervalMillis) return
        synchronized(this) {
            if (now() - lastCheckAt < CHECK_THROTTLE_MILLIS) return
            lastCheckAt = now()
        }
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        refreshMutex.withLock {
            // The initial load runs under this mutex too: by the time the lock is held, the
            // stored copy is known — and if it turns out fresh, this refresh was requested
            // before the load finished and there is nothing to do.
            val known = mutableCatalog.value?.fetchedAt
            if (known != null && (now() - known) < refreshIntervalMillis) return
            val text = withContext(ioDispatcher) { runCatching { fetch() }.getOrNull() } ?: return
            val parsed = PriceCatalog.fromModelsDev(text, now()) ?: return
            if (parsed.isEmpty) return
            withContext(ioDispatcher) { store(parsed) }
            mutableCatalog.value = parsed
        }
    }

    private fun loadStored() {
        val stored = File(storageDir, FILE_NAME)
        val parsed =
            runCatching { stored.takeIf { it.isFile }?.readText() }.getOrNull()?.let(PriceCatalog::parseSnapshot)
        mutableCatalog.value = parsed ?: seedJson()?.let(PriceCatalog::parseSnapshot)
    }

    /** Atomic: a new snapshot either replaces the old one whole or leaves it untouched. */
    private fun store(catalog: PriceCatalog) {
        runCatching {
            storageDir.mkdirs()
            val staged = File(storageDir, "$FILE_NAME.tmp")
            staged.writeText(catalogJson.encodeToString(PriceCatalog.serializer(), catalog))
            if (!staged.renameTo(File(storageDir, FILE_NAME))) {
                staged.delete()
            }
        }
    }

    companion object {
        /** The catalog changes slowly; a weekly refresh is far ahead of price changes. */
        const val DEFAULT_REFRESH_INTERVAL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        /** Two catalog opens in one session do not each trigger a network check. */
        const val CHECK_THROTTLE_MILLIS: Long = 60_000L

        const val FILE_NAME = "models-dev-prices.json"

        private val catalogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
