package com.yugahashimoto.andcode.feature.storage

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing
import java.io.File

/** How an area looks: its name, what it holds, an icon and a colour that reads in both themes. */
private class AreaStyle(
    val name: Int,
    val description: Int,
    val icon: ImageVector,
    val color: Color,
)

private val AREA_STYLES =
    mapOf(
        StorageArea.PROJECTS to
            AreaStyle(
                R.string.storage_area_projects,
                R.string.storage_area_projects_desc,
                Icons.Outlined.Folder,
                Color(0xFF3B82F6),
            ),
        StorageArea.ATTACHMENTS to
            AreaStyle(
                R.string.storage_area_attachments,
                R.string.storage_area_attachments_desc,
                Icons.Outlined.Image,
                Color(0xFF14B8A6),
            ),
        StorageArea.AGENTS to
            AreaStyle(
                R.string.storage_area_agents,
                R.string.storage_area_agents_desc,
                Icons.Outlined.SmartToy,
                Color(0xFF8B5CF6),
            ),
        StorageArea.AGENT_DATA to
            AreaStyle(
                R.string.storage_area_agent_data,
                R.string.storage_area_agent_data_desc,
                Icons.Outlined.Chat,
                Color(0xFF6366F1),
            ),
        StorageArea.LINUX to
            AreaStyle(
                R.string.storage_area_linux,
                R.string.storage_area_linux_desc,
                Icons.Outlined.Memory,
                Color(0xFF64748B),
            ),
        StorageArea.CACHES to
            AreaStyle(
                R.string.storage_area_caches,
                R.string.storage_area_caches_desc,
                Icons.Outlined.Cached,
                Color(0xFFF59E0B),
            ),
        StorageArea.LOGS to
            AreaStyle(
                R.string.storage_area_logs,
                R.string.storage_area_logs_desc,
                Icons.Outlined.ReceiptLong,
                Color(0xFFA16207),
            ),
        StorageArea.LEFTOVERS to
            AreaStyle(
                R.string.storage_area_leftovers,
                R.string.storage_area_leftovers_desc,
                Icons.Outlined.AutoDelete,
                Color(0xFFEF4444),
            ),
        StorageArea.TEMP to
            AreaStyle(
                R.string.storage_area_temp,
                R.string.storage_area_temp_desc,
                Icons.Outlined.Timer,
                Color(0xFF06B6D4),
            ),
        StorageArea.BACKUP to
            AreaStyle(
                R.string.storage_area_backup,
                R.string.storage_area_backup_desc,
                Icons.Outlined.Backup,
                Color(0xFF94A3B8),
            ),
        StorageArea.VOICE to
            AreaStyle(
                R.string.storage_area_voice,
                R.string.storage_area_voice_desc,
                Icons.Outlined.Mic,
                Color(0xFFEC4899),
            ),
        StorageArea.OTHER to
            AreaStyle(
                R.string.storage_area_other,
                R.string.storage_area_other_desc,
                Icons.Outlined.MoreHoriz,
                Color(0xFFA3A3A3),
            ),
    )

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

class StorageActions(
    val onBack: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onClean: (Cleanup) -> Unit = {},
    val onCleanAll: () -> Unit = {},
    val onOpen: (File) -> Unit = {},
    val onUp: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onDelete: (File) -> Unit = {},
    val onConsumeNotice: () -> Unit = {},
)

/**
 * Everything the app keeps on the device: how much, in which areas, what can be cleaned now, the
 * largest files, and a browser that lists any folder by size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    state: StorageUiState,
    actions: StorageActions,
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val freedText = (state.notice as? StorageNotice.Freed)?.let { stringResource(R.string.storage_freed, size(it.bytes)) }
    val busyText = stringResource(R.string.storage_busy)
    LaunchedEffect(state.notice) {
        val text =
            when (state.notice) {
                is StorageNotice.Freed -> freedText
                StorageNotice.Busy -> busyText
                null -> null
            } ?: return@LaunchedEffect
        actions.onConsumeNotice()
        snackbar.showSnackbar(text)
    }
    var confirmDelete by remember { mutableStateOf<BrowseEntry?>(null) }

    // What a clean would remove, asked about first: one area, or every area when null.
    var confirmClean by remember { mutableStateOf<Pair<Cleanup?, Long>?>(null) }
    var showAllLargest by remember { mutableStateOf(false) }
    BackHandler(enabled = state.folder != null || showAllLargest) {
        if (state.folder != null) actions.onUp() else showAllLargest = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                state.folder != null -> state.folder.name
                                showAllLargest -> stringResource(R.string.storage_largest)
                                else -> stringResource(R.string.storage_title)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.folder?.let { folder ->
                            Text(
                                displayPath(folder, context.filesDir, context.cacheDir),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CodeFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            state.folder != null -> actions.onUp()
                            showAllLargest -> showAllLargest = false
                            else -> actions.onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRescan, enabled = !state.scanning && !state.working) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedVisibility(visible = state.scanning || state.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                state.folder != null -> Browser(state, actions, onDelete = { confirmDelete = it })
                showAllLargest -> LargestList(state, actions)
                else ->
                    Overview(
                        state,
                        actions,
                        onSeeAllLargest = { showAllLargest = true },
                        onAskClean = { cleanup -> confirmClean = cleanup to (cleanup?.bytes ?: state.reclaimable) },
                    )
            }
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.storage_delete_title, entry.file.name)) },
            text = { Text(stringResource(R.string.storage_delete_body, size(entry.bytes))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    actions.onDelete(entry.file)
                }) { Text(stringResource(R.string.storage_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmClean?.let { (cleanup, bytes) ->
        AlertDialog(
            onDismissRequest = { confirmClean = null },
            title = { Text(stringResource(R.string.storage_clean_title, size(bytes))) },
            text = { Text(stringResource(R.string.storage_clean_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClean = null
                    if (cleanup == null) actions.onCleanAll() else actions.onClean(cleanup)
                }) { Text(stringResource(R.string.storage_free_up, size(bytes))) }
            },
            dismissButton = { TextButton(onClick = { confirmClean = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun displayPath(
    file: File,
    filesDir: File,
    cacheDir: File,
): String =
    when {
        file.path.startsWith(filesDir.path) -> "~" + file.path.removePrefix(filesDir.path)
        file.path.startsWith(cacheDir.path) -> "cache" + file.path.removePrefix(cacheDir.path)
        else -> file.path
    }

@Composable
private fun Overview(
    state: StorageUiState,
    actions: StorageActions,
    onSeeAllLargest: () -> Unit,
    onAskClean: (Cleanup?) -> Unit,
) {
    val filesDir = LocalContext.current.filesDir
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenEdge, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item { UsageCard(state) }
        if (!state.scanning || state.cleanups.isNotEmpty()) item { CleanupCard(state, onAskClean) }
        if (state.areas.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.storage_breakdown)) }
            items(state.areas, key = {
                it.area
            }) { usage ->
                // An area may start with a single file (an agent's binary): open its first folder.
                AreaRow(usage, state.appBytes, onOpen = { usage.folders.firstOrNull { it.isDirectory }?.let(actions.onOpen) })
            }
        }
        if (state.largest.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.storage_largest), modifier = Modifier.weight(1f))
                    TextButton(onClick = onSeeAllLargest) { Text(stringResource(R.string.storage_see_all)) }
                }
            }
            item { LargestPreview(state, actions) }
        }
        item {
            TextButton(onClick = { actions.onOpen(filesDir) }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.storage_browse_all), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier.padding(top = Spacing.xs))
}

/** How much the app uses, split by area, against the device's free space. */
@Composable
private fun UsageCard(state: StorageUiState) {
    SectionCard {
        Text(
            stringResource(R.string.storage_used_by_app),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (state.scanning && state.appBytes == 0L) "…" else size(state.appBytes),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.scanning) {
            Text(
                pluralStringResource(R.plurals.storage_scanning, state.filesSeen, state.filesSeen),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.deviceTotal > 0) {
            // The whole device, so the app's share is seen against what is left.
            val used = (state.deviceTotal - state.deviceFree).coerceAtLeast(0)
            val others = (used - state.appBytes).coerceAtLeast(0)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                state.areas.forEach { usage ->
                    if (usage.bytes > 0) {
                        Box(
                            Modifier.weight(usage.bytes.toFloat()).fillMaxHeight().background(AREA_STYLES.getValue(usage.area).color),
                        )
                    }
                }
                if (others > 0) {
                    Box(
                        Modifier.weight(others.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                if (state.deviceFree > 0) Spacer(Modifier.weight(state.deviceFree.toFloat()))
            }
            Row(modifier = Modifier.padding(top = Spacing.sm)) {
                Text(
                    stringResource(R.string.storage_device_free, size(state.deviceFree), size(state.deviceTotal)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.padding(
                        top = 4.dp,
                    ).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                )
                Text(
                    stringResource(R.string.storage_other_apps, size(others)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CleanupCard(
    state: StorageUiState,
    onAskClean: (Cleanup?) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CleaningServices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.storage_cleanup_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
            )
        }
        if (state.cleanups.isEmpty()) {
            Text(
                stringResource(R.string.storage_nothing_to_clean),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            return@SectionCard
        }
        state.cleanups.forEach { cleanup ->
            val style = AREA_STYLES.getValue(cleanup.area)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.md)) {
                AreaIcon(style)
                Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                    Text(stringResource(style.name), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(style.description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onAskClean(cleanup) }, enabled = !state.working && !state.scanning) {
                    Text(size(cleanup.bytes))
                }
            }
        }
        FilledTonalButton(
            onClick = { onAskClean(null) },
            enabled = !state.working && !state.scanning,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        ) {
            Text(stringResource(R.string.storage_free_up, size(state.reclaimable)))
        }
    }
}

@Composable
private fun AreaIcon(style: AreaStyle) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(style.color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AreaRow(
    usage: AreaUsage,
    total: Long,
    onOpen: () -> Unit,
) {
    val style = AREA_STYLES.getValue(usage.area)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AreaIcon(style)
                Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                    Text(stringResource(style.name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(style.description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(size(usage.bytes), style = MaterialTheme.typography.bodyMedium, fontFamily = CodeFontFamily)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ShareBar(usage.bytes, total, style.color, Modifier.padding(top = Spacing.sm))
        }
    }
}

@Composable
private fun ShareBar(
    value: Long,
    total: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}

@Composable
private fun LargestPreview(
    state: StorageUiState,
    actions: StorageActions,
) {
    SectionCard {
        state.largest.take(PREVIEW_FILES).forEach { file ->
            LargeFileRow(file, actions)
        }
    }
}

@Composable
private fun LargestList(
    state: StorageUiState,
    actions: StorageActions,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screenEdge)) {
        items(state.largest, key = { it.file.path }) { file ->
            LargeFileRow(file, actions)
        }
    }
}

@Composable
private fun LargeFileRow(
    file: LargeFile,
    actions: StorageActions,
) {
    val context = LocalContext.current
    // Opens the folder holding the file: deleting happens there, where it is clear what else goes.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { file.file.parentFile?.let(actions.onOpen) }
                .padding(vertical = Spacing.sm),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            Text(file.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                displayPath(file.file.parentFile ?: file.file, context.filesDir, context.cacheDir),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = CodeFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(size(file.bytes), style = MaterialTheme.typography.labelMedium, fontFamily = CodeFontFamily)
    }
}

/** One folder's contents, largest first, with a share bar each and delete where it is safe. */
@Composable
private fun Browser(
    state: StorageUiState,
    actions: StorageActions,
    onDelete: (BrowseEntry) -> Unit,
) {
    val total = state.entries.sumOf { it.bytes }
    if (state.entries.isEmpty()) {
        Text(
            stringResource(R.string.storage_empty_folder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = Spacing.sm)) {
        item {
            Text(
                pluralStringResource(R.plurals.storage_items, state.entries.size, state.entries.size) + " · " + size(total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screenEdge, vertical = Spacing.xs),
            )
        }
        items(state.entries, key = { it.file.path }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = entry.folder) { actions.onOpen(entry.file) }
                        .padding(start = Spacing.screenEdge, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            ) {
                Icon(
                    if (entry.folder) Icons.Outlined.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (entry.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!entry.deletable) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.storage_locked),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 4.dp).size(12.dp),
                            )
                        }
                    }
                    ShareBar(entry.bytes, total, MaterialTheme.colorScheme.primary, Modifier.padding(top = 4.dp))
                }
                Text(
                    size(entry.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = CodeFontFamily,
                    modifier = Modifier.width(72.dp),
                    maxLines = 1,
                )
                if (entry.deletable) {
                    IconButton(onClick = { onDelete(entry) }, enabled = !state.working) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.storage_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}

private const val PREVIEW_FILES = 5
