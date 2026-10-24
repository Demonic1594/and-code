package com.yugahashimoto.andcode.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper

/** Where a launcher shortcut (long-press on the app icon) takes the user. */
enum class ShortcutDestination(val id: String) {
    NEW_CHAT("new_chat"),
    WORKSPACES("workspaces"),
    SCHEDULES("schedules"),
    ;

    companion object {
        const val EXTRA = "com.yugahashimoto.andcode.extra.SHORTCUT"

        fun fromId(id: String?): ShortcutDestination? = entries.firstOrNull { it.id == id }
    }
}

/** A shortcut tap; [token] makes two taps on the same shortcut distinct, like [ChatDeepLink]. */
data class ShortcutLink(
    val destination: ShortcutDestination,
    val token: Long,
)

/** A pinned chat offered as its own launcher shortcut. */
data class PinnedChatShortcut(
    val sessionId: String,
    val title: String,
    val runtimeId: String?,
)

/**
 * Publishes the launcher shortcuts. Dynamic rather than a static shortcuts.xml: a static shortcut
 * has to name the target package literally, and the debug build installs under a different one
 * (see docs/FORK.md). Re-published on every launch so the labels follow the app language.
 */
object AppShortcuts {
    /**
     * [pinned] chats come first, so a long-press on the icon opens a chat the user keeps coming back
     * to in one tap. Launchers show only a handful of shortcuts, so the list is cut to what this one
     * allows, keeping New chat and at least one destination.
     */
    fun publish(
        context: Context,
        pinned: List<PinnedChatShortcut> = emptyList(),
    ) {
        val fixed =
            listOf(
                shortcut(context, ShortcutDestination.NEW_CHAT, R.string.shortcut_new_chat, R.drawable.ic_shortcut_chat),
                shortcut(context, ShortcutDestination.WORKSPACES, R.string.nav_workspaces, R.drawable.ic_shortcut_folder),
                shortcut(context, ShortcutDestination.SCHEDULES, R.string.nav_schedules, R.drawable.ic_shortcut_schedule),
            )
        val max = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(DEFAULT_MAX_SHORTCUTS)
        val pinnedSlots = (max - MIN_FIXED_SHORTCUTS).coerceIn(0, MAX_PINNED_SHORTCUTS)
        val chats = pinned.take(pinnedSlots).map { chatShortcut(context, it) }
        val shortcuts = (chats + fixed).take(max.coerceAtLeast(MIN_FIXED_SHORTCUTS))
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun chatShortcut(
        context: Context,
        chat: PinnedChatShortcut,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "chat:${chat.sessionId}")
            // An untitled chat falls back to its id's start rather than "New chat", which would read
            // as the shortcut that starts a fresh one.
            .setShortLabel(chat.title.take(SHORT_LABEL_MAX).ifBlank { chat.sessionId.take(SHORT_LABEL_MAX) })
            .setLongLabel(chat.title.take(LONG_LABEL_MAX).ifBlank { chat.sessionId.take(LONG_LABEL_MAX) })
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_pinned))
            .setIntent(
                // The same extras a notification tap carries, so MainActivity's existing deep-link path
                // opens the chat, switching to its runtime first when needed.
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID, chat.sessionId)
                    .apply { chat.runtimeId?.let { putExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()

    private const val DEFAULT_MAX_SHORTCUTS = 4
    private const val MIN_FIXED_SHORTCUTS = 2
    private const val MAX_PINNED_SHORTCUTS = 2
    private const val SHORT_LABEL_MAX = 24
    private const val LONG_LABEL_MAX = 48

    private fun shortcut(
        context: Context,
        destination: ShortcutDestination,
        labelRes: Int,
        iconRes: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, destination.id)
            .setShortLabel(context.getString(labelRes))
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(ShortcutDestination.EXTRA, destination.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()
}
