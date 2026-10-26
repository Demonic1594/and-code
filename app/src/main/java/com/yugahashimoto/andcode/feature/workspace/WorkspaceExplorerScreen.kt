package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.ui.components.FileTypeIcon
import com.yugahashimoto.andcode.ui.components.HapticEvent
import com.yugahashimoto.andcode.ui.components.RetainedPanel
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.components.StatusChip
import com.yugahashimoto.andcode.ui.components.rememberHaptics
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Radius
import com.yugahashimoto.andcode.ui.theme.Spacing

data class CommitInfo(
    val hash: String,
    val message: String,
    val author: String,
    val relativeTime: String,
)

enum class DiffViewMode { UNIFIED, SPLIT }

/** Whether the changed file can be opened in the viewer. Deleted files no longer exist on disk. */
internal fun OpenCodeFileChange.isOpenable(): Boolean = displayPath.isNotBlank() && !status.equals("deleted", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceExplorerScreen(
    state: WorkspaceExplorerUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenNode: (OpenCodeFileNode) -> Unit,
    onNavigateUp: () -> Unit,
    onSearch: (String) -> Unit,
    onRefreshChanges: () -> Unit,
    branches: List<String> = emptyList(),
    onSwitchBranch: (String) -> Unit = {},
    onCreateBranch: (String) -> Unit = {},
    commits: List<CommitInfo> = emptyList(),
    prTitle: String? = null,
    prStatus: String? = null,
    prDescription: String? = null,
    tabManager: WorkspaceTabManager? = null,
    workspaceDeck: List<String> = emptyList(),
    onOpenTerminal: () -> Unit = {},
    onOpenSourceControl: () -> Unit = {},
    onOpenChange: (OpenCodeFileChange) -> Unit = {},
    gitActions: GitActions = GitActions(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // The view model's live branch list and history win over the static ones a caller may pass.
    val shownBranches = state.branches.ifEmpty { branches }
    val shownCommits = state.commits.ifEmpty { commits }
    val tabs =
        listOf(
            stringResource(R.string.tab_files),
            stringResource(R.string.tab_search),
            stringResource(R.string.tab_changes),
            stringResource(R.string.tab_pr),
        )

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        if (workspaceDeck.size > 1) {
            WorkspaceDeckRow(workspaceDeck, state.workspace.name)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.workspace.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.workspace.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.canRunGit) {
                IconButton(onClick = onOpenSourceControl) {
                    Icon(Icons.Outlined.AccountTree, contentDescription = stringResource(R.string.git_title))
                }
            }
            IconButton(onClick = onOpenTerminal) {
                Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.tab_type_terminal))
            }
            IconButton(
                onClick = if (selectedTab == 2) onRefreshChanges else onRefresh,
                enabled = !state.isLoadingFiles && !state.isLoadingChanges,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        if (tabManager != null) {
            WorkspaceTabRow(tabManager)
        }

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        if (tabManager != null) {
            WorkspaceTabContent(tabManager = tabManager) {
                when (selectedTab) {
                    0 -> FilesTab(state, onOpenNode, onNavigateUp)
                    1 -> SearchTab(state, onSearch)
                    2 -> ChangesTab(state, shownBranches, onSwitchBranch, onCreateBranch, shownCommits, onOpenChange, gitActions)
                    else -> PrTab(prTitle, prStatus, prDescription)
                }
            }
        } else {
            when (selectedTab) {
                0 -> FilesTab(state, onOpenNode, onNavigateUp)
                1 -> SearchTab(state, onSearch)
                2 -> ChangesTab(state, shownBranches, onSwitchBranch, onCreateBranch, shownCommits, onOpenChange, gitActions)
                else -> PrTab(prTitle, prStatus, prDescription)
            }
        }
    }
}

@Composable
private fun WorkspaceDeckRow(
    workspaces: List<String>,
    activeName: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        workspaces.forEach { name ->
            FilterChip(
                selected = name == activeName,
                onClick = {},
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceTabRow(tabManager: WorkspaceTabManager) {
    val workspaceTabs by tabManager.tabs.collectAsState()
    val selectedTabId by tabManager.selectedTabId.collectAsState()
    var tabDropdownId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    if (workspaceTabs.isEmpty()) return

    val selectedIndex = workspaceTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)

    Box {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.weight(1f),
                edgePadding = 8.dp,
            ) {
                workspaceTabs.forEach { tab ->
                    Tab(
                        selected = tab.id == selectedTabId,
                        onClick = { tabManager.selectTab(tab.id) },
                        text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier =
                            Modifier.combinedClickable(
                                onClick = { tabManager.selectTab(tab.id) },
                                onLongClick = { tabDropdownId = tab.id },
                            ),
                    )
                }
            }
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_tab))
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tab_type_agent)) },
                        onClick = {
                            tabManager.addTab(
                                WorkspaceTab(
                                    id = java.util.UUID.randomUUID().toString(),
                                    type = TabType.AGENT,
                                    title = "New",
                                ),
                            )
                            showAddMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tab_type_terminal)) },
                        onClick = {
                            tabManager.addTab(
                                WorkspaceTab(
                                    id = java.util.UUID.randomUUID().toString(),
                                    type = TabType.TERMINAL,
                                    title = "Terminal",
                                ),
                            )
                            showAddMenu = false
                        },
                    )
                }
            }
        }
        DropdownMenu(expanded = tabDropdownId != null, onDismissRequest = { tabDropdownId = null }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_close)) },
                onClick = {
                    tabDropdownId?.let { tabManager.removeTab(it) }
                    tabDropdownId = null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_close_others)) },
                onClick = {
                    tabDropdownId?.let { tabManager.closeOthers(it) }
                    tabDropdownId = null
                },
            )
        }
    }
}

@Composable
private fun WorkspaceTabContent(
    tabManager: WorkspaceTabManager,
    content: @Composable () -> Unit,
) {
    val workspaceTabs by tabManager.tabs.collectAsState()
    val selectedTabId by tabManager.selectedTabId.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        workspaceTabs.forEach { tab ->
            RetainedPanel(visible = selectedTabId == tab.id) {
                when {
                    tab.type == TabType.TERMINAL -> TerminalTabPlaceholder()
                    selectedTabId == tab.id -> content()
                    else -> Text(stringResource(R.string.tab_content_placeholder), modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TerminalTabPlaceholder() {
    var input by remember { mutableStateOf("") }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .padding(12.dp),
    ) {
        Column {
            Text(
                "$ ",
                fontFamily = CodeFontFamily,
                color = Color(0xFF6FCF97),
            )
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White,
                    ),
            )
        }
    }
}

@Composable
private fun FilesTab(
    state: WorkspaceExplorerUiState,
    onOpenNode: (OpenCodeFileNode) -> Unit,
    onNavigateUp: () -> Unit,
) {
    // The view model lists folders first, then files, each alphabetically.
    val sorted = state.files
    val atRoot = state.currentPath == WorkspaceFolders.GUEST_ROOT
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.screenEdge,
                end = Spacing.screenEdge,
                top = Spacing.md,
            ),
    ) {
        item(key = "path") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                FilledTonalIconButton(onClick = onNavigateUp, enabled = !atRoot) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.parent_folder))
                }
                Text(
                    text = state.currentPath,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = CodeFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            state.isLoadingFiles -> item(key = "loading") { LoadingCard(stringResource(R.string.loading_file_list)) }
            sorted.isEmpty() ->
                item(key = "empty") {
                    Text(
                        stringResource(R.string.folder_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl),
                        textAlign = TextAlign.Center,
                    )
                }
            else ->
                itemsIndexed(sorted, key = { _, node -> node.absolute }) { index, node ->
                    FileRow(
                        node = node,
                        first = index == 0,
                        last = index == sorted.lastIndex,
                        onClick = { onOpenNode(node) },
                        modifier = Modifier.animateItem(),
                    )
                }
        }
        state.error?.let { item { ErrorCard(it) } }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

/**
 * One entry of a folder, drawn as a row of one grouped card rather than a card of its own: a
 * folder of thirty files used to be thirty boxes, each repeating its own name as its path.
 */
@Composable
private fun FileRow(
    node: OpenCodeFileNode,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDirectory = node.type == "directory"
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val shape =
        RoundedCornerShape(
            topStart = if (first) Radius.lg else 0.dp,
            topEnd = if (first) Radius.lg else 0.dp,
            bottomStart = if (last) Radius.lg else 0.dp,
            bottomEnd = if (last) Radius.lg else 0.dp,
        )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            FileTypeIcon(fileName = node.name, isDirectory = isDirectory, modifier = Modifier.size(20.dp))
            Text(
                node.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isDirectory) FontWeight.Medium else FontWeight.Normal,
                // Ignored files are there but out of the way, as in an IDE's tree.
                color = if (node.ignored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.ignored) StatusChip(stringResource(R.string.ignored_label), active = false)
            if (isDirectory) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchTab(
    state: WorkspaceExplorerUiState,
    onSearch: (String) -> Unit,
) {
    var query by rememberSaveable(state.workspace.id) { mutableStateOf(state.searchQuery) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_files_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onSearch(query) },
                enabled = query.isNotBlank() && !state.isSearching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.tab_search))
            }
        }

        if (state.isSearching) {
            item { LoadingCard(stringResource(R.string.searching)) }
        }

        if (!state.isSearching && state.searchQuery.isNotBlank() &&
            state.textMatches.isEmpty() && state.fileMatches.isEmpty()
        ) {
            item {
                SectionCard {
                    Text(stringResource(R.string.no_search_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.fileMatches.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.section_title_file_names), state.fileMatches.size) }
            items(state.fileMatches, key = { "file-$it" }) { path ->
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = stringResource(R.string.cd_file),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text(path, fontFamily = CodeFontFamily, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.textMatches.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.section_title_content), state.textMatches.size) }
            items(
                state.textMatches,
                key = { "${it.path.text}:${it.lineNumber}:${it.absoluteOffset}" },
            ) { match ->
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = stringResource(R.string.cd_code_match),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${match.path.text}:${match.lineNumber}",
                                fontFamily = CodeFontFamily,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(
                                    match.lines.text.trimEnd(),
                                    fontFamily = CodeFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { item { ErrorCard(it) } }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesTab(
    state: WorkspaceExplorerUiState,
    branches: List<String>,
    onSwitchBranch: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    commits: List<CommitInfo>,
    onOpenChange: (OpenCodeFileChange) -> Unit = {},
    gitActions: GitActions = GitActions(),
) {
    var showBranchSheet by remember { mutableStateOf(false) }
    var showCommitDialog by remember { mutableStateOf(false) }
    var diffViewMode by remember { mutableStateOf(DiffViewMode.UNIFIED) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Source,
                        contentDescription = stringResource(R.string.cd_git),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.git_label), fontWeight = FontWeight.SemiBold)
                        Text(
                            state.vcsInfo?.branch?.let { stringResource(R.string.branch_label, it) }
                                ?: stringResource(R.string.no_git_info),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { showBranchSheet = true },
                        )
                    }
                    Text(stringResource(R.string.item_count, state.changes.size))
                }
            }
        }

        if (state.canRunGit && state.vcsInfo != null) {
            item {
                GitActionBar(
                    state = state,
                    onCommit = { showCommitDialog = true },
                    actions = gitActions,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = diffViewMode == DiffViewMode.UNIFIED,
                    onClick = { diffViewMode = DiffViewMode.UNIFIED },
                    label = { Text(stringResource(R.string.unified_view)) },
                )
                FilterChip(
                    selected = diffViewMode == DiffViewMode.SPLIT,
                    onClick = { diffViewMode = DiffViewMode.SPLIT },
                    label = { Text(stringResource(R.string.split_view)) },
                )
            }
        }

        if (state.isLoadingChanges) {
            item { LoadingCard(stringResource(R.string.loading_changes)) }
        } else if (state.changes.isEmpty()) {
            item {
                SectionCard {
                    Text(stringResource(R.string.no_uncommitted_changes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.changes, key = { it.displayPath }) { change ->
                val detailed = state.diff.firstOrNull { it.displayPath == change.displayPath } ?: change
                ChangeCard(
                    detailed,
                    diffViewMode,
                    onOpenChange,
                    onDiscard = gitActions.onDiscard.takeIf { state.canRunGit && state.runningAction == null },
                )
            }
        }

        if (commits.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.commits_title), commits.size) }
            items(commits, key = { it.hash }) { commit ->
                CommitCard(commit)
            }
        }

        state.error?.let { item { ErrorCard(it) } }
        item { Spacer(Modifier.height(72.dp)) }
    }

    if (showCommitDialog) {
        // What the commit will actually contain: AndCode's own attachment copies and temp files are
        // left out of it (see WorkspaceShell.COMMIT_PATHSPEC), so they are left out of the count too.
        val committable = state.changes.map { it.displayPath }.filterNot(WorkspaceShell::isAndCodeArtifact)
        CommitDialog(
            changeCount = committable.size,
            suggestedMessage = suggestedCommitMessage(committable),
            onCommit = { message ->
                gitActions.onCommit(message)
                showCommitDialog = false
            },
            onDismiss = { showCommitDialog = false },
        )
    }

    if (showBranchSheet) {
        BranchSwitcherSheet(
            currentBranch = state.vcsInfo?.branch.orEmpty(),
            branches = branches,
            onSwitchBranch = { branch ->
                onSwitchBranch(branch)
                showBranchSheet = false
            },
            onCreateBranch = { name ->
                onCreateBranch(name)
                showBranchSheet = false
            },
            onDismiss = { showBranchSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSwitcherSheet(
    currentBranch: String,
    branches: List<String>,
    onSwitchBranch: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var newBranchName by rememberSaveable { mutableStateOf("") }
    var showNewBranchField by remember { mutableStateOf(false) }

    val filteredBranches =
        if (searchQuery.isBlank()) {
            branches
        } else {
            branches.filter { it.contains(searchQuery, ignoreCase = true) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.switch_branch),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_branches)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredBranches, key = { it }) { branch ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSwitchBranch(branch) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (branch == currentBranch) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_current_branch),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Spacer(Modifier.height(24.dp))
                        }
                        Text(
                            branch,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (branch == currentBranch) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (showNewBranchField) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.branch_name_hint)) },
                        singleLine = true,
                    )
                    Button(
                        onClick = { if (newBranchName.isNotBlank()) onCreateBranch(newBranchName) },
                        enabled = newBranchName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.create_branch))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { showNewBranchField = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_branch))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.new_branch))
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(
    change: OpenCodeFileChange,
    diffViewMode: DiffViewMode,
    onOpen: (OpenCodeFileChange) -> Unit = {},
    onDiscard: ((OpenCodeFileChange) -> Unit)? = null,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard && onDiscard != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.git_discard_title)) },
            text = { Text(stringResource(R.string.git_discard_body, change.displayPath)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard(change)
                }) { Text(stringResource(R.string.git_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    var expanded by remember(change.displayPath, change.patch) { mutableStateOf(false) }
    val canOpen = change.isOpenable()
    SectionCard(
        modifier =
            if (canOpen) {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.open_file),
                ) { onOpen(change) }
            } else {
                Modifier
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileTypeIcon(fileName = change.displayPath.substringAfterLast('/'))
            Column(modifier = Modifier.weight(1f)) {
                Text(change.displayPath, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.diff_stat, change.additions.toInt(), change.deletions.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        change.status.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canOpen) {
                OutlinedButton(onClick = { onOpen(change) }) {
                    Text(stringResource(R.string.open_file))
                }
            }
            if (!change.patch.isNullOrBlank()) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.collapse) else stringResource(R.string.diff_label))
                }
            }
            if (onDiscard != null) {
                IconButton(onClick = { confirmDiscard = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.git_discard),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (expanded && !change.patch.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            when (diffViewMode) {
                DiffViewMode.UNIFIED -> UnifiedDiffView(change.patch)
                DiffViewMode.SPLIT -> SplitDiffView(change.patch)
            }
        }
    }
}

@Composable
internal fun UnifiedDiffView(patch: String) {
    SelectionContainer {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        ) {
            patch.lines().forEach { line ->
                val background =
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> Color(0x2A6FCF97)
                        line.startsWith("-") && !line.startsWith("---") -> Color(0x2AF07178)
                        else -> Color.Transparent
                    }
                Text(
                    line,
                    fontFamily = CodeFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(background)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
internal fun SplitDiffView(patch: String) {
    val oldLines = mutableListOf<String>()
    val newLines = mutableListOf<String>()
    patch.lines().forEach { line ->
        when {
            line.startsWith("+") && !line.startsWith("+++") -> {
                newLines.add(line.removePrefix("+"))
                oldLines.add("")
            }
            line.startsWith("-") && !line.startsWith("---") -> {
                oldLines.add(line.removePrefix("-"))
                newLines.add("")
            }
            !line.startsWith("@@") && !line.startsWith("+++") && !line.startsWith("---") -> {
                oldLines.add(line.removePrefix(" "))
                newLines.add(line.removePrefix(" "))
            }
        }
    }

    SelectionContainer {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                oldLines.forEach { line ->
                    Text(
                        line,
                        fontFamily = CodeFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(if (line.isNotEmpty()) Color(0x2AF07178) else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                newLines.forEach { line ->
                    Text(
                        line,
                        fontFamily = CodeFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(if (line.isNotEmpty()) Color(0x2A6FCF97) else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitCard(commit: CommitInfo) {
    SectionCard(modifier = Modifier.clickable { }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(commit.message, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${commit.author} · ${commit.relativeTime}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                commit.hash.take(7),
                fontFamily = CodeFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PrTab(
    prTitle: String?,
    prStatus: String?,
    prDescription: String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (prTitle == null) {
            item {
                SectionCard {
                    Text(
                        stringResource(R.string.no_pr_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                prTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (prStatus != null) {
                                StatusChip(prStatus, active = prStatus.equals("open", ignoreCase = true))
                            }
                        }
                        if (!prDescription.isNullOrBlank()) {
                            Text(
                                prDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    count: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.item_count, count), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingCard(text: String) {
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text(text)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    SectionCard {
        Text(stringResource(R.string.fetch_failed), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Callbacks for the git actions the Changes tab offers on this device's runtime. */
data class GitActions(
    val onCommit: (String) -> Unit = {},
    val onPush: () -> Unit = {},
    val onPull: () -> Unit = {},
    val onStash: () -> Unit = {},
    val onDiscard: (OpenCodeFileChange) -> Unit = {},
    val onConsumeResult: () -> Unit = {},
)

@Composable
private fun GitActionBar(
    state: WorkspaceExplorerUiState,
    onCommit: () -> Unit,
    actions: GitActions,
) {
    val haptics = rememberHaptics()
    val busy = state.runningAction != null
    LaunchedEffect(state.completedAction, state.actionError) {
        when {
            state.completedAction != null -> haptics.perform(HapticEvent.CONFIRM)
            state.actionError != null -> haptics.perform(HapticEvent.REJECT)
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onCommit, enabled = !busy && state.changes.isNotEmpty()) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.git_commit))
                }
                OutlinedButton(onClick = actions.onPull, enabled = !busy) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.git_pull))
                }
                OutlinedButton(onClick = actions.onPush, enabled = !busy) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.git_push))
                }
                OutlinedButton(onClick = actions.onStash, enabled = !busy && state.changes.isNotEmpty()) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.git_stash))
                }
            }
            AnimatedVisibility(visible = busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val confirmation =
                when (state.completedAction) {
                    WorkspaceAction.COMMIT -> stringResource(R.string.git_done_commit)
                    WorkspaceAction.PUSH -> stringResource(R.string.git_done_push)
                    WorkspaceAction.PULL -> stringResource(R.string.git_done_pull)
                    WorkspaceAction.STASH -> stringResource(R.string.git_done_stash)
                    WorkspaceAction.CHECKOUT, WorkspaceAction.CREATE_BRANCH -> stringResource(R.string.git_done_branch)
                    WorkspaceAction.DISCARD -> stringResource(R.string.git_done_discard)
                    WorkspaceAction.SAVE, null -> null
                }
            val message =
                when (state.actionError) {
                    ACTION_ERROR_INVALID_BRANCH -> stringResource(R.string.git_invalid_branch)
                    else -> state.actionError
                } ?: confirmation
            if (message != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (state.actionError != null) CodeFontFamily else null,
                        color = if (state.actionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = actions.onConsumeResult) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close_description),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitDialog(
    changeCount: Int,
    suggestedMessage: String,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Prefilled from the changed files, like the subject line GitHub Desktop proposes, so a quick
    // commit from the phone needs no typing; it is selected text, replaced by the first keystroke.
    var message by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(suggestedMessage, TextRange(0, suggestedMessage.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_commit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.git_commit_body, changeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.git_commit_message_label)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCommit(message.text) }, enabled = message.text.isNotBlank()) {
                Text(stringResource(R.string.git_commit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** "Update Main.kt", "Update Main.kt and README.md", "Update Main.kt and 3 more". */
@Composable
private fun suggestedCommitMessage(paths: List<String>): String {
    val names = paths.map { it.substringAfterLast('/') }.distinct()
    return when (names.size) {
        0 -> ""
        1 -> stringResource(R.string.git_commit_suggest_one, names[0])
        2 -> stringResource(R.string.git_commit_suggest_two, names[0], names[1])
        else -> stringResource(R.string.git_commit_suggest_many, names[0], names.size - 1)
    }
}
