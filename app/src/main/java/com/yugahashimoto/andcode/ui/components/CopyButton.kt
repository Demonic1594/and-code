package com.yugahashimoto.andcode.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.delay

/**
 * Copies [text] and shows that it did: the icon turns into a check mark for [feedbackMillis],
 * and its description changes to "Copied", which TalkBack announces. [text] is read at tap time, so
 * a caller can pass content that is still streaming in.
 */
@Composable
fun CopyButton(
    text: () -> String,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.action_copy),
    iconSize: Dp = 18.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onCopied: () -> Unit = {},
    feedbackMillis: Long = 2_000L,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    // A counter, not a flag: a second tap while the check shows restarts its timer.
    var copies by remember { mutableIntStateOf(0) }
    var showingCheck by remember { mutableIntStateOf(0) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        showingCheck = copies
        delay(feedbackMillis)
        showingCheck = 0
    }
    val copied = showingCheck != 0
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text()))
            haptics.perform(HapticEvent.TICK)
            copies++
            onCopied()
        },
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = if (copied) stringResource(R.string.action_copied) else contentDescription,
            tint = if (copied) MaterialTheme.colorScheme.primary else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
