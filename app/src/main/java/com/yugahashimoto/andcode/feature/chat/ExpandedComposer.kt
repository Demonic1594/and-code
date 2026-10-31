package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.Spacing

private object ExpandedComposerDefaults {
    /** From this many lines (the composer shows at most four) it offers the message full screen. */
    const val FROM_LINES = 4
}

/** Whether a message laid out in [lineCount] lines is long enough to offer the full-screen editor. */
internal fun shouldOfferExpandedComposer(lineCount: Int): Boolean = lineCount >= ExpandedComposerDefaults.FROM_LINES

/**
 * The message being written, full screen, for a long prompt the four-line composer can't show.
 *
 * It edits the same text as the composer, so collapsing (or back) returns to it with every change
 * kept; sending stays in the composer, where the send rules (queue, interrupt, attachments) live.
 */
@Composable
internal fun ExpandedComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onCollapse: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCollapse,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Surface(
            modifier = Modifier.fillMaxSize().testTag("chat-expanded-composer"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.chat_message_placeholder),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                    )
                    IconButton(onClick = onCollapse, modifier = Modifier.testTag("chat-collapse-composer")) {
                        Icon(Icons.Default.CloseFullscreen, contentDescription = stringResource(R.string.cd_collapse_composer))
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            .focusRequester(focus),
                    textStyle =
                        TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                            textDirection = TextDirection.Content,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
