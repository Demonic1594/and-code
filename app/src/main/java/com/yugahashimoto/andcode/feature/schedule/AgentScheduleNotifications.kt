package com.yugahashimoto.andcode.feature.schedule

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.SessionApproval
import com.yugahashimoto.andcode.ui.ShortcutDestination

/**
 * Posts an [AgentScheduleNotice] as an Android notification on a channel of its own, "Schedules
 * changed by agents", so the user can tune it without silencing the results of their runs.
 *
 * One notification per schedule (tagged with its id), so a later change replaces the earlier notice instead of
 * stacking up. Without the POST_NOTIFICATIONS permission (Android 13+) nothing is posted - the
 * change still happens, and the marker on the schedule in the Schedules screen carries it.
 */
class AgentScheduleNotifications(
    private val context: Context,
    private val runtimes: RuntimeRegistry,
    /** Whether the app lock is on; then the lock screen shows that something happened, not what. */
    private val appLocked: () -> Boolean,
) : ScheduleChangeNotifier {
    private val manager = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    override fun notify(notice: AgentScheduleNotice): Boolean {
        if (!canShow()) return false
        val schedule = notice.schedule
        val folder = schedule.workspacePath.ifBlank { context.getString(R.string.schedule_workspace_unset) }
        val where = "${runtimeLabel(schedule.runtimeId)} · $folder"
        val notification =
            when (notice.kind) {
                // Deleting only takes work away, so it is told quietly: no sound, no heads-up. It is
                // told at all because it cannot be undone and leaves no schedule to carry a marker.
                AgentScheduleChangeKind.DELETED ->
                    builder(schedule.id, openSchedule = false)
                        .setContentTitle(context.getString(R.string.notification_schedule_agent_deleted_title, schedule.displayName))
                        .setContentText(where)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                AgentScheduleChangeKind.CREATED, AgentScheduleChangeKind.CHANGED -> {
                    val title =
                        if (notice.kind == AgentScheduleChangeKind.CREATED) {
                            R.string.notification_schedule_agent_created_title
                        } else {
                            R.string.notification_schedule_agent_changed_title
                        }
                    val timing =
                        when {
                            !schedule.enabled -> context.getString(R.string.notification_schedule_agent_off)
                            notice.nextRunAt != null ->
                                context.getString(R.string.schedule_next_run, dateTimeLabel(context, notice.nextRunAt.toEpochMilli()))
                            else -> context.getString(R.string.notification_schedule_agent_no_next_run)
                        }
                    val warning =
                        when (notice.approval) {
                            SessionApproval.NEVER_ASKS -> context.getString(R.string.notification_schedule_agent_will_not_ask)
                            SessionApproval.EDITS_AND_COMMANDS_PREAPPROVED ->
                                context.getString(R.string.notification_schedule_agent_edits_unasked)
                            SessionApproval.ASKS -> null
                        }
                    builder(schedule.id, openSchedule = true)
                        .setContentTitle(context.getString(title, schedule.displayName))
                        // Collapsed, the warning is what matters most; expanded, everything is there.
                        .setContentText(warning ?: where)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(where, timing, warning).joinToString("\n")))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                }
            }
        return post(schedule.id, notification)
    }

    /** POST_NOTIFICATIONS granted (Android 13+), notifications on for the app, and this channel not blocked. */
    private fun canShow(): Boolean {
        if (!RuntimeNotificationHelper.canPost(context) || !manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun builder(
        scheduleId: String,
        openSchedule: Boolean,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent(scheduleId, openSchedule))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply { if (appLocked()) setVisibility(NotificationCompat.VISIBILITY_PRIVATE) }

    /**
     * Opens the Schedules screen, at the schedule itself when [openSchedule] - through the same
     * path as the launcher's Schedules shortcut, so MainActivity needs no second way in.
     */
    private fun openIntent(
        scheduleId: String,
        openSchedule: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                // Request codes are a hash and can collide; the identifier keeps two schedules'
                // intents apart (Intent.filterEquals compares it), so one tap never opens the other.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) identifier = scheduleId
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ShortcutDestination.EXTRA, ShortcutDestination.SCHEDULES.id)
                if (openSchedule) putExtra(ShortcutDestination.EXTRA_SCHEDULE_ID, scheduleId)
            }
        return PendingIntent.getActivity(
            context,
            requestCode(scheduleId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The runtime as the Schedules screen names it, or its id when it no longer exists. */
    private fun runtimeLabel(runtimeId: String): String {
        val target = runtimes.target(runtimeId) ?: return runtimeId
        val agent = target.agent ?: return target.displayName
        return context.getString(R.string.local_agent_on_device, context.getString(agent.displayNameRes))
    }

    @SuppressLint("MissingPermission")
    private fun post(
        scheduleId: String,
        builder: NotificationCompat.Builder,
    ): Boolean = runCatching { manager.notify(scheduleId, NOTIFICATION_ID, builder.build()) }.isSuccess

    private fun ensureChannel() {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_schedule_agent_changes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_schedule_agent_changes_description)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "andcode_schedule_agent_changes"

        /**
         * Takes down [scheduleId]'s notice once the user has opened the schedule: it has done its
         * job, like the marker cleared at the same moment.
         */
        fun cancel(
            context: Context,
            scheduleId: String,
        ) {
            runCatching { NotificationManagerCompat.from(context).cancel(scheduleId, NOTIFICATION_ID) }
        }

        /** Only a PendingIntent request code: a hash, so two schedules can share one. */
        private fun requestCode(scheduleId: String): Int = NOTIFICATION_ID + (scheduleId.hashCode() and REQUEST_CODE_MASK)

        // Notices are told apart by their tag, the full schedule id, so they all share one id. It is
        // clear of RuntimeNotificationHelper's ranges (1M, 3M and 5M, each about a million wide) and
        // of LiveRunNotifier's 4_900_001.
        private const val NOTIFICATION_ID = 7_000_000
        private const val REQUEST_CODE_MASK = 0x000FFFFF
    }
}
