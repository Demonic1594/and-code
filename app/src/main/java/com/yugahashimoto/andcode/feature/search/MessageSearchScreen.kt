package com.yugahashimoto.andcode.feature.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.repository.MessageSearchHit

/**
 * Search across every chat's messages, not just titles. Results stream in as each chat is read,
 * best matches first; tapping one opens the chat with the same text already in its find bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchScreen(
    state: MessageSearchUiState,
    onQueryChange: (String) -> Unit,
    onIncludeArchivedChange: (Boolean) -> Unit,
    onOpen: (MessageSearchHit) -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.query.isEmpty()) runCatching { focusRequester.requestFocus() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.message_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.drawer_search_clear))
                                }
                            }
                        },
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.includeArchived,
                    onClick = { onIncludeArchivedChange(!state.includeArchived) },
                    label = { Text(stringResource(R.string.message_search_include_archived)) },
                )
                Spacer(Modifier.weight(1f))
                if (state.total > 0) {
                    Text(
                        text = stringResource(R.string.message_search_progress, state.searched, state.total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.searching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            when {
                state.query.isBlank() ->
                    SearchMessage(
                        title = stringResource(R.string.message_search_empty_title),
                        body = stringResource(R.string.message_search_empty_body),
                    )
                state.hits.isEmpty() && !state.searching ->
                    SearchMessage(
                        title = stringResource(R.string.message_search_no_results_title),
                        body = stringResource(R.string.message_search_no_results_body),
                    )
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.hits, key = { "${it.ref.runtimeId}/${it.ref.session.id}" }) { hit ->
                            SearchHitRow(hit = hit, query = state.query.trim(), onClick = { onOpen(hit) })
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}

@Composable
private fun SearchMessage(
    title: String,
    body: String,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: MessageSearchHit,
    query: String,
    onClick: () -> Unit,
) {
    val highlight = SpanStyle(fontWeight = FontWeight.SemiBold, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hit.ref.agent?.let { agent ->
                Icon(
                    painter = painterResource(agent.iconRes),
                    contentDescription = stringResource(agent.displayNameRes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = highlighted(hit.ref.session.title.ifBlank { hit.ref.session.id }, query, highlight),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hit.messageMatches > 0) {
                Text(
                    text = stringResource(R.string.message_search_match_count, hit.messageMatches),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hit.snippet.isNotEmpty()) {
            Text(
                text = highlighted(hit.snippet, query, highlight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** [text] with every case-insensitive occurrence of [query] in [style]. */
internal fun highlighted(
    text: String,
    query: String,
    style: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        if (query.isEmpty()) return@buildAnnotatedString
        var at = text.indexOf(query, ignoreCase = true)
        while (at >= 0) {
            addStyle(style, at, at + query.length)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
    }
