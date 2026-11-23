package com.yugahashimoto.andcode.feature.browser

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.AppNotificationChannels
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.ui.ShortcutDestination
import com.yugahashimoto.andcode.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** The notice in the chat while the hidden browser is in use, with what its buttons do. */
internal class AgentBrowsingBannerState(
    val host: String,
    val onView: () -> Unit,
    val onClose: () -> Unit,
)

/** Provided by the app while the hidden browser is in use; the chat shows it under its top bar. */
internal val LocalAgentBrowsing = compositionLocalOf<AgentBrowsingBannerState?> { null }

/** "Agent is browsing <host>", with View (the page on screen, as the agent left it) and close. */
@Composable
internal fun AgentBrowsingBanner(state: AgentBrowsingBannerState) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = Spacing.hairline),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.agent_browsing_notice, state.host),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = state.onView) { Text(stringResource(R.string.agent_browsing_view)) }
            IconButton(onClick = state.onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.agent_browsing_close),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The same notice as a notification while the app is in the background, on a quiet channel of its
 * own: "Agent is browsing <host>", with View, which opens the page in the Guest Browser, and Close.
 * It goes away as soon as the hidden browser closes or the app is back on screen, where the chat
 * shows the notice instead.
 */
internal class AgentBrowsingNotification(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    suspend fun run(
        state: StateFlow<AgentBrowserState>,
        foreground: StateFlow<Boolean>,
    ) {
        combine(state, foreground) { browser, onScreen -> browser.browsingNotice(onScreen)?.takeIf { it.inBackground }?.host }
            .distinctUntilChanged()
            .collect { host -> withContext(Dispatchers.Main) { if (host == null) cancel() else post(host) } }
    }

    @SuppressLint("MissingPermission")
    private fun post(host: String) {
        if (!RuntimeNotificationHelper.canPost(context) || !manager.areNotificationsEnabled()) return
        AppNotificationChannels.ensure(context, CHANNEL)
        // In the language picked now: the Application context keeps the one the process started in.
        val text = AppLanguage.current(context)
        val view = viewIntent()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL.id)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.getString(R.string.agent_browsing_notice, host))
                .setContentText(text.getString(R.string.agent_browsing_notification_text))
                .setContentIntent(view)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, text.getString(R.string.agent_browsing_view), view)
                .addAction(0, text.getString(R.string.agent_browsing_close), closeIntent())
                .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancel() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /** Through the same way in as the launcher shortcuts, so MainActivity needs no second one. */
    private fun viewIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ShortcutDestination.EXTRA, ShortcutDestination.AGENT_BROWSER.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun closeIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, AgentBrowserCloseReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        val CHANNEL = AppNotificationChannels.AGENT_BROWSING
        const val NOTIFICATION_ID = 0x4252
    }
}

/** The notification's Close: closes the hidden browser, as the chat notice's close does. */
class AgentBrowserCloseReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as? AndCodeApplication ?: return
        app.agentBrowser.closeByUser()
    }
}
