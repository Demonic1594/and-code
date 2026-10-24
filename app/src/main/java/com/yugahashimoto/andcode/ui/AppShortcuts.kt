package com.yugahashimoto.andcode.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R

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

/**
 * Publishes the launcher shortcuts. Dynamic rather than a static shortcuts.xml: a static shortcut
 * has to name the target package literally, and the debug build installs under a different one
 * (see docs/FORK.md). Re-published on every launch so the labels follow the app language.
 */
object AppShortcuts {
    fun publish(context: Context) {
        val shortcuts =
            listOf(
                shortcut(context, ShortcutDestination.NEW_CHAT, R.string.shortcut_new_chat, R.drawable.ic_shortcut_chat),
                shortcut(context, ShortcutDestination.WORKSPACES, R.string.nav_workspaces, R.drawable.ic_shortcut_folder),
                shortcut(context, ShortcutDestination.SCHEDULES, R.string.nav_schedules, R.drawable.ic_shortcut_schedule),
            )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

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
