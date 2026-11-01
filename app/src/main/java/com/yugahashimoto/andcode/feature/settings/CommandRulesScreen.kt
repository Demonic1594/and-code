package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionBridge.CommandRule

/** Commands people commonly want refused outright, offered one tap away. */
private val suggestedBlocks = listOf("rm -rf", "git push --force", "git reset --hard", "sudo")

/**
 * Claude Code's remembered command rules: commands it may always run without asking, and commands it
 * is never allowed to run. Blocking rules are added here by typing the command's first words (or one
 * of the suggestions); always-allow rules come from the approval sheet and can only be removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandRulesScreen(
    rules: List<CommandRule>,
    onBlock: (String) -> Boolean,
    onForget: (CommandRule) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.command_rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
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
                    text = stringResource(R.string.command_rules_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
            item { BlockCommandField(blocked = rules.filter { it.blocks }.mapNotNull { it.words }, onBlock = onBlock) }
            ruleSection(R.string.command_rules_blocked, rules.filter { it.blocks }, onForget)
            ruleSection(R.string.command_rules_allowed, rules.filterNot { it.blocks }, onForget)
            if (rules.none { !it.blocks }) {
                item {
                    Text(
                        text = stringResource(R.string.command_rules_no_allowed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockCommandField(
    blocked: List<String>,
    onBlock: (String) -> Boolean,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (onBlock(text)) text = "" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.command_rules_block_label)) },
            placeholder = { Text(suggestedBlocks.first(), fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).testTag("command-rules-block-input"),
        )
        Button(onClick = submit, enabled = text.isNotBlank()) {
            Text(stringResource(R.string.command_rules_block_button))
        }
    }
    val offered = suggestedBlocks.filterNot { it in blocked }
    if (offered.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            offered.forEach { words ->
                SuggestionChip(
                    onClick = { onBlock(words) },
                    label = { Text(words, fontFamily = FontFamily.Monospace) },
                )
            }
        }
    }
}

private fun LazyListScope.ruleSection(
    titleRes: Int,
    rules: List<CommandRule>,
    onForget: (CommandRule) -> Unit,
) {
    if (rules.isEmpty()) return
    item {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
    }
    items(rules, key = { "${it.blocks}:${it.toolName}:${it.words}" }) { rule ->
        RuleRow(rule = rule, onForget = { onForget(rule) })
        HorizontalDivider()
    }
}

@Composable
private fun RuleRow(
    rule: CommandRule,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (rule.blocks) Icons.Default.Block else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (rule.blocks) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = rule.words ?: stringResource(R.string.command_rules_whole_tool, rule.toolName),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (rule.words != null) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onForget) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.cd_forget_command_rule),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
