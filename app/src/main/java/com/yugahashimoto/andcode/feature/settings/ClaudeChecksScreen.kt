package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.ClaudeChecks

/** Commands a project commonly checks itself with, offered to fill the field. */
private val afterEditExamples = listOf("npm run lint", "npx tsc --noEmit", "ruff check .", "cargo check")
private val beforeFinishExamples = listOf("npm test", "./gradlew test", "pytest -q", "cargo test")

/**
 * The checks Claude Code runs in the project: one after each file edit, one before it ends a turn.
 * Each is off until a command is saved; a failing one's output goes back to Claude Code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeChecksScreen(
    checks: Map<ClaudeChecks.Kind, String>,
    onSet: (ClaudeChecks.Kind, String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.claude_checks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.claude_checks_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CheckSection(
                title = stringResource(R.string.claude_checks_after_edit),
                caption = stringResource(R.string.claude_checks_after_edit_caption),
                saved = checks[ClaudeChecks.Kind.AFTER_EDIT].orEmpty(),
                examples = afterEditExamples,
                tag = "claude-check-after-edit",
                onSave = { onSet(ClaudeChecks.Kind.AFTER_EDIT, it) },
            )
            CheckSection(
                title = stringResource(R.string.claude_checks_before_finish),
                caption = stringResource(R.string.claude_checks_before_finish_caption),
                saved = checks[ClaudeChecks.Kind.BEFORE_FINISH].orEmpty(),
                examples = beforeFinishExamples,
                tag = "claude-check-before-finish",
                onSave = { onSet(ClaudeChecks.Kind.BEFORE_FINISH, it) },
            )
            Text(
                text = stringResource(R.string.claude_checks_applies),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckSection(
    title: String,
    caption: String,
    saved: String,
    examples: List<String>,
    tag: String,
    onSave: (String) -> Unit,
) {
    // Keyed to what is saved, so the field follows a save or a removal.
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.claude_checks_command_label)) },
            placeholder = { Text(examples.first(), fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { example ->
                SuggestionChip(
                    onClick = { text = example },
                    label = { Text(example, fontFamily = FontFamily.Monospace) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (saved.isNotEmpty()) {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.claude_checks_remove)) }
            }
            Button(onClick = { onSave(text) }, enabled = text.trim() != saved && text.isNotBlank()) {
                Text(stringResource(R.string.claude_checks_save))
            }
        }
    }
}
