package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

/**
 * The text a timeline row can be found by: what the user wrote, the answer's text, reasoning and
 * tool titles inside an activity group, todo items and errors. Images and footers have none.
 */
internal fun searchableText(entry: TimelineEntry): String =
    when (entry) {
        is TimelineEntry.UserMessage -> entry.message.text
        is TimelineEntry.Body -> entry.part.text
        is TimelineEntry.Error -> entry.part.message
        is TimelineEntry.Activity ->
            entry.parts.joinToString("\n") { part ->
                when (part) {
                    is ChatPart.Reasoning -> part.text
                    is ChatPart.Tool -> listOfNotNull(part.name, part.title, part.input, part.output, part.error).joinToString("\n")
                    is ChatPart.Patch -> part.files.joinToString("\n")
                    is ChatPart.Text -> part.text
                    is ChatPart.Error -> part.message
                    is ChatPart.Image -> ""
                }
            }
        is TimelineEntry.Todo -> entry.todos.joinToString("\n") { it.content }
        is TimelineEntry.Changes -> entry.files.joinToString("\n")
        is TimelineEntry.Image -> ""
        is TimelineEntry.Footer -> ""
    }

/**
 * Indices of the timeline rows that contain [query], ignoring case, oldest first. A blank query
 * matches nothing. The whole query is matched as typed, the way a browser's find does, so a phrase
 * finds that phrase.
 */
internal fun chatFindMatches(
    entries: List<TimelineEntry>,
    query: String,
): List<Int> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return entries.indices.filter { searchableText(entries[it]).contains(needle, ignoreCase = true) }
}

/**
 * The match a find bar is on: [index] while it points at one of [matchCount] matches, otherwise
 * the newest match (the chat sits at the bottom), or -1 when there are none.
 */
internal fun resolveFindIndex(
    index: Int,
    matchCount: Int,
): Int = if (index in 0 until matchCount) index else matchCount - 1

/** Replaces the chat's top bar while finding: the query, "3 of 12", older/newer, and close. */
@Composable
internal fun ChatFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    /** Zero-based position of the current match, or -1 when there is none. */
    current: Int,
    total: Int,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.chat_find_open)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onOlder() }),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
            )
            if (query.isNotBlank()) {
                Text(
                    text =
                        if (total == 0) {
                            stringResource(R.string.chat_find_none)
                        } else {
                            stringResource(R.string.chat_find_position, current + 1, total)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (total == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onOlder, enabled = total > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.chat_find_older))
            }
            IconButton(onClick = onNewer, enabled = total > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_find_newer))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_find_close))
            }
        }
    }
}
