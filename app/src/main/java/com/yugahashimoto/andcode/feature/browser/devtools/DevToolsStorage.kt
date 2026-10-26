package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

private enum class StorageKind(
    val label: Int,
    /** The name the page agent's storage functions take. */
    val agentName: String,
) {
    LOCAL(R.string.devtools_local_storage, "local"),
    SESSION(R.string.devtools_session_storage, "session"),
    COOKIES(R.string.devtools_cookies, "cookie"),
}

/** The page's local storage, session storage and cookies, each editable in place. */
@Composable
internal fun StorageTab(
    bridge: DevToolsBridge,
    modifier: Modifier = Modifier,
) {
    var kind by rememberSaveable { mutableStateOf(StorageKind.LOCAL) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Header?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() = bridge.call("storage") { snapshot = DevToolsProtocol.parseStorage(it) }
    LaunchedEffect(Unit) { refresh() }

    val entries =
        when (kind) {
            StorageKind.LOCAL -> snapshot?.local
            StorageKind.SESSION -> snapshot?.session
            StorageKind.COOKIES -> snapshot?.cookies
        }.orEmpty().filter { query.isBlank() || it.name.contains(query, true) || it.value.contains(query, true) }

    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            StorageKind.entries.forEach { item ->
                SegmentedButton(
                    selected = item == kind,
                    onClick = { kind = item },
                    shape = SegmentedButtonDefaults.itemShape(item.ordinal, StorageKind.entries.size),
                    icon = {},
                ) { Text(stringResource(item.label), fontSize = 12.sp, maxLines = 1) }
            }
        }
        DevToolsToolbar(
            query = query,
            onQueryChange = { query = it },
            chips = emptyList(),
            selectedChip = 0,
            onChipSelected = {},
            trailing = {
                IconButton(
                    onClick = { adding = true },
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.devtools_add_entry)) }
                IconButton(
                    onClick = ::refresh,
                ) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.devtools_refresh)) }
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.devtools_clear_all))
                }
            },
        )
        snapshot?.origin?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.devtools_origin, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                DevToolsEmpty(stringResource(R.string.devtools_storage_empty))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.name }) { entry ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth().clickable { editing = entry }.padding(
                                    horizontal = Spacing.md,
                                    vertical = Spacing.sm,
                                ),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                entry.name,
                                fontFamily = CodeFontFamily,
                                fontSize = 12.sp,
                                color = valueColors().key,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.4f),
                            )
                            Text(
                                entry.value,
                                fontFamily = CodeFontFamily,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.6f).padding(start = Spacing.sm),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        StorageEntryDialog(
            initialKey = entry.name,
            initialValue = entry.value,
            keyEditable = false,
            onDismiss = { editing = null },
            onSave = { _, value ->
                editing = null
                bridge.call("storageSet", kind.agentName, entry.name, value) { refresh() }
            },
            onDelete = {
                editing = null
                bridge.call("storageRemove", kind.agentName, entry.name) { refresh() }
            },
        )
    }
    if (adding) {
        StorageEntryDialog(
            initialKey = "",
            initialValue = "",
            keyEditable = true,
            onDismiss = { adding = false },
            onSave = { key, value ->
                adding = false
                if (key.isNotBlank()) bridge.call("storageSet", kind.agentName, key, value) { refresh() }
            },
            onDelete = null,
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.devtools_clear_all_title, stringResource(kind.label))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    bridge.call("storageClear", kind.agentName) { refresh() }
                }) { Text(stringResource(R.string.devtools_clear_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StorageEntryDialog(
    initialKey: String,
    initialValue: String,
    keyEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var key by remember { mutableStateOf(initialKey) }
    var value by remember { mutableStateOf(prettyBody(initialValue, "")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (keyEditable) R.string.devtools_add_entry else R.string.devtools_edit_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    enabled = keyEditable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.devtools_key)) },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.devtools_value)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily),
                    modifier = Modifier.heightIn(max = 240.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, value) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(
                        onClick = it,
                    ) { Text(stringResource(R.string.devtools_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
