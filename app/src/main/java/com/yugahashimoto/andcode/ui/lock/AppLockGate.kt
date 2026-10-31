package com.yugahashimoto.andcode.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.yugahashimoto.andcode.R

/**
 * Runs a send once the user has unlocked, when the setting asks for it; otherwise at once. Provided
 * by the activity, used by the composer.
 */
val LocalSendGate = staticCompositionLocalOf<(send: () -> Unit) -> Unit> { { send -> send() } }

/**
 * Covers [content] while [locked], and asks for the device unlock straight away. The content stays
 * composed underneath (so where the user was survives), but cannot be touched or read by a screen
 * reader until unlocked.
 */
@Composable
fun AppLockGate(
    locked: Boolean,
    onUnlock: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val title = stringResource(R.string.app_lock_prompt_title)
    val ask = {
        // A device whose lock was removed since the setting was turned on cannot be asked; locking
        // the user out of their own app would be worse than letting them in.
        if (activity == null || !DeviceUnlock.isAvailable(context)) {
            onUnlock()
        } else {
            DeviceUnlock.ask(activity, title, null) { unlocked -> if (unlocked) onUnlock() }
        }
    }
    LaunchedEffect(locked) { if (locked) ask() }
    Box(Modifier.fillMaxSize()) {
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
        if (locked) LockedScreen(onUnlock = ask)
    }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Swallows every touch, so nothing underneath can be reached.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.app_lock_locked_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onUnlock) { Text(stringResource(R.string.app_lock_unlock)) }
        }
    }
}
