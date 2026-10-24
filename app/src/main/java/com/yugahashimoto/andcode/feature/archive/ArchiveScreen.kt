package com.yugahashimoto.andcode.feature.archive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.repository.ArchivedChat

/** Which chats a "delete forever" confirmation is about. */
private sealed interface DeleteRequest {
    val chats: List<ArchivedChat>

    data class Some(override val chats: List<ArchivedChat>) : DeleteRequest

    data class All(override val chats: List<ArchivedChat>) : DeleteRequest
}

/**
 * Every archived chat, across all agents, in one place: search them, open one to read it, restore
 * it to the drawer, or delete it for good. Long-press starts a multi-select for doing either to
 * several at once. Deleting from here is permanent, so it always asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    relativeTime: (Long) -> String,
    onOpen: (ArchivedChat) -> Unit,
    onRestore: (List<ArchivedChat>) -> Unit,
    onDeleteForever: (List<ArchivedChat>) -> Unit,
    onOpenAutoArchiveSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    val visible = filterArchivedChats(state.chats, query)
    val selected = state.chats.filter { it.session.id in selectedIds }
    val selectionMode = selected.isNotEmpty()

    fun toggle(chat: ArchivedChat) {
        val id = chat.session.id
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            stringResource(R.string.drawer_selected_count, selected.size)
                        } else {
                            stringResource(R.string.archive_title)
                        },
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            enabled = !state.busy,
                            onClick = {
                                onRestore(selected)
                                selectedIds = emptySet()
                            },
                        ) {
                            Icon(Icons.Default.Unarchive, contentDescription = stringResource(R.string.archive_restore))
                        }
                        IconButton(enabled = !state.busy, onClick = { deleteRequest = DeleteRequest.Some(selected) }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.archive_delete_forever))
                        }
                    } else if (state.chats.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.archive_more_actions))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.archive_delete_all)) },
                                    leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                                    enabled = !state.busy,
                                    onClick = {
                                        menuOpen = false
                                        deleteRequest = DeleteRequest.All(state.chats)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && state.chats.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.chats.isEmpty() ->
                ArchiveEmptyState(
                    onOpenAutoArchiveSettings = onOpenAutoArchiveSettings,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else ->
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    item {
                        ArchiveSearchField(query = query, onQueryChange = { query = it })
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.drawer_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                    items(visible, key = { "${it.runtimeId}/${it.session.id}" }) { chat ->
                        ArchivedChatRow(
                            chat = chat,
                            archivedLabel =
                                stringResource(
                                    R.string.archive_archived_ago,
                                    relativeTime(chat.session.time.archived ?: chat.session.time.updated ?: chat.session.time.created),
                                ),
                            selected = chat.session.id in selectedIds,
                            selectionMode = selectionMode,
                            enabled = !state.busy,
                            onClick = { if (selectionMode) toggle(chat) else onOpen(chat) },
                            onLongClick = { toggle(chat) },
                            onRestore = { onRestore(listOf(chat)) },
                            onDelete = { deleteRequest = DeleteRequest.Some(listOf(chat)) },
                        )
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
        }
    }

    deleteRequest?.let { request ->
        val chats = request.chats
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = {
                Text(
                    when {
                        request is DeleteRequest.All -> stringResource(R.string.archive_delete_all_title)
                        chats.size == 1 -> stringResource(R.string.archive_delete_one_title, chats.first().session.title)
                        else -> stringResource(R.string.archive_delete_many_title, chats.size)
                    },
                )
            },
            text = { Text(stringResource(R.string.archive_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteForever(chats)
                    selectedIds = emptySet()
                    deleteRequest = null
                }) {
                    Text(stringResource(R.string.archive_delete_forever), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequest = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ArchiveEmptyState(
    onOpenAutoArchiveSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Inventory2,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.archive_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.archive_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onOpenAutoArchiveSettings) {
            Text(stringResource(R.string.archive_auto_archive_settings))
        }
    }
}

@Composable
private fun ArchiveSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.archive_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.drawer_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivedChatRow(
    chat: ArchivedChat,
    archivedLabel: String,
    selected: Boolean,
    selectionMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = if (selectionMode) null else onLongClick)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.session.title.ifBlank { chat.session.id },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chat.agent?.let { agent ->
                    Icon(
                        painter = painterResource(agent.iconRes),
                        contentDescription = stringResource(agent.displayNameRes),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = listOfNotNull(archivedProjectName(chat), archivedLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!selectionMode) {
            Box {
                IconButton(enabled = enabled, onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.archive_more_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.archive_restore)) },
                        leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.archive_delete_forever), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
