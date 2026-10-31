package com.yugahashimoto.andcode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.yugahashimoto.andcode.core.ProjectLinks
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.feature.assistant.AndCodeVoiceInteractionService
import com.yugahashimoto.andcode.feature.assistant.AssistantStatus
import com.yugahashimoto.andcode.feature.support.GitHubStarPromptDialog
import com.yugahashimoto.andcode.feature.support.openProjectLink
import com.yugahashimoto.andcode.ui.AndCodeApp
import com.yugahashimoto.andcode.ui.ChatDeepLink
import com.yugahashimoto.andcode.ui.ShortcutDestination
import com.yugahashimoto.andcode.ui.ShortcutLink
import com.yugahashimoto.andcode.ui.lock.AppLockGate
import com.yugahashimoto.andcode.ui.lock.DeviceUnlock
import com.yugahashimoto.andcode.ui.lock.LocalSendGate
import java.util.UUID

// A FragmentActivity (still a ComponentActivity) because the system unlock prompt needs one.
class MainActivity : FragmentActivity() {
    private var chatDeepLink by mutableStateOf<ChatDeepLink?>(null)
    private var shortcutLink by mutableStateOf<ShortcutLink?>(null)
    private var deepLinkToken = 0L
    private var showInitialStarPrompt by mutableStateOf(false)
    private var assistantActive by mutableStateOf(false)

    private val app: AndCodeApplication
        get() = application as AndCodeApplication

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        assistantActive = AssistantStatus.isActive(this)
        showInitialStarPrompt = app.githubStarCoordinator.shouldShowInitialPrompt()
        app.githubStarCoordinator.refresh()

        setContent {
            val snapshot by app.githubStarCoordinator.snapshot.collectAsState()
            val secondPromptRequested by app.githubStarCoordinator.secondPromptRequested.collectAsState()
            val thankYouRequested by app.githubStarCoordinator.thankYouRequested.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val thankYouMessage = stringResource(R.string.github_star_thanks_snackbar)
            val locked by app.appLock.locked.collectAsState()
            val sendGate = rememberSendGate()
            LaunchedEffect(thankYouRequested) {
                if (thankYouRequested) {
                    snackbarHostState.showSnackbar(thankYouMessage)
                    app.githubStarCoordinator.markThankYouShown()
                }
            }

            LaunchedEffect(secondPromptRequested) {
                if (secondPromptRequested) {
                    app.githubStarCoordinator.markSecondPromptPresented()
                }
            }

            AppLockGate(locked = locked, onUnlock = app.appLock::unlock) {
                CompositionLocalProvider(LocalSendGate provides sendGate) {
                    Box {
                        AndCodeApp(
                            onOpenAssistantSettings = ::openAssistantSettings,
                            assistantActive = assistantActive,
                            chatDeepLink = chatDeepLink,
                            onChatDeepLinkConsumed = { chatDeepLink = null },
                            shortcutLink = shortcutLink,
                            onShortcutLinkConsumed = { shortcutLink = null },
                            snackbarHostState = snackbarHostState,
                        )
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                        )
                    }
                }
            }

            if (!locked && showInitialStarPrompt) {
                GitHubStarPromptDialog(
                    starCount = snapshot.stargazersCount,
                    secondPrompt = false,
                    onStar = {
                        app.githubStarCoordinator.markInitialStarOpened()
                        showInitialStarPrompt = false
                        openProjectLink(this@MainActivity, ProjectLinks.GITHUB_REPOSITORY)
                    },
                    onLater = {
                        app.githubStarCoordinator.markInitialDeferred()
                        showInitialStarPrompt = false
                    },
                )
            }

            if (!locked && secondPromptRequested) {
                GitHubStarPromptDialog(
                    starCount = snapshot.stargazersCount,
                    secondPrompt = true,
                    onStar = {
                        app.githubStarCoordinator.markSecondStarOpened()
                        openProjectLink(this@MainActivity, ProjectLinks.GITHUB_REPOSITORY)
                    },
                    onLater = app.githubStarCoordinator::dismissSecondPrompt,
                )
            }
        }
    }

    /**
     * The per-send unlock, when the settings ask for it, and the recents thumbnail hidden while the
     * app lock is on (it would show the chats the lock is meant to hide).
     */
    @Composable
    private fun rememberSendGate(): (() -> Unit) -> Unit {
        val preferences by app.preferences.state.collectAsState()
        val sendPromptTitle = stringResource(R.string.app_lock_send_prompt_title)
        LaunchedEffect(preferences.biometricLockEnabled) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(!preferences.biometricLockEnabled)
            }
        }
        // One lambda for the life of the screen, reading the latest settings: a new one on every
        // preference change would recompose everything below the composition local.
        val latest by rememberUpdatedState(preferences)
        val title by rememberUpdatedState(sendPromptTitle)
        return remember {
            { send ->
                val asks = latest.biometricLockEnabled && latest.biometricLockOnSendEnabled
                if (asks && DeviceUnlock.isAvailable(this)) {
                    DeviceUnlock.ask(this, title, null) { unlocked -> if (unlocked) send() }
                } else {
                    send()
                }
            }
        }
    }

    // The process-wide stop comes about a second late, and a phone locked within that second would
    // count its sleep from waking; the activity's own stop is immediate. A rotation is not leaving.
    override fun onStart() {
        super.onStart()
        app.appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.appLock.onBackground()
    }

    override fun onResume() {
        super.onResume()
        assistantActive = AssistantStatus.isActive(this)
        app.githubStarCoordinator.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent ?: return
        ShortcutDestination.fromId(intent.getStringExtra(ShortcutDestination.EXTRA))?.let { destination ->
            deepLinkToken += 1
            shortcutLink = ShortcutLink(destination, deepLinkToken)
            intent.removeExtra(ShortcutDestination.EXTRA)
            return
        }
        val sessionId =
            intent.getStringExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID)
                ?.takeIf(String::isNotBlank) ?: return
        deepLinkToken += 1
        chatDeepLink =
            ChatDeepLink(
                sessionId = sessionId,
                runtimeId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID),
                token = deepLinkToken,
            )
        // Consume the extras at once: the activity keeps this intent across configuration changes,
        // and re-delivering it would yank the user back to a chat they have already left.
        intent.removeExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID)
        intent.removeExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID)
    }

    private fun openAssistantSettings() {
        if (assistantActive) {
            AndCodeVoiceInteractionService.show(this, UUID.randomUUID().toString())
            return
        }
        val opened =
            listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            ).any { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            }

        if (!opened) {
            Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
    }
}
