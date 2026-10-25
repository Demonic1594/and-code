package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

/**
 * What an empty chat shows instead of a blank screen: the agent you are about to talk to, one
 * line of what it can do, and a handful of starters that fill the composer when tapped.
 *
 * A starter only fills the composer, it never sends: the prompts are generic on purpose, so you
 * get a chance to add the file or symptom that makes them useful before anything runs.
 */
@Composable
internal fun ChatWelcome(
    agent: LocalAgent?,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions =
        listOf(
            Icons.Outlined.AccountTree to stringResource(R.string.chat_suggest_explain),
            Icons.Outlined.BugReport to stringResource(R.string.chat_suggest_fix),
            Icons.Outlined.Science to stringResource(R.string.chat_suggest_tests),
            Icons.AutoMirrored.Outlined.FactCheck to stringResource(R.string.chat_suggest_review),
        )
    Box(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("chat-welcome"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenEdge, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = AppShapes.lg,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (agent != null) {
                        Icon(painterResource(agent.iconRes), contentDescription = null, modifier = Modifier.size(28.dp))
                    } else {
                        Image(painterResource(R.drawable.andcode_logo), contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.chat_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xxl))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                suggestions.forEach { (icon, text) -> SuggestionRow(icon, text) { onSuggestion(text) } }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
