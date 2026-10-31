package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.Spacing

/**
 * Whether a message laid out in [lineCount] lines fills the composer, so the full-screen editor is
 * offered. The composer's layout reports at most [ComposerLimits.MAX_LINES] lines, so this means "it is full".
 */
internal fun shouldOfferExpandedComposer(lineCount: Int): Boolean = lineCount >= ComposerLimits.MAX_LINES

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
        // The dialog has its own window, which the app theme's status bar setup never reaches: match
        // its bar icons to this surface, or light icons vanish on a light theme.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > ComposerLimits.LIGHT_SURFACE
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize().testTag("chat-expanded-composer"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
                    horizontalArrangement = Arrangement.End,
                ) {
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
                            .focusRequester(focus)
                            .testTag("chat-expanded-input"),
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

/** Sizes shared by the chat composer and its full-screen editor. */
internal object ComposerLimits {
    /** The most lines the chat composer shows before it scrolls. */
    const val MAX_LINES = 4

    /** Above this luminance a surface is light and wants dark system bar icons. */
    const val LIGHT_SURFACE = 0.5f
}
