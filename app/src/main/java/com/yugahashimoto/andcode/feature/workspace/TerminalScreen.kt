package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.terminal.StyledLine
import com.yugahashimoto.andcode.feature.terminal.TerminalKey
import com.yugahashimoto.andcode.feature.terminal.TerminalStyle
import kotlinx.coroutines.launch

private val TerminalBackground = Color(0xFF1A1A2E)
private val AccentColor = Color(0xFF4EC9B0)
private val OutputColor = Color(0xFFD4D4D4)
private val ErrorColor = Color(0xFFF44747)
private val SystemColor = Color(0xFFDCDCAA)
private val OutputPadding = 8.dp
private val OutputTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onOpen: (columns: Int, rows: Int) -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
) {
    val running = state.status == TerminalStatus.RUNNING
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(TerminalBackground)
                .navigationBarsPadding()
                .imePadding(),
    ) {
        TerminalToolbar(state = state, onRestart = onRestart, onClear = onClear)
        TerminalOutput(state = state, onOpen = onOpen, modifier = Modifier.weight(1f))
        TerminalKeyRow(enabled = running, onKey = onKey)
        TerminalInput(
            value = state.currentInput,
            enabled = running,
            onValueChange = onInputChange,
            onSubmit = onSubmit,
        )
    }
}

@Composable
private fun TerminalToolbar(
    state: TerminalUiState,
    onRestart: () -> Unit,
    onClear: () -> Unit,
) {
    val message =
        when (state.status) {
            TerminalStatus.IDLE, TerminalStatus.STARTING -> stringResource(R.string.terminal_starting)
            TerminalStatus.RUNNING -> null
            TerminalStatus.EXITED -> stringResource(R.string.terminal_session_ended, state.exitCode ?: 0)
            TerminalStatus.FAILED ->
                when (state.failure) {
                    TerminalFailure.NOT_INSTALLED -> stringResource(R.string.terminal_not_installed)
                    TerminalFailure.START_FAILED, null ->
                        listOfNotNull(stringResource(R.string.terminal_start_failed), state.failureDetail).joinToString(" ")
                }
        }
    val canRestart = state.status == TerminalStatus.EXITED || state.status == TerminalStatus.FAILED
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = OutputPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.orEmpty(),
            modifier = Modifier.weight(1f),
            color = if (state.status == TerminalStatus.FAILED) ErrorColor else SystemColor,
            style = OutputTextStyle,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (canRestart) {
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.terminal_restart), color = AccentColor)
            }
        }
        IconButton(onClick = onClear) {
            Icon(
                Icons.Default.DeleteSweep,
                contentDescription = stringResource(R.string.cd_clear_terminal),
                tint = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TerminalOutput(
    state: TerminalUiState,
    onOpen: (columns: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember(density) { measurer.measure("M", OutputTextStyle).size }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val horizontalPaddingPx = with(density) { OutputPadding.toPx() } * 2
        // The shell is told this size once, when it starts, so what it wraps lines to matches the
        // number of characters that fit across the screen.
        val columns = ((constraints.maxWidth - horizontalPaddingPx) / cell.width).toInt()
        val rows = constraints.maxHeight / cell.height
        LaunchedEffect(columns, rows) {
            if (columns > 0 && rows > 0) onOpen(columns, rows)
        }

        // Follow the output until the reader scrolls away from the end, and again once they scroll
        // back to it. Only a drag changes this: the viewport also shrinks when the keyboard opens, and
        // new lines arrive below the fold before they are scrolled to, and neither of those means the
        // reader left the end - judging by "can scroll forward" at those moments would stop following
        // exactly when the keyboard comes up to type the next command.
        var followOutput by remember { mutableStateOf(true) }
        LaunchedEffect(listState) {
            var dragging = false
            launch {
                listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragging = true }
            }
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && dragging) {
                    dragging = false
                    followOutput = !listState.canScrollForward
                }
            }
        }
        LaunchedEffect(state.lines, constraints.maxHeight) {
            if (followOutput) listState.scrollToItem(state.lines.lastIndex)
        }

        val cursorRow = state.cursorRow.takeIf { state.status == TerminalStatus.RUNNING }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = OutputPadding),
            state = listState,
        ) {
            itemsIndexed(state.lines) { index, line ->
                Text(
                    text = line.toAnnotatedString(cursorColumn = state.cursorColumn.takeIf { index == cursorRow }),
                    style = OutputTextStyle,
                    color = OutputColor,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun TerminalKeyRow(
    enabled: Boolean,
    onKey: (TerminalKey) -> Unit,
) {
    // Shared out evenly rather than scrolled: Ctrl+C is the key that matters most, and it must never
    // be one swipe away from where the arrows are. "Esc" and "Tab" are key caps, not words - they read
    // the same in every language - so, like "^C", they are not translated; the keys that are drawn as
    // symbols get a spoken description.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
    ) {
        TerminalKeyButton("Esc", null, enabled) { onKey(TerminalKey.ESCAPE) }
        TerminalKeyButton("Tab", null, enabled) { onKey(TerminalKey.TAB) }
        TerminalKeyButton("^C", R.string.cd_terminal_interrupt, enabled) { onKey(TerminalKey.CTRL_C) }
        TerminalKeyButton("^D", R.string.cd_terminal_end_of_input, enabled) { onKey(TerminalKey.CTRL_D) }
        TerminalKeyButton("←", R.string.cd_terminal_key_left, enabled) { onKey(TerminalKey.LEFT) }
        TerminalKeyButton("↑", R.string.cd_terminal_key_up, enabled) { onKey(TerminalKey.UP) }
        TerminalKeyButton("↓", R.string.cd_terminal_key_down, enabled) { onKey(TerminalKey.DOWN) }
        TerminalKeyButton("→", R.string.cd_terminal_key_right, enabled) { onKey(TerminalKey.RIGHT) }
    }
}

@Composable
private fun RowScope.TerminalKeyButton(
    label: String,
    descriptionRes: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = descriptionRes?.let { stringResource(it) }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        modifier =
            Modifier
                .weight(1f)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, color = if (enabled) AccentColor else AccentColor.copy(alpha = 0.4f))
    }
}

@Composable
private fun TerminalInput(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = OutputPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text(stringResource(R.string.terminal_input_hint), color = Color.White.copy(alpha = 0.4f)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Color.White,
                ),
            enabled = enabled,
        )
        IconButton(onClick = onSubmit, enabled = enabled) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.cd_send_command),
                tint = if (enabled) AccentColor else AccentColor.copy(alpha = 0.4f),
            )
        }
    }
}

private fun StyledLine.toAnnotatedString(cursorColumn: Int?): AnnotatedString {
    // An empty line still has to take up a row of the screen, and the cursor may sit past the text.
    val shown = text.padEnd(maxOf(1, (cursorColumn ?: -1) + 1))
    return buildAnnotatedString {
        append(shown)
        spans.forEach { addStyle(it.style.toSpanStyle(), it.start, it.end) }
        if (cursorColumn != null) {
            addStyle(SpanStyle(color = TerminalBackground, background = OutputColor), cursorColumn, cursorColumn + 1)
        }
    }
}

private fun TerminalStyle.toSpanStyle(): SpanStyle {
    val foreground = foreground?.let(::Color)
    val background = background?.let(::Color)
    // Inverse swaps the two, falling back to the screen's own colours for whichever was left default.
    val textColor = if (inverse) background ?: TerminalBackground else foreground
    val backColor = if (inverse) foreground ?: OutputColor else background
    return SpanStyle(
        color = textColor ?: Color.Unspecified,
        background = backColor ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}
