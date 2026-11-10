package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.repository.ChatAction
import com.yugahashimoto.andcode.data.repository.ChatActionReport
import com.yugahashimoto.andcode.data.repository.ChatLifecycleRepository

internal fun speechLocaleTag(context: android.content.Context): String {
    val locale =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    return locale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en-US"
}

/** The snackbar text for a finished chat action, or null when it needs none. */
internal fun chatActionMessage(
    context: android.content.Context,
    report: ChatActionReport,
): String? {
    val (action, result) = report
    if (result.failed.isNotEmpty()) {
        val reason =
            when (val raw = result.failed.values.first()) {
                ChatLifecycleRepository.UNSUPPORTED -> context.getString(R.string.chat_action_reason_unsupported)
                ChatLifecycleRepository.NOT_FOUND -> context.getString(R.string.chat_action_reason_not_found)
                else -> raw
            }
        val count = result.failed.size
        return when (action) {
            ChatAction.DELETE -> context.getString(R.string.chat_delete_failed_snackbar, count, reason)
            ChatAction.ARCHIVE -> context.getString(R.string.chat_archive_failed_snackbar, count, reason)
            ChatAction.RESTORE -> context.getString(R.string.chat_restore_failed_snackbar, count, reason)
            // A rename is always one chat, so its message has no count.
            ChatAction.RENAME -> context.getString(R.string.chat_rename_failed_snackbar, reason)
        }
    }
    if (action != ChatAction.RESTORE || result.succeeded.isEmpty()) return null
    return if (result.succeeded.size == 1) {
        context.getString(R.string.chat_restored_snackbar)
    } else {
        context.resources.getQuantityString(R.plurals.chats_restored_snackbar, result.succeeded.size, result.succeeded.size)
    }
}

internal fun relativeTimeLabel(
    context: android.content.Context,
    epochMillis: Long,
): String {
    val diffMillis = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    val minutes = diffMillis / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> context.getString(R.string.drawer_time_just_now)
        hours < 1L -> context.resources.getQuantityString(R.plurals.drawer_time_minutes_ago, minutes.toInt(), minutes)
        days < 1L -> context.resources.getQuantityString(R.plurals.drawer_time_hours_ago, hours.toInt(), hours)
        else -> context.resources.getQuantityString(R.plurals.drawer_time_days_ago, days.toInt(), days)
    }
}
