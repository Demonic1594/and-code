package com.yugahashimoto.andcode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.components.HapticEvent
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.components.rememberHaptics
import com.yugahashimoto.andcode.ui.navigation.ROUTE_ARCHIVE
import com.yugahashimoto.andcode.ui.navigation.ROUTE_INBOX
import com.yugahashimoto.andcode.ui.navigation.ROUTE_USAGE
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import com.yugahashimoto.andcode.ui.theme.Spacing

data class DrawerRecentSession(
    val id: String,
    val title: String,
    val relativeTime: String,
    val directory: String? = null,
    val isActive: Boolean = false,
    val hasUnread: Boolean = false,
    val status: SessionStatus = SessionStatus.IDLE,
    val hasAttention: Boolean = false,
    /** The runtime that owns this chat - opening it has to switch to that runtime first. */
    val runtimeId: String? = null,
    /** Null for a remote runtime, which is not one of the three local agents. */
    val agent: LocalAgent? = null,
)

/** An agent offered by the drawer's switcher. */
data class DrawerAgent(
    val runtimeId: String,
    val agent: LocalAgent,
)

private fun DrawerRecentSession.projectKey(): String =
    directory?.trimEnd('/')?.takeIf { it.isNotBlank() && it != "/root" && it != "/workspace" }.orEmpty()

private fun DrawerRecentSession.projectLabel(defaultLabel: String): String =
    projectKey().substringAfterLast('/').takeIf { it.isNotBlank() } ?: defaultLabel

/**
 * Chats whose title or project folder contains every word of [query], case-insensitively, in
 * their original (most recent first) order. A blank query matches nothing: the caller shows the
 * normal grouped list instead of a search result then.
 */
internal fun filterDrawerSessions(
    sessions: List<DrawerRecentSession>,
    query: String,
): List<DrawerRecentSession> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    return sessions.filter { session ->
        val haystack = "${session.title} ${session.directory.orEmpty()}"
        terms.all { haystack.contains(it, ignoreCase = true) }
    }
}

@Composable
fun AppDrawerContent(
    recentSessions: List<DrawerRecentSession>,
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    onNewChat: () -> Unit,
    onSelectProject: (WorkspaceRef) -> Unit,
    /** Session id, title, and the runtime that owns it. */
    onOpenSession: (String, String, String?) -> Unit,
    onNavigate: (String) -> Unit,
    /** Chats waiting on an answer or finished unread, and chats running, as the inbox counts them. */
    inboxWaiting: Int = 0,
    inboxRunning: Int = 0,
    /** Installed agents, offered as a switcher only when there is more than one. */
    agents: List<DrawerAgent> = emptyList(),
    selectedRuntimeId: String? = null,
    onSelectAgent: (DrawerAgent) -> Unit = {},
    onBatchDelete: (Set<String>) -> Unit = {},
    onBatchArchive: (Set<String>) -> Unit = {},
    /** Opens the search across every chat's messages with the drawer's query. */
    onSearchMessages: (String) -> Unit = {},
    collapsedSections: Set<String> = emptySet(),
    onToggleSection: (String) -> Unit = {},
    pinnedSessionIds: Set<String> = emptySet(),
    /** Pins every given chat when `true`, unpins them when `false`. */
    onSetPinned: (Set<String>, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var projectSearchOpen by rememberSaveable { mutableStateOf(false) }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    val selectionMode = selectedSessionIds.isNotEmpty()
    val haptics = rememberHaptics()

    fun toggleSelection(id: String) {
        selectedSessionIds =
            if (id in selectedSessionIds) {
                selectedSessionIds - id
            } else {
                selectedSessionIds + id
            }
    }

    fun enterSelectionMode(id: String) {
        haptics.perform(HapticEvent.LONG_PRESS)
        selectedSessionIds = setOf(id)
    }

    @Composable
    fun ChatRow(
        session: DrawerRecentSession,
        indented: Boolean,
    ) {
        DrawerChatRow(
            title = session.title.ifBlank { session.id },
            status = session.status,
            hasAttention = session.hasAttention,
            isActive = session.isActive,
            hasUnread = session.hasUnread,
            isSelected = session.id in selectedSessionIds,
            selectionMode = selectionMode,
            indented = indented,
            agent = session.agent,
            relativeTime = session.relativeTime,
            onClick = { onOpenSession(session.id, session.title, session.runtimeId) },
            onLongClick = { enterSelectionMode(session.id) },
            onToggleSelection = { toggleSelection(session.id) },
        )
    }

    @Composable
    fun PinnedAndGroupedChats(pinned: List<DrawerRecentSession>) {
        val defaultLabel = stringResource(R.string.drawer_project_default)
        if (pinned.isNotEmpty()) {
            DrawerRecentProjectHeader(
                label = stringResource(R.string.drawer_pinned_chats),
                collapsed = collapsedSections.contains("pinned"),
                onToggle = { onToggleSection("pinned") },
            )
            AnimatedVisibility(visible = !collapsedSections.contains("pinned")) {
                Column {
                    pinned.forEach { session -> ChatRow(session, indented = true) }
                }
            }
        }
        // Pinned chats are listed once, above, rather than again in their project.
        // Grouped by project, always. The project/status switch that used to sit
        // here made the drawer ask a question before it answered one, and the
        // status a chat is in already shows on its own row.
        val grouped =
            recentSessions
                .filterNot { it.id in pinnedSessionIds }
                .groupBy { it.projectKey() }
                .toList()
                // recentSessions is newest first, so the project whose newest chat comes earliest
                // in it is the one worked on last; it leads. (Sorting descending used to put the
                // project you touched longest ago at the top.)
                .sortedBy { (_, sessions) ->
                    sessions.firstOrNull()?.let { recentSessions.indexOf(it) } ?: Int.MAX_VALUE
                }
        grouped.forEach { (key, sessions) ->
            val sectionKey = "project_$key"
            val sorted = sessions.sortedByDescending { it.hasAttention }
            DrawerRecentProjectHeader(
                label = sessions.first().projectLabel(defaultLabel),
                collapsed = collapsedSections.contains(sectionKey),
                onToggle = { onToggleSection(sectionKey) },
            )
            AnimatedVisibility(visible = !collapsedSections.contains(sectionKey)) {
                Column {
                    sorted.forEach { session -> ChatRow(session, indented = true) }
                }
            }
        }
    }

    Surface(
        modifier =
            modifier
                .fillMaxHeight()
                .width(288.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
            ) {
                DrawerHeader()
                NewChatRow(onClick = onNewChat)

                // Only worth a switcher when there is something to switch between. A row of agent
                // chips rather than a list: four full-width rows used to push the chats themselves
                // below the fold, and the agent is a setting you glance at, not a list you read.
                if (agents.size > 1) {
                    DrawerSectionHeader(text = stringResource(R.string.drawer_agents_title), collapsible = false)
                    DrawerAgentSwitcher(agents = agents, selectedRuntimeId = selectedRuntimeId, onSelectAgent = onSelectAgent)
                }
                // Under the agents: actions, then what you work with, then where things wait for you.
                InboxRow(waiting = inboxWaiting, running = inboxRunning, onClick = { onNavigate(ROUTE_INBOX) })

                // Projects as chips for the same reason; the full path is one tap away in Workspaces.
                // Add and search sit on the header, so neither scrolls off with the chips.
                DrawerSectionHeader(text = stringResource(R.string.drawer_projects_title), collapsible = false) {
                    // Capped, so a long translation at a large font size cannot push the search icon out.
                    TextButton(
                        onClick = { onNavigate("workspaces") },
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                        modifier = Modifier.height(32.dp).widthIn(max = 150.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            stringResource(R.string.drawer_add_project),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (workspaces.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                projectSearchOpen = !projectSearchOpen
                                if (!projectSearchOpen) projectQuery = ""
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.cd_search_projects),
                                tint =
                                    if (projectSearchOpen) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (projectSearchOpen && workspaces.isNotEmpty()) {
                    DrawerSearchField(
                        query = projectQuery,
                        onQueryChange = { projectQuery = it },
                        hint = stringResource(R.string.drawer_project_search_hint),
                    )
                }
                DrawerProjectChips(
                    workspaces = filterDrawerProjects(workspaces, projectQuery),
                    selectedWorkspacePath = selectedWorkspacePath,
                    onSelectProject = onSelectProject,
                )

                if (recentSessions.isNotEmpty()) {
                    // Next to the list it filters. A query shows the results even in a collapsed
                    // section, without overwriting the collapse the user chose.
                    DrawerSearchField(query = searchQuery, onQueryChange = { searchQuery = it })
                    val recentCollapsed = isRecentSectionCollapsed(collapsedSections, searchQuery)
                    DrawerSectionHeader(
                        text = stringResource(R.string.drawer_recent_chats),
                        collapsed = recentCollapsed,
                        // While a query holds the section open, a tap would flip the saved choice
                        // with nothing to show for it until the query is cleared.
                        onToggle = { if (searchQuery.isBlank()) onToggleSection("recent") },
                    )
                    AnimatedVisibility(visible = !recentCollapsed) {
                        Column {
                            if (selectionMode) {
                                val allPinned = selectedSessionIds.all { it in pinnedSessionIds }
                                SelectionActionBar(
                                    selectedCount = selectedSessionIds.size,
                                    allPinned = allPinned,
                                    onCancel = { selectedSessionIds = emptySet() },
                                    onTogglePin = {
                                        haptics.perform(HapticEvent.TICK)
                                        onSetPinned(selectedSessionIds, !allPinned)
                                        selectedSessionIds = emptySet()
                                    },
                                    onArchive = {
                                        onBatchArchive(selectedSessionIds)
                                        selectedSessionIds = emptySet()
                                    },
                                    onDelete = {
                                        // One chat goes straight away, with Undo on the snackbar; a
                                        // batch is worth a question first.
                                        if (selectedSessionIds.size == 1) {
                                            onBatchDelete(selectedSessionIds)
                                            selectedSessionIds = emptySet()
                                        } else {
                                            showDeleteConfirm = true
                                        }
                                    },
                                )
                            }
                            val pinned = recentSessions.filter { it.id in pinnedSessionIds }
                            if (searchQuery.isNotBlank()) {
                                // Titles are matched here; the full message text is one tap away.
                                DrawerSearchMessagesRow(query = searchQuery.trim(), onClick = { onSearchMessages(searchQuery.trim()) })
                                val matches = filterDrawerSessions(recentSessions, searchQuery)
                                if (matches.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.drawer_search_no_results),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                    )
                                }
                                matches.forEach { session -> ChatRow(session, indented = false) }
                            }
                            if (searchQuery.isBlank()) {
                                PinnedAndGroupedChats(pinned)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f))
            // The places you go less often share one row, so the chat list keeps the height.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // An equal share each, so a long label in one language cannot squeeze out the last.
                val item = Modifier.weight(1f)
                DrawerDestinationRow(Icons.Default.Inventory2, stringResource(R.string.archive_title), item) { onNavigate(ROUTE_ARCHIVE) }
                DrawerDestinationRow(Icons.Default.Schedule, stringResource(R.string.nav_schedules), item) { onNavigate("schedules") }
                DrawerDestinationRow(Icons.Outlined.Insights, stringResource(R.string.usage_title), item) { onNavigate(ROUTE_USAGE) }
                DrawerDestinationRow(Icons.Default.Settings, stringResource(R.string.nav_settings), item) { onNavigate("settings") }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = {
                Text(
                    stringResource(R.string.drawer_delete_selected_body, selectedSessionIds.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onBatchDelete(selectedSessionIds)
                    selectedSessionIds = emptySet()
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppDrawerContentPreview() {
    AndCodeTheme {
        AppDrawerContent(
            recentSessions =
                listOf(
                    DrawerRecentSession("1", "Investigate auth bug", "3 hours ago", "/workspace/android-code"),
                    DrawerRecentSession("2", "Update README", "Yesterday", "/workspace/android-code"),
                    DrawerRecentSession("3", "Fix failing tests", "2 days ago", "/workspace/api-server"),
                    DrawerRecentSession("4", "Clean up API responses", "4 days ago", "/workspace/api-server"),
                    DrawerRecentSession("5", "Update dependencies", "1 week ago", null),
                ),
            workspaces =
                listOf(
                    WorkspaceRef("/workspace/android-code", "android-code", "/workspace/android-code"),
                    WorkspaceRef("/workspace/api-server", "api-server", "/workspace/api-server"),
                ),
            selectedWorkspacePath = "/workspace/android-code",
            onNewChat = {},
            onSelectProject = {},
            onOpenSession = { _, _, _ -> },
            onNavigate = {},
        )
    }
}

/** Projects whose name contains [query], ignoring case; all of them while [query] is blank. */
internal fun filterDrawerProjects(
    workspaces: List<WorkspaceRef>,
    query: String,
): List<WorkspaceRef> {
    val needle = query.trim()
    if (needle.isEmpty()) return workspaces
    return workspaces.filter { it.name.contains(needle, ignoreCase = true) }
}

/** Whether Recent chats is shown collapsed: as the user left it, except that a query always opens it. */
internal fun isRecentSectionCollapsed(
    collapsedSections: Set<String>,
    query: String,
): Boolean = "recent" in collapsedSections && query.isBlank()
