package com.yugahashimoto.andcode.feature.git

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.DiffRow
import com.yugahashimoto.andcode.feature.chat.parseUnifiedDiff
import com.yugahashimoto.andcode.ui.components.HapticEvent
import com.yugahashimoto.andcode.ui.components.rememberHaptics
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

private enum class GitTab(
    val label: Int,
) {
    CHANGES(R.string.git_tab_changes),
    HISTORY(R.string.git_tab_history),
    BRANCHES(R.string.git_tab_branches),
    STASHES(R.string.git_tab_stashes),
}

/** What the Source Control screen can ask its view model to do. */
class GitScreenActions(
    val onRefresh: () -> Unit = {},
    val onStage: (GitFileChange) -> Unit = {},
    val onUnstage: (GitFileChange) -> Unit = {},
    val onStageAll: () -> Unit = {},
    val onUnstageAll: () -> Unit = {},
    val onDiscard: (GitFileChange) -> Unit = {},
    val onCommitMessageChange: (String) -> Unit = {},
    val onAmendChange: (Boolean) -> Unit = {},
    val onCommit: () -> Unit = {},
    val onFetch: () -> Unit = {},
    val onPull: () -> Unit = {},
    val onPush: () -> Unit = {},
    val onCheckout: (GitBranch) -> Unit = {},
    val onCreateBranch: (String) -> Unit = {},
    val onDeleteBranch: (GitBranch, force: Boolean) -> Unit = { _, _ -> },
    val onStash: (String) -> Unit = {},
    val onApplyStash: (GitStash, pop: Boolean) -> Unit = { _, _ -> },
    val onDropStash: (GitStash) -> Unit = {},
    val onOpenDiff: (GitFileChange, staged: Boolean) -> Unit = { _, _ -> },
    val onOpenCommit: (String) -> Unit = {},
    val onOpenCommitFile: (GitCommitFile) -> Unit = {},
    val onCloseDiff: () -> Unit = {},
    val onCloseCommit: () -> Unit = {},
    val onLoadMoreCommits: () -> Unit = {},
    val onConsumeResult: () -> Unit = {},
)

/**
 * Source control for a workspace, laid out like the panel of a desktop editor: the branch and its
 * sync state on top, then changes to stage and commit, the history, the branches and the stashes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    state: GitUiState,
    onBack: () -> Unit,
    actions: GitScreenActions,
) {
    var tab by rememberSaveable { mutableStateOf(GitTab.CHANGES) }
    // Held up here, above the diff and commit screens, so coming back from one keeps the place.
    val historyList = rememberLazyListState()
    val haptics = rememberHaptics()
    LaunchedEffect(state.done, state.error) {
        when {
            state.done != null -> haptics.perform(HapticEvent.CONFIRM)
            state.error != null -> haptics.perform(HapticEvent.REJECT)
        }
    }
    state.diff?.let { diff ->
        BackHandler(onBack = actions.onCloseDiff)
        DiffScreen(diff, onClose = actions.onCloseDiff)
        return
    }
    state.commit?.let { commit ->
        BackHandler(onBack = actions.onCloseCommit)
        CommitScreen(commit, onClose = actions.onCloseCommit, onOpenFile = actions.onOpenCommitFile)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.git_title))
                        Text(
                            state.directory.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.git_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.status == null && state.loadError != null) {
                NotARepository(state.loadError)
                return@Column
            }
            SyncHeader(state, actions)
            AnimatedVisibility(visible = state.running != null || state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ResultBanner(state, actions.onConsumeResult)
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                GitTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = {
                            val count =
                                when (entry) {
                                    GitTab.CHANGES -> state.status?.changes?.size ?: 0
                                    GitTab.STASHES -> state.stashes.size
                                    else -> 0
                                }
                            Text(if (count > 0) "${stringResource(entry.label)} $count" else stringResource(entry.label), maxLines = 1)
                        },
                    )
                }
            }
            when (tab) {
                GitTab.CHANGES -> ChangesTab(state, actions)
                GitTab.HISTORY -> HistoryTab(state, actions, historyList)
                GitTab.BRANCHES -> BranchesTab(state, actions)
                GitTab.STASHES -> StashesTab(state, actions)
            }
        }
    }
}

@Composable
private fun NotARepository(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.git_not_a_repo),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = CodeFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/** The branch, how far it is from its upstream, and the three sync buttons. */
@Composable
private fun SyncHeader(
    state: GitUiState,
    actions: GitScreenActions,
) {
    val status = state.status ?: return
    val busy = state.running != null
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    status.branch ?: stringResource(R.string.git_detached),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = CodeFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                )
                if (status.ahead > 0) CountPill("↑${status.ahead}", MaterialTheme.colorScheme.primary)
                if (status.behind > 0) CountPill("↓${status.behind}", MaterialTheme.colorScheme.tertiary)
            }
            Text(
                when {
                    status.noCommits -> stringResource(R.string.git_no_commits)
                    status.upstream == null -> stringResource(R.string.git_no_upstream)
                    status.ahead == 0 && status.behind == 0 -> stringResource(R.string.git_up_to_date, status.upstream)
                    else -> stringResource(R.string.git_tracking, status.upstream)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(onClick = actions.onFetch, enabled = !busy) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.git_fetch))
                }
                OutlinedButton(onClick = actions.onPull, enabled = !busy && status.upstream != null) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (status.behind > 0) {
                            "${stringResource(
                                R.string.git_pull,
                            )} ${status.behind}"
                        } else {
                            stringResource(R.string.git_pull)
                        },
                    )
                }
                FilledTonalButton(onClick = actions.onPush, enabled = !busy && !status.noCommits) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (status.ahead > 0) "${stringResource(R.string.git_push)} ${status.ahead}" else stringResource(R.string.git_push),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountPill(
    text: String,
    color: Color,
) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f), modifier = Modifier.padding(start = Spacing.xs)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ResultBanner(
    state: GitUiState,
    onDismiss: () -> Unit,
) {
    val message =
        when {
            state.error == GitViewModel.ERROR_INVALID_REF -> stringResource(R.string.git_invalid_branch)
            state.error != null -> state.error
            else ->
                when (state.done) {
                    GitOperation.COMMIT -> stringResource(R.string.git_done_commit)
                    GitOperation.PUSH -> stringResource(R.string.git_done_push)
                    GitOperation.PULL -> stringResource(R.string.git_done_pull)
                    GitOperation.FETCH -> stringResource(R.string.git_done_fetch)
                    GitOperation.STASH -> stringResource(R.string.git_done_stash)
                    GitOperation.CHECKOUT, GitOperation.CREATE_BRANCH -> stringResource(R.string.git_done_branch)
                    GitOperation.DELETE_BRANCH -> stringResource(R.string.git_done_delete_branch)
                    GitOperation.APPLY_STASH -> stringResource(R.string.git_done_apply_stash)
                    GitOperation.DROP_STASH -> stringResource(R.string.git_done_drop_stash)
                    GitOperation.DISCARD -> stringResource(R.string.git_done_discard)
                    else -> null
                }
        } ?: return
    val isError = state.error != null
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs).clickable(onClick = onDismiss),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (isError) CodeFontFamily else null,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(Spacing.md),
        )
    }
}

@Composable
private fun ChangesTab(
    state: GitUiState,
    actions: GitScreenActions,
) {
    val status = state.status ?: return
    val message = state.commitMessage
    val amend = state.amend
    var confirmDiscard by remember { mutableStateOf<GitFileChange?>(null) }
    val busy = state.running != null
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = actions.onCommitMessageChange,
                    placeholder = { Text(stringResource(R.string.git_commit_message_label)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                    Checkbox(
                        checked = amend,
                        onCheckedChange = actions.onAmendChange,
                        enabled = !status.noCommits,
                    )
                    Text(stringResource(R.string.git_amend), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = actions.onCommit,
                        enabled = !busy && !state.loading && message.isNotBlank() && (status.changes.isNotEmpty() || amend),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                when {
                                    amend -> R.string.git_amend_commit
                                    status.staged.isEmpty() -> R.string.git_commit_all
                                    else -> R.string.git_commit
                                },
                            ),
                        )
                    }
                }
            }
        }
        if (status.changes.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.git_clean),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                )
            }
        }
        if (status.staged.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.git_staged), status.staged.size) {
                    TextButton(onClick = actions.onUnstageAll, enabled = !busy) { Text(stringResource(R.string.git_unstage_all)) }
                }
            }
            items(status.staged, key = { "s:" + it.path }) { change ->
                ChangeRow(
                    change = change,
                    letter = change.index,
                    onOpen = { actions.onOpenDiff(change, true) },
                    trailing = {
                        IconButton(onClick = { actions.onUnstage(change) }, enabled = !busy) {
                            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.git_unstage))
                        }
                    },
                )
            }
        }
        if (status.unstaged.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.git_unstaged), status.unstaged.size) {
                    TextButton(onClick = actions.onStageAll, enabled = !busy) { Text(stringResource(R.string.git_stage_all)) }
                }
            }
            items(status.unstaged, key = { "u:" + it.path }) { change ->
                ChangeRow(
                    change = change,
                    letter =
                        if (change.conflicted) {
                            '!'
                        } else if (change.untracked) {
                            'U'
                        } else {
                            change.worktree
                        },
                    onOpen = { actions.onOpenDiff(change, false) },
                    trailing = {
                        // A conflicted file is resolved, not discarded; git refuses to restore it.
                        if (!change.conflicted) {
                            IconButton(onClick = { confirmDiscard = change }, enabled = !busy) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.git_discard))
                            }
                        }
                        IconButton(onClick = { actions.onStage(change) }, enabled = !busy) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.git_stage))
                        }
                    },
                )
            }
        }
    }
    confirmDiscard?.let { change ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text(stringResource(R.string.git_discard_title)) },
            text = { Text(stringResource(R.string.git_discard_body, change.path)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = null
                    actions.onDiscard(change)
                }) { Text(stringResource(R.string.git_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    action: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            " $count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

/** The colour git UIs give each change letter: added green, modified amber, deleted red. */
@Composable
private fun letterColor(letter: Char): Color =
    when (letter) {
        'A', 'U', '?' -> Color(0xFF2EA043)
        'D', '!' -> MaterialTheme.colorScheme.error
        'R', 'C' -> Color(0xFF58A6FF)
        else -> Color(0xFFD29922)
    }

@Composable
private fun ChangeRow(
    change: GitFileChange,
    letter: Char,
    onOpen: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().clickable(
                onClick = onOpen,
            ).padding(start = Spacing.lg, end = Spacing.xs, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(letterColor(letter).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter.toString(),
                color = letterColor(letter),
                fontFamily = CodeFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            Text(
                change.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folder = change.path.substringBeforeLast('/', "")
            val subtitle =
                listOfNotNull(
                    folder.ifEmpty { null },
                    change.originalPath?.let { "← $it" },
                ).joinToString("  ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun HistoryTab(
    state: GitUiState,
    actions: GitScreenActions,
    listState: LazyListState,
) {
    if (state.commits.isEmpty()) {
        Text(
            stringResource(R.string.git_no_history),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
        )
        return
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        items(state.commits, key = { it.hash }) { commit ->
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        actions.onOpenCommit(
                            commit.hash,
                        )
                    }.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                // The history line: a dot per commit, a ring for a merge.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp).padding(top = 4.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .size(if (commit.parents.size > 1) 12.dp else 10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (commit.parents.size > 1) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                ),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
                    Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Text(commit.shortHash, fontFamily = CodeFontFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "  ${commit.author} · ${dateFormat.format(Date(commit.time * 1000))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (commit.refs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            commit.refs.forEach { ref ->
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        ref.removePrefix("HEAD -> ").removePrefix("tag: "),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = CodeFontFamily,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 44.dp),
            )
        }
        if (state.moreCommits) {
            item {
                LaunchedEffect(state.commits.size) { actions.onLoadMoreCommits() }
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.git_loading_more), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun BranchesTab(
    state: GitUiState,
    actions: GitScreenActions,
) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GitBranch?>(null) }
    val busy = state.running != null
    val local = state.branches.filter { !it.remote }
    val remote = state.branches.filter { it.remote }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(stringResource(R.string.git_local_branches), local.size) {
                TextButton(onClick = { creating = true }, enabled = !busy) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.git_new_branch), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        items(local, key = { "l:" + it.name }) { branch ->
            BranchRow(branch, busy, onCheckout = { actions.onCheckout(branch) }, onDelete = { deleting = branch })
        }
        if (remote.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.git_remote_branches), remote.size) }
            items(remote, key = { "r:" + it.name }) { branch ->
                BranchRow(branch, busy, onCheckout = { actions.onCheckout(branch) }, onDelete = null)
            }
        }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.git_new_branch),
            label = stringResource(R.string.git_branch_name),
            confirm = stringResource(R.string.git_create),
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                actions.onCreateBranch(it)
            },
        )
    }
    deleting?.let { branch ->
        var force by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.git_delete_branch_title, branch.name)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = force, onCheckedChange = { force = it })
                    Text(stringResource(R.string.git_delete_unmerged), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.onDeleteBranch(branch, force)
                }) { Text(stringResource(R.string.git_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BranchRow(
    branch: GitBranch,
    busy: Boolean,
    onCheckout: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy && !branch.current, onClick = onCheckout)
                .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (branch.current) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
            Text(
                branch.name,
                fontFamily = CodeFontFamily,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (branch.current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    branch.shortHash,
                    branch.relativeDate,
                    branch.upstream?.let { "→ $it" },
                    branch.track.trim('[', ']').ifEmpty { null },
                )
                    .filterNotNull()
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDelete != null && !branch.current) {
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.git_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StashesTab(
    state: GitUiState,
    actions: GitScreenActions,
) {
    var saving by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf<GitStash?>(null) }
    val busy = state.running != null
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(stringResource(R.string.git_tab_stashes), state.stashes.size) {
                TextButton(onClick = { saving = true }, enabled = !busy && state.status?.changes?.isNotEmpty() == true) {
                    Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.git_stash_changes), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (state.stashes.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.git_no_stashes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                )
            }
        }
        items(state.stashes, key = { it.ref }) { stash ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stash.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${stash.ref} · ${stash.relativeDate}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = CodeFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { actions.onApplyStash(stash, true) },
                    enabled = !busy,
                ) { Text(stringResource(R.string.git_stash_pop)) }
                IconButton(onClick = { actions.onApplyStash(stash, false) }, enabled = !busy) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.git_stash_apply))
                }
                IconButton(onClick = { dropping = stash }, enabled = !busy) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.git_stash_drop),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    dropping?.let { stash ->
        AlertDialog(
            onDismissRequest = { dropping = null },
            title = { Text(stringResource(R.string.git_drop_stash_title, stash.ref)) },
            text = { Text(stash.message) },
            confirmButton = {
                TextButton(onClick = {
                    dropping = null
                    actions.onDropStash(stash)
                }) { Text(stringResource(R.string.git_stash_drop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dropping = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (saving) {
        NameDialog(
            title = stringResource(R.string.git_stash_changes),
            label = stringResource(R.string.git_stash_message),
            confirm = stringResource(R.string.git_stash),
            allowEmpty = true,
            onDismiss = { saving = false },
            onConfirm = {
                saving = false
                actions.onStash(it)
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    allowEmpty: Boolean = false,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = {
                text = it
            }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = allowEmpty || text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One commit: its message, author, date and the files it changed, each opening its diff. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitScreen(
    details: GitCommitDetails,
    onClose: () -> Unit,
    onOpenFile: (GitCommitFile) -> Unit,
) {
    val commit = details.commit
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(commit.shortHash, fontFamily = CodeFontFamily) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    Text(commit.subject, style = MaterialTheme.typography.titleMedium)
                    if (details.body.isNotBlank()) {
                        Text(details.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.sm))
                    }
                    Text(
                        "${commit.author} <${commit.email}>",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                    Text(
                        dateFormat.format(Date(commit.time * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(commit.hash, fontFamily = CodeFontFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader(stringResource(R.string.git_files_changed), details.files.size) }
            if (details.filesIncomplete) {
                item { GitNotice(stringResource(R.string.git_files_incomplete)) }
            }
            // Keyed by position as well as path: a list that repeats a path must not crash the screen.
            itemsIndexed(details.files, key = { index, file -> "$index:${file.path}" }) { _, file ->
                ChangeRow(
                    change = GitFileChange(file.path, file.originalPath, file.status, ' '),
                    letter = file.status,
                    onOpen = { onOpenFile(file) },
                    trailing = {},
                )
            }
        }
    }
}

/** A unified diff with line numbers, added lines green and removed lines red. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffScreen(
    diff: GitDiffView,
    onClose: () -> Unit,
) {
    val parsed = remember(diff.text) { diff.text?.let(::parseUnifiedDiff) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(diff.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            Text(
                                diff.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
                actions = {
                    parsed?.let {
                        Text("+${it.additions}", color = Color(0xFF2EA043), fontFamily = CodeFontFamily, fontSize = 13.sp)
                        Text(
                            " −${it.deletions}",
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = CodeFontFamily,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = Spacing.md),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                diff.error != null ->
                    Text(
                        diff.error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = CodeFontFamily,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                parsed == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                parsed.rows.isEmpty() ->
                    Text(
                        stringResource(R.string.git_no_diff),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        if (diff.truncated) {
                            item { GitNotice(stringResource(R.string.git_diff_truncated)) }
                        }
                        items(parsed.rows.size) { index -> DiffLine(parsed.rows[index]) }
                    }
            }
        }
    }
}

@Composable
private fun DiffLine(row: DiffRow) {
    val (background, foreground, sign) =
        when (row.kind) {
            DiffRow.Kind.ADDED -> Triple(Color(0x332EA043), MaterialTheme.colorScheme.onSurface, "+")
            DiffRow.Kind.REMOVED -> Triple(Color(0x33F85149), MaterialTheme.colorScheme.onSurface, "−")
            DiffRow.Kind.HUNK ->
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "",
                )
            DiffRow.Kind.META -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant, "")
            DiffRow.Kind.CONTEXT -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, " ")
        }
    Row(modifier = Modifier.widthIn(min = 600.dp).background(background).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        val gutter = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        Text(
            row.oldLine?.toString() ?: "",
            fontFamily = CodeFontFamily,
            fontSize = 11.sp,
            color = gutter,
            modifier = Modifier.width(40.dp).padding(end = 4.dp),
            maxLines = 1,
        )
        Text(
            row.newLine?.toString() ?: "",
            fontFamily = CodeFontFamily,
            fontSize = 11.sp,
            color = gutter,
            modifier = Modifier.width(40.dp).padding(end = 4.dp),
            maxLines = 1,
        )
        Text(sign, fontFamily = CodeFontFamily, fontSize = 12.sp, color = foreground, modifier = Modifier.width(14.dp))
        Text(
            row.text,
            fontFamily = CodeFontFamily,
            fontSize = 12.sp,
            color = foreground,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = Spacing.lg),
        )
    }
}

/** A one-line note above a list or diff that is not shown whole. */
@Composable
private fun GitNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}
