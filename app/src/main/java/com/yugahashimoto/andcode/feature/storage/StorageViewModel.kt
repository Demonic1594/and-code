package com.yugahashimoto.andcode.feature.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row of the folder browser. */
data class BrowseEntry(
    val file: File,
    val folder: Boolean,
    val bytes: Long,
    val deletable: Boolean,
)

/** What the last action did, for the screen to say once. */
sealed interface StorageNotice {
    data class Freed(
        val bytes: Long,
    ) : StorageNotice

    /** An install, update or agent is at work in these folders, so nothing was removed. */
    data object Busy : StorageNotice
}

data class StorageUiState(
    val scanning: Boolean = true,
    val filesSeen: Int = 0,
    val appBytes: Long = 0,
    val deviceTotal: Long = 0,
    val deviceFree: Long = 0,
    val areas: List<AreaUsage> = emptyList(),
    val cleanups: List<Cleanup> = emptyList(),
    val largest: List<LargeFile> = emptyList(),
    /** The folder open in the browser, or null on the overview. */
    val folder: File? = null,
    val entries: List<BrowseEntry> = emptyList(),
    val working: Boolean = false,
    val notice: StorageNotice? = null,
) {
    val reclaimable: Long get() = cleanups.sumOf { it.bytes }
}

/**
 * The storage screen: measures everything the app keeps, splits it into areas, offers what can be
 * cleaned, and browses folders by size. Cleaning and deleting are refused while [busy] - an install,
 * update or agent at work, all of which write into the very folders that would be emptied - and a
 * clean asks again before each folder.
 */
class StorageViewModel(
    private val areas: StorageAreas,
    private val deviceSpace: () -> Pair<Long, Long>,
    private val busy: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = mutableState.asStateFlow()

    private var scan: ScanResult? = null
    private var scanJob: Job? = null
    private var browseJob: Job? = null

    init {
        rescan()
    }

    fun rescan() {
        scanJob?.cancel()
        mutableState.update { it.copy(scanning = true, filesSeen = 0) }
        scanJob =
            viewModelScope.launch {
                val result =
                    withContext(io) {
                        StorageScanner.scan(
                            areas.roots,
                            isActive = { isActive },
                        ) { seen -> mutableState.update { it.copy(filesSeen = seen) } }
                    }
                val (total, free) = withContext(io) { deviceSpace() }
                val breakdown = withContext(io) { areas.breakdown(result) to areas.cleanups(result) }
                scan = result
                mutableState.update {
                    it.copy(
                        scanning = false,
                        appBytes = result.bytes,
                        deviceTotal = total,
                        deviceFree = free,
                        areas = breakdown.first,
                        cleanups = breakdown.second,
                        largest = result.largest,
                    )
                }
                // Folders may have gone (or grown) since the browser listed them.
                mutableState.value.folder?.let { open(it) }
            }
    }

    fun clean(cleanup: Cleanup) = act { areas.clean(cleanup, busy) }

    fun cleanAll() = act { mutableState.value.cleanups.sumOf { areas.clean(it, busy) } }

    fun delete(file: File) = act { if (busy()) 0L else areas.delete(file) }

    /** Opens [folder] in the browser; a later open or [close] wins over one still listing. */
    fun open(folder: File) {
        browseJob?.cancel()
        browseJob =
            viewModelScope.launch {
                val entries = withContext(io) { if (areas.withoutLinks(folder)) list(folder) else null } ?: return@launch
                mutableState.update { it.copy(folder = folder, entries = entries) }
            }
    }

    /** Up one folder, or back to the overview from the top of a storage root. */
    fun up() {
        val folder = mutableState.value.folder ?: return
        val parent = folder.parentFile
        if (parent == null || areas.roots.none { root -> parent.path.startsWith(root.path) }) {
            close()
        } else {
            open(parent)
        }
    }

    fun close() {
        browseJob?.cancel()
        mutableState.update { it.copy(folder = null, entries = emptyList()) }
    }

    fun consumeNotice() = mutableState.update { it.copy(notice = null) }

    private fun list(folder: File): List<BrowseEntry> {
        val node = scan?.find(folder)
        return folder.listFiles().orEmpty().map { file ->
            val isFolder = file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())
            BrowseEntry(
                file = file,
                folder = isFolder,
                bytes =
                    if (isFolder) {
                        node?.child(file.name)?.bytes ?: 0L
                    } else if (file.isFile) {
                        file.length()
                    } else {
                        0L
                    },
                deletable = areas.deletable(file),
            )
        }.sortedWith(compareByDescending<BrowseEntry> { it.bytes }.thenBy { it.file.name })
    }

    private fun act(work: () -> Long) {
        if (mutableState.value.working) return
        if (busy()) {
            mutableState.update { it.copy(notice = StorageNotice.Busy) }
            return
        }
        mutableState.update { it.copy(working = true) }
        viewModelScope.launch {
            val freed = withContext(io) { runCatching(work).getOrDefault(0L) }
            mutableState.update { it.copy(working = false, notice = StorageNotice.Freed(freed)) }
            rescan()
        }
    }
}
