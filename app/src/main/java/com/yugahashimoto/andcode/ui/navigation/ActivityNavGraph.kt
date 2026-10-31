package com.yugahashimoto.andcode.ui.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.feature.archive.ArchiveScreen
import com.yugahashimoto.andcode.feature.archive.ArchiveViewModel
import com.yugahashimoto.andcode.feature.inbox.InboxScreen
import com.yugahashimoto.andcode.feature.inbox.InboxUiState
import com.yugahashimoto.andcode.feature.inbox.InboxViewModel
import com.yugahashimoto.andcode.feature.search.MessageSearchScreen
import com.yugahashimoto.andcode.feature.search.MessageSearchViewModel
import com.yugahashimoto.andcode.feature.usage.UsageScreen
import com.yugahashimoto.andcode.feature.usage.UsageViewModel
import com.yugahashimoto.andcode.ui.ViewModelFactory

/**
 * The screens that look across every chat: message search, the Inbox, usage and the archive.
 *
 * Getters throughout, as in [settingsNavGraph]: NavHost remembers these lambdas, so anything passed
 * as a value is frozen at the composition that built the graph and never updates again.
 */
fun NavGraphBuilder.activityNavGraph(
    app: AndCodeApplication,
    navController: NavController,
    inboxViewModel: InboxViewModel,
    inboxState: () -> InboxUiState,
    /** The query the drawer's search field handed over, used once when the search screen opens. */
    pendingSearchQuery: () -> String,
    /** Asks the chat to find [Pair.second] once [Pair.first] is open. */
    onFindRequest: (Pair<String, String>) -> Unit,
    /** Opens a chat, switching to the runtime that owns it first. */
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
    relativeTime: (epochMillis: Long) -> String,
) {
    // Archived chats as the same reference the live list uses, for screens that can include them.
    val archivedChats: suspend () -> List<RuntimeSessionRef> = {
        app.chatLifecycle.listArchived().map { RuntimeSessionRef(it.runtimeId, it.agent, it.session) }
    }

    searchRoute(app, navController, archivedChats, pendingSearchQuery, onFindRequest, openSessionInChat)
    inboxRoute(navController, inboxViewModel, inboxState, openSessionInChat, relativeTime)
    usageRoute(app, navController, archivedChats, openSessionInChat)
    archiveRoute(app, navController, openSessionInChat, relativeTime)
}

private fun NavGraphBuilder.searchRoute(
    app: AndCodeApplication,
    navController: NavController,
    archivedChats: suspend () -> List<RuntimeSessionRef>,
    pendingSearchQuery: () -> String,
    onFindRequest: (Pair<String, String>) -> Unit,
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
) {
    composable(ROUTE_SEARCH) {
        val searchViewModel: MessageSearchViewModel =
            viewModel(
                factory =
                    ViewModelFactory {
                        MessageSearchViewModel(
                            repository = app.messageSearch,
                            activeChats = { app.catalogRepository.allSessions.value },
                            archivedChats = archivedChats,
                            initialQuery = pendingSearchQuery(),
                        )
                    },
            )
        val searchState by searchViewModel.state.collectAsState()
        MessageSearchScreen(
            state = searchState,
            onQueryChange = searchViewModel::setQuery,
            onIncludeArchivedChange = searchViewModel::setIncludeArchived,
            onOpen = { hit ->
                val session = hit.ref.session
                if (searchState.query.isNotBlank()) onFindRequest(session.id to searchState.query.trim())
                openSessionInChat(session.id, session.title, hit.ref.runtimeId)
            },
            onBack = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.inboxRoute(
    navController: NavController,
    inboxViewModel: InboxViewModel,
    inboxState: () -> InboxUiState,
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
    relativeTime: (epochMillis: Long) -> String,
) {
    composable(ROUTE_INBOX) {
        InboxScreen(
            state = inboxState(),
            relativeTime = relativeTime,
            onOpen = { chat -> openSessionInChat(chat.sessionId, chat.title, chat.runtimeId) },
            onRespond = { approval, response, remember ->
                inboxViewModel.respond(approval.request, response, remember)
            },
            onStop = inboxViewModel::stop,
            onReply = inboxViewModel::reply,
            onMarkRead = inboxViewModel::markRead,
            onMarkAllRead = inboxViewModel::markAllRead,
            onConsumeFailure = inboxViewModel::consumeFailure,
            onBack = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.usageRoute(
    app: AndCodeApplication,
    navController: NavController,
    archivedChats: suspend () -> List<RuntimeSessionRef>,
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
) {
    composable(ROUTE_USAGE) {
        val usageViewModel: UsageViewModel =
            viewModel(
                factory =
                    ViewModelFactory {
                        UsageViewModel(
                            repository = app.usage,
                            priceCatalogs = app.priceCatalogRepository,
                            preferences = app.preferences,
                            activeChats = { app.catalogRepository.allSessions.value },
                            archivedChats = archivedChats,
                        )
                    },
            )
        val usageState by usageViewModel.state.collectAsState()
        UsageScreen(
            state = usageState,
            onRangeChange = usageViewModel::setRange,
            onIncludeArchivedChange = usageViewModel::setIncludeArchived,
            onEstimateChange = usageViewModel::setEstimateAtListPrices,
            onRefresh = usageViewModel::reload,
            onOpenChat = { row -> openSessionInChat(row.ref.session.id, row.ref.session.title, row.ref.runtimeId) },
            onBack = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.archiveRoute(
    app: AndCodeApplication,
    navController: NavController,
    openSessionInChat: (sessionId: String, title: String, runtimeId: String?) -> Unit,
    relativeTime: (epochMillis: Long) -> String,
) {
    composable(ROUTE_ARCHIVE) {
        val archiveViewModel: ArchiveViewModel =
            viewModel(factory = ViewModelFactory { ArchiveViewModel(app.chatLifecycle) })
        val archiveState by archiveViewModel.state.collectAsState()
        ArchiveScreen(
            state = archiveState,
            relativeTime = relativeTime,
            onOpen = { chat -> openSessionInChat(chat.session.id, chat.session.title, chat.runtimeId) },
            onRestore = archiveViewModel::restore,
            onDeleteForever = archiveViewModel::deleteForever,
            onOpenAutoArchiveSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
            onBack = { navController.popBackStack() },
        )
    }
}
