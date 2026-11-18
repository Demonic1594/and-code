package com.yugahashimoto.andcode.feature.chat

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.delay

/** Wait after the last keystroke before asking GitHub about a link, so typing one is a single lookup. */
private const val ISSUE_LOOKUP_DEBOUNCE_MS = 500L

/**
 * The issues linked in [draft] that GitHub answered for, in the order they are linked. An issue
 * GitHub answered for is asked once per screen. One it did not (offline, say) is asked again the next
 * time the links in the draft change, pasted again for instance, never on every keystroke.
 */
@Composable
internal fun rememberLinkedIssues(
    draft: String,
    lookup: (suspend (IssueRef) -> IssueThread?)?,
): List<IssueThread> {
    val threads = remember { mutableStateMapOf<IssueRef, IssueThread>() }
    val asked = remember { mutableSetOf<IssueRef>() }
    val links = remember(draft) { if (lookup == null) emptyList() else IssuePrompt.links(draft) }
    LaunchedEffect(links) {
        if (lookup == null || links.all { it in asked }) return@LaunchedEffect
        delay(ISSUE_LOOKUP_DEBOUNCE_MS)
        // Marked asked only once answered: a lookup cut short by the next edit, or one that failed,
        // is asked again.
        links.filterNot(asked::contains).forEach { ref ->
            val thread = lookup(ref) ?: return@forEach
            asked += ref
            threads[ref] = thread
        }
    }
    return links.mapNotNull(threads::get)
}

/** The inserted issue's fixed wording from the string resources, in the language [context] shows. */
internal fun issuePromptLabels(context: Context): IssuePromptLabels {
    val res = context.resources
    return IssuePromptLabels(
        header = { res.getString(R.string.chat_issue_prompt_header, it) },
        guard = res.getString(R.string.chat_issue_prompt_guard),
        meta = { number, state, author -> res.getString(R.string.chat_issue_prompt_meta, number, state, author) },
        open = res.getString(R.string.chat_issue_prompt_state_open),
        closed = res.getString(R.string.chat_issue_prompt_state_closed),
        labels = { res.getString(R.string.chat_issue_prompt_labels, it) },
        noDescription = res.getString(R.string.chat_issue_prompt_no_description),
        comments = res.getString(R.string.chat_issue_prompt_comments),
        commentsLatest = { shown, skipped ->
            res.getQuantityString(R.plurals.chat_issue_prompt_comments_latest, skipped, shown, skipped)
        },
        commentsPartial = { shown, read -> res.getString(R.string.chat_issue_prompt_comments_partial, shown, read) },
        cut = { res.getQuantityString(R.plurals.chat_issue_prompt_cut, it, it) },
    )
}

/** One chip per linked issue; tapping it puts the issue's text in place of its link. */
@Composable
internal fun LinkedIssueChips(
    issues: List<IssueThread>,
    onUse: (IssueThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (issues.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        issues.forEach { issue ->
            SuggestionChip(
                onClick = { onUse(issue) },
                icon = { Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = {
                    Text(
                        stringResource(R.string.chat_use_issue, issue.ref.number, issue.title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }
    }
}
