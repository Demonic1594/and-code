package com.yugahashimoto.andcode.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.locale.AppLanguage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One of the app's notification channels, its name and description as resources so it can be named in any language. */
data class ChannelSpec(
    val id: String,
    @StringRes val nameRes: Int,
    val importance: Int,
    @StringRes val descriptionRes: Int? = null,
    val showBadge: Boolean = true,
) {
    /** The channel with its name and description read from [localized]. */
    fun channel(localized: Context): NotificationChannel =
        NotificationChannel(id, localized.getString(nameRes), importance).apply {
            descriptionRes?.let { description = localized.getString(it) }
            setShowBadge(showBadge)
        }
}

/**
 * Every notification channel the app has, and the one way they are created: named in the language
 * picked in AndCode.
 *
 * The system settings list a channel under the name and description it was last created with, so a
 * channel created before the user picked another language would keep the old wording there.
 * Creating it again with the same id renames it and leaves its importance, and whatever the user
 * changed, alone. [ensure] does that before a notifier posts, whenever the language has changed
 * since this process last named the channel; [relabel] does it straight away for every channel the
 * app already has, when the user picks a language.
 */
object AppNotificationChannels {
    val APPROVALS =
        ChannelSpec("opencode_approvals", R.string.notification_channel_approvals, NotificationManager.IMPORTANCE_HIGH)
    val STATUS =
        ChannelSpec("opencode_status", R.string.notification_channel_status, NotificationManager.IMPORTANCE_DEFAULT)
    val LIVE_RUNS =
        ChannelSpec(
            "opencode_live_runs",
            R.string.notification_channel_live,
            NotificationManager.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_live_description,
            showBadge = false,
        )
    val SCHEDULE_AGENT_CHANGES =
        ChannelSpec(
            "andcode_schedule_agent_changes",
            R.string.notification_channel_schedule_agent_changes,
            NotificationManager.IMPORTANCE_DEFAULT,
            descriptionRes = R.string.notification_channel_schedule_agent_changes_description,
        )
    val AGENT_BROWSING =
        ChannelSpec(
            "andcode_agent_browsing",
            R.string.notification_channel_agent_browsing,
            NotificationManager.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_agent_browsing_description,
            showBadge = false,
        )
    val SCHEDULE_RUNS =
        ChannelSpec("andcode_schedule_runs", R.string.notification_channel_schedules, NotificationManager.IMPORTANCE_LOW)
    val LOCAL_RUNTIME =
        ChannelSpec(
            "local_opencode_runtime",
            R.string.local_runtime_screen_title,
            NotificationManager.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_local_runtime_description,
        )
    val SUPERVISED_PROCESSES =
        ChannelSpec("andcode_supervised_processes", R.string.notification_channel_supervised, NotificationManager.IMPORTANCE_LOW)
    val CODEX_KEEP_ALIVE =
        ChannelSpec("andcode_codex_keepalive", R.string.notification_channel_codex, NotificationManager.IMPORTANCE_LOW)
    val WAKE_WORD =
        ChannelSpec("wakeword_channel", R.string.wake_word_notification_channel, NotificationManager.IMPORTANCE_LOW, showBadge = false)

    val all: List<ChannelSpec> =
        listOf(
            APPROVALS,
            STATUS,
            LIVE_RUNS,
            SCHEDULE_AGENT_CHANGES,
            AGENT_BROWSING,
            SCHEDULE_RUNS,
            LOCAL_RUNTIME,
            SUPERVISED_PROCESSES,
            CODEX_KEEP_ALIVE,
            WAKE_WORD,
        )

    private val named = NamedChannels()

    /**
     * Creates [specs]' channels, or renames them, when this process has not named them in the
     * current language yet. Cheap otherwise, so notifiers call it before every post.
     */
    @Synchronized
    fun ensure(
        context: Context,
        vararg specs: ChannelSpec,
    ) {
        val locale = AppLanguage.currentLocale(context)
        val pending = specs.filter { named.claim(it.id, locale) }
        if (pending.isEmpty()) return
        val localized = AppLanguage.contextFor(context, locale)
        val manager = context.getSystemService(NotificationManager::class.java)
        pending.forEach { spec ->
            val created = manager != null && runCatching { manager.createNotificationChannel(spec.channel(localized)) }.isSuccess
            if (!created) named.forget(spec.id)
        }
    }

    /** [ensure] for the channel [channelId] names, when it is one of the app's. */
    fun ensure(
        context: Context,
        channelId: String?,
    ) {
        all.firstOrNull { it.id == channelId }?.let { ensure(context, it) }
    }

    /**
     * Renames every channel the app already has, in the language just picked. Channels the app
     * never created are left out: creating them here would list features the user never used.
     */
    fun relabel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = runCatching { manager.notificationChannels.mapTo(HashSet()) { it.id } }.getOrNull() ?: return
        ensure(context, *existingAmong(all, existing).toTypedArray())
    }

    internal fun existingAmong(
        specs: List<ChannelSpec>,
        existingIds: Set<String>,
    ): List<ChannelSpec> = specs.filter { it.id in existingIds }
}

/**
 * Which locale this process last named each channel in, so a channel is created again (which
 * renames it) only when the language has changed since. It starts empty: a channel an earlier
 * process created may carry another language, so the first post of a process names it once.
 */
internal class NamedChannels {
    private val namedIn = ConcurrentHashMap<String, Locale>()

    /** Whether [id] still has to be named in [locale]; records that it is, so only one caller gets true. */
    fun claim(
        id: String,
        locale: Locale,
    ): Boolean = namedIn.put(id, locale) != locale

    /** Forgets [id] after creating it failed, so the next post tries again. */
    fun forget(id: String) {
        namedIn.remove(id)
    }
}
