package com.yugahashimoto.andcode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.components.HapticEvent
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.components.StatusDot
import com.yugahashimoto.andcode.ui.components.rememberHaptics
import com.yugahashimoto.andcode.ui.navigation.ROUTE_ARCHIVE
import com.yugahashimoto.andcode.ui.navigation.ROUTE_INBOX
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors
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
                InboxRow(waiting = inboxWaiting, running = inboxRunning, onClick = { onNavigate(ROUTE_INBOX) })
                if (recentSessions.isNotEmpty()) {
                    DrawerSearchField(query = searchQuery, onQueryChange = { searchQuery = it })
                }

                // Only worth a switcher when there is something to switch between. A row of agent
                // chips rather than a list: four full-width rows used to push the chats themselves
                // below the fold, and the agent is a setting you glance at, not a list you read.
                if (agents.size > 1) {
                    DrawerSectionHeader(text = stringResource(R.string.drawer_agents_title), collapsible = false)
                    DrawerAgentSwitcher(agents = agents, selectedRuntimeId = selectedRuntimeId, onSelectAgent = onSelectAgent)
                }

                // Projects as chips for the same reason; the full path is one tap away in Workspaces.
                DrawerSectionHeader(text = stringResource(R.string.drawer_projects_title), collapsible = false)
                DrawerProjectChips(
                    workspaces = workspaces,
                    selectedWorkspacePath = selectedWorkspacePath,
                    onSelectProject = onSelectProject,
                    onAddProject = { onNavigate("workspaces") },
                )

                if (recentSessions.isNotEmpty()) {
                    DrawerSectionHeader(
                        text = stringResource(R.string.drawer_recent_chats),
                        collapsed = collapsedSections.contains("recent"),
                        onToggle = { onToggleSection("recent") },
                    )
                    AnimatedVisibility(visible = !collapsedSections.contains("recent")) {
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
            // The three places you go less often share one row, so the chat list keeps the height.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DrawerDestinationRow(Icons.Default.Inventory2, stringResource(R.string.archive_title)) { onNavigate(ROUTE_ARCHIVE) }
                DrawerDestinationRow(Icons.Default.Schedule, stringResource(R.string.nav_schedules)) { onNavigate("schedules") }
                DrawerDestinationRow(Icons.Default.Settings, stringResource(R.string.nav_settings)) { onNavigate("settings") }
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

@Composable
private fun DrawerSearchMessagesRow(
    query: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ManageSearch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.drawer_search_messages, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DrawerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
        shape = AppShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = Spacing.xs).height(42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.drawer_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.drawer_search_clear),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    allPinned: Boolean,
    onCancel: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.drawer_selected_count, selectedCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onTogglePin) {
            Icon(
                if (allPinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                contentDescription =
                    stringResource(if (allPinned) R.string.drawer_unpin_chats else R.string.drawer_pin_chats),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onArchive) {
            Icon(
                Icons.Default.Archive,
                contentDescription = stringResource(R.string.drawer_archive_session),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete_session),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.andcode_logo),
            contentDescription = stringResource(R.string.cd_app_logo),
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewChatRow(onClick: () -> Unit) {
    // The one thing the drawer exists to start, so it is the one filled, accent-coloured control.
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs).height(44.dp),
        shape = AppShapes.pill,
        contentPadding = PaddingValues(horizontal = Spacing.lg),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text = stringResource(R.string.new_chat), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The way into the inbox, with what is in it at a glance: how many chats wait on the user and how
 * many are running, across every agent - so a run on another agent is not out of sight.
 */
@Composable
private fun InboxRow(
    waiting: Int,
    running: Int,
    onClick: () -> Unit,
) {
    val tc = LocalThemeColors.current
    val label = stringResource(R.string.inbox_title)
    val description = stringResource(R.string.inbox_row_description, waiting, running)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm)
                .clip(AppShapes.md)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = Spacing.md, vertical = 10.dp)
                .testTag("drawer-inbox"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (running > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusDot(SessionStatus.RUNNING, size = 6.dp)
                Text(
                    running.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = CodeFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (waiting > 0) {
            Surface(shape = AppShapes.pill, color = tc.warning, contentColor = MaterialTheme.colorScheme.surface) {
                Text(
                    text = waiting.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerSectionHeader(
    text: String,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
    collapsible: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(start = 18.dp, end = Spacing.lg, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                imageVector = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.cd_expand_collapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun DrawerAgentSwitcher(
    agents: List<DrawerAgent>,
    selectedRuntimeId: String?,
    onSelectAgent: (DrawerAgent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        agents.forEach { agent ->
            val selected = agent.runtimeId == selectedRuntimeId
            val name = stringResource(agent.agent.displayNameRes)
            Surface(
                onClick = { onSelectAgent(agent) },
                // The chosen agent spells out its name; the others stay icons, so four agents fit
                // a phone-width drawer without truncating any of them.
                modifier =
                    Modifier
                        .height(38.dp)
                        .then(if (selected) Modifier.weight(1f) else Modifier.width(44.dp))
                        .semantics { this.selected = selected },
                shape = AppShapes.md,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                ) {
                    Icon(
                        painter = painterResource(agent.agent.iconRes),
                        contentDescription = if (selected) null else name,
                        modifier = Modifier.size(18.dp),
                    )
                    if (selected) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerProjectChips(
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    onSelectProject: (WorkspaceRef) -> Unit,
    onAddProject: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        workspaces.forEach { workspace ->
            val selected = workspace.path == selectedWorkspacePath
            Surface(
                onClick = { onSelectProject(workspace) },
                modifier = Modifier.semantics { this.selected = selected },
                shape = AppShapes.pill,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                border =
                    BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline,
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (selected) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(workspace.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        Surface(
            onClick = onAddProject,
            shape = AppShapes.pill,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.drawer_add_project), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DrawerRecentProjectHeader(
    label: String,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 18.dp, end = Spacing.lg, top = 14.dp, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = stringResource(R.string.cd_folder),
            tint = LocalThemeColors.current.foregroundExtraMuted,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = CodeFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
            contentDescription = stringResource(R.string.cd_expand_collapse),
            tint = LocalThemeColors.current.foregroundExtraMuted,
            modifier = Modifier.size(14.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerChatRow(
    title: String,
    status: SessionStatus = SessionStatus.IDLE,
    hasAttention: Boolean = false,
    isActive: Boolean = false,
    hasUnread: Boolean = false,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    indented: Boolean = false,
    /** Null for a remote runtime; the list now spans every agent, so rows have to say which. */
    agent: LocalAgent? = null,
    relativeTime: String = "",
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelection: () -> Unit = {},
) {
    // Two lines per chat, like the session lists of mobile agent clients: the title, then which
    // agent ran it and when. The open chat gets a soft highlight instead of nothing at all.
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 1.dp),
        shape = AppShapes.md,
        color = if (isActive || isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier =
                Modifier
                    .combinedClickable(
                        onClick = if (selectionMode) onToggleSelection else onClick,
                        onLongClick = if (selectionMode) null else onLongClick,
                    )
                    .padding(start = if (indented) 14.dp else 10.dp, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                StatusDot(status = status, size = 7.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (hasUnread || isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (agent != null) {
                        Icon(
                            painter = painterResource(agent.iconRes),
                            // The name is written right beside it.
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(agent.displayNameRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (relativeTime.isNotBlank()) {
                        if (agent != null) {
                            Text("·", style = MaterialTheme.typography.labelSmall, color = LocalThemeColors.current.foregroundExtraMuted)
                        }
                        Text(
                            text = relativeTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalThemeColors.current.foregroundExtraMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
            // The status dot already turns amber for an approval; the extra marker is for attention
            // the status does not show.
            if (hasAttention && status != SessionStatus.PERMISSION) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(LocalThemeColors.current.statusPermission),
                )
            }
        }
    }
}

@Composable
private fun DrawerDestinationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .clip(AppShapes.md)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
