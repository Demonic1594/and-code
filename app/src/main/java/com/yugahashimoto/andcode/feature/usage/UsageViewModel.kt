package com.yugahashimoto.andcode.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import com.yugahashimoto.andcode.data.repository.ChatUsage
import com.yugahashimoto.andcode.data.repository.PriceCatalogRepository
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.data.repository.UsageRepository
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsageUiState(
    val range: UsageRange = UsageRange.MONTH,
    val includeArchived: Boolean = true,
    /** Whether turns the runtime did not price get a list-price estimate beside the cost. */
    val estimateAtListPrices: Boolean = true,
    val report: UsageReport? = null,
    val read: Int = 0,
    val total: Int = 0,
    /** When the price list the estimates come from was fetched; shown under the estimate. */
    val priceListFetchedAt: Long? = null,
) {
    val loading: Boolean get() = read < total
}

/**
 * The usage page: reads every chat's turns through [UsageRepository] and sums them for the chosen
 * range. The report is rebuilt as chats arrive, so the numbers climb while the page fills in.
 */
class UsageViewModel(
    private val repository: UsageRepository,
    private val activeChats: () -> List<RuntimeSessionRef>,
    private val archivedChats: suspend () -> List<RuntimeSessionRef>,
    private val priceCatalogs: PriceCatalogRepository? = null,
    private val preferences: AppPreferencesRepository? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = mutableState.asStateFlow()

    private var chats: List<ChatUsage> = emptyList()
    private var loadJob: Job? = null
    private var publishJob: Job? = null
    private var priceListFetchedAt: Long? = null

    private var catalog: PriceCatalog? = null

    init {
        preferences?.state?.value?.estimateAtListPrices?.let { persisted ->
            mutableState.update { it.copy(estimateAtListPrices = persisted) }
        }
        // Seed the estimates at once and follow refreshes; opening the page is also the trigger
        // for a due refresh, off this path and never blocking the report.
        viewModelScope.launch {
            priceCatalogs?.catalog?.collect { fetched ->
                catalog = fetched
                priceListFetchedAt = fetched?.fetchedAt
                publishJob?.cancel()
                publishJob = viewModelScope.launch { publish() }
            }
        }
        // Estimates off also means no price-list fetch: the toggle is the download's off switch.
        if (mutableState.value.estimateAtListPrices) priceCatalogs?.refreshIfDue()
        reload()
    }

    fun setRange(range: UsageRange) {
        if (range == mutableState.value.range) return
        mutableState.update { it.copy(range = range) }
        publishJob?.cancel()
        publishJob = viewModelScope.launch { publish() }
    }

    fun setIncludeArchived(include: Boolean) {
        if (include == mutableState.value.includeArchived) return
        mutableState.update { it.copy(includeArchived = include) }
        reload()
    }

    fun setEstimateAtListPrices(enabled: Boolean) {
        if (enabled == mutableState.value.estimateAtListPrices) return
        mutableState.update { it.copy(estimateAtListPrices = enabled) }
        preferences?.setEstimateAtListPrices(enabled)
        publishJob?.cancel()
        publishJob = viewModelScope.launch { publish() }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val active = activeChats()
                val all =
                    if (mutableState.value.includeArchived) {
                        val ids = active.mapTo(HashSet()) { it.runtimeId to it.session.id }
                        active + archivedChats().filterNot { (it.runtimeId to it.session.id) in ids }
                    } else {
                        active
                    }
                repository.collect(all).conflate().collect { progress ->
                    mutableState.update { it.copy(read = progress.read, total = progress.total) }
                    // Until the first chat is read, the last report stays up rather than dropping to zero.
                    if (progress.read == 0 && progress.total > 0) return@collect
                    chats = progress.chats
                    publishJob?.cancel()
                    publish()
                }
            }
    }

    private suspend fun publish() {
        val state = mutableState.value
        val snapshot = chats
        val catalogForReport = if (state.estimateAtListPrices) catalog else null
        val report = withContext(computeDispatcher) { UsageReport.build(snapshot, state.range, now(), catalog = catalogForReport) }
        mutableState.update { if (it.range == state.range) it.copy(report = report, priceListFetchedAt = priceListFetchedAt) else it }
    }
}
