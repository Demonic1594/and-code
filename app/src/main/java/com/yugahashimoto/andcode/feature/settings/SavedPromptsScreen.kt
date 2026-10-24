package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.settings.SavedPrompt
import com.yugahashimoto.andcode.feature.chat.SlashCommandRegistry
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import java.util.UUID

/**
 * Lists the user's saved prompts and lets them add, edit and delete one. Each prompt answers to
 * `/name` in the chat composer, where picking it drops the saved text into the input to send or
 * tweak first.
 */
@Composable
fun SavedPromptsScreen(
    prompts: List<SavedPrompt>,
    onSave: (SavedPrompt) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<SavedPrompt?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedPrompt?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.saved_prompts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editing = null
                        showEditor = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.saved_prompts_add))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.saved_prompts_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
            if (prompts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.saved_prompts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(prompts, key = SavedPrompt::id) { prompt ->
                SavedPromptRow(
                    prompt = prompt,
                    onEdit = {
                        editing = prompt
                        showEditor = true
                    },
                    onDelete = { pendingDelete = prompt },
                )
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showEditor) {
        SavedPromptEditDialog(
            initial = editing,
            existingCommands = prompts.filter { it.id != editing?.id }.map { it.command }.toSet(),
            onSave = { name, text ->
                onSave(SavedPrompt(id = editing?.id ?: UUID.randomUUID().toString(), name = name, text = text))
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }

    pendingDelete?.let { prompt ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.saved_prompts_delete_title)) },
            text = { Text(stringResource(R.string.saved_prompts_delete_body, prompt.command)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(prompt.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SavedPromptRow(
    prompt: SavedPrompt,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(prompt.command, style = MaterialTheme.typography.bodyLarge, fontFamily = CodeFontFamily)
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.saved_prompts_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SavedPromptEditDialog(
    initial: SavedPrompt?,
    existingCommands: Set<String>,
    onSave: (name: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    val normalized = SavedPrompt.normalizeName(name)
    val command = "/$normalized"
    val nameError =
        when {
            name.isNotBlank() && normalized.isEmpty() -> stringResource(R.string.saved_prompts_name_invalid)
            command in existingCommands -> stringResource(R.string.saved_prompts_name_taken, command)
            // A saved prompt must not shadow the composer's own commands; the built-in one would win
            // silently and the user's prompt would look broken.
            command in SlashCommandRegistry.builtInCommandNames -> stringResource(R.string.saved_prompts_name_taken, command)
            else -> null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (initial == null) R.string.saved_prompts_add else R.string.saved_prompts_edit),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(SavedPrompt.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.saved_prompts_name_label)) },
                    supportingText = {
                        Text(nameError ?: if (normalized.isNotEmpty()) command else "")
                    },
                    isError = nameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.saved_prompts_text_label)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), text.trim()) },
                enabled = normalized.isNotEmpty() && text.isNotBlank() && nameError == null,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
