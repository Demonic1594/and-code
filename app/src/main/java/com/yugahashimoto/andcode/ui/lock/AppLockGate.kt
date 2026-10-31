package com.yugahashimoto.andcode.ui.lock

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yugahashimoto.andcode.R

/**
 * Runs a send once the user has unlocked, when the setting asks for it; otherwise at once. Provided
 * by the activity, used by the composer.
 */
val LocalSendGate = staticCompositionLocalOf<(send: () -> Unit) -> Unit> { { send -> send() } }

/**
 * Shows [content] only while unlocked, and asks for the device unlock as soon as it locks and each
 * time the app comes back still locked.
 *
 * While locked the content leaves the composition entirely, so nothing of it can show or act: its
 * dialogs, sheets and popups (separate windows that an overlay would not cover), its back handlers
 * and its keyboard focus all go with it. What it saved with rememberSaveable, the navigation back
 * stack included, is kept and comes back on unlock.
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
    val currentLocked by rememberUpdatedState(locked)
    val currentAsk by rememberUpdatedState(ask)
    LaunchedEffect(locked) { if (locked) ask() }
    // A prompt cancelled by leaving the app is asked again on return, not left to the button.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && currentLocked) currentAsk()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val saved = rememberSaveableStateHolder()
    if (locked) {
        LockedScreen(onUnlock = ask)
    } else {
        saved.SaveableStateProvider("app") { content() }
    }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
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
                modifier = Modifier.semantics { heading() },
            )
            Button(onClick = onUnlock) { Text(stringResource(R.string.app_lock_unlock)) }
        }
    }
}
