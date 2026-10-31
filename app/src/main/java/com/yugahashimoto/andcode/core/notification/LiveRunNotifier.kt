package com.yugahashimoto.andcode.core.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.repository.RuntimeActivityState
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** One chat that is running right now, as the live notification shows it. */
data class LiveRun(
    val sessionId: String,
    val runtimeId: String?,
    val title: String,
    val agentName: String?,
    val step: String?,
    val startedAt: Long,
)

/** The text of the live notification, built apart from Android so it can be unit-tested. */
data class LiveRunContent(
    val title: String,
    val text: String,
    val lines: List<String>,
    /** The one chat the Stop and open actions act on; null when several are running. */
    val single: LiveRun?,
    /** The earliest start, for the notification's running clock. */
    val since: Long,
)

/** Strings the content needs, passed in so the builder stays free of Android resources. */
data class LiveRunLabels(
    val untitled: String,
    val working: String,
    val manyTitle: (count: Int) -> String,
)

/**
 * Builds the live notification for [runs], or null when nothing is running.
 *
 * One chat reads like a desktop agent's status line: its title, then the step in flight
 * ("bash · ./gradlew test") or a plain "Working…". Several chats collapse into one notification
 * listing each as "title — step", oldest first, so a phone running three agents shows one row in
 * the shade rather than three.
 */
fun liveRunContent(
    runs: List<LiveRun>,
    labels: LiveRunLabels,
): LiveRunContent? {
    if (runs.isEmpty()) return null
    val ordered = runs.sortedBy { it.startedAt }

    fun titleOf(run: LiveRun) = run.title.ifBlank { labels.untitled }
    val since = ordered.first().startedAt
    if (ordered.size == 1) {
        val run = ordered.first()
        val text = listOfNotNull(run.agentName, run.step ?: labels.working).joinToString(" · ")
        return LiveRunContent(title = titleOf(run), text = text, lines = emptyList(), single = run, since = since)
    }
    val lines = ordered.map { run -> "${titleOf(run)} — ${run.step ?: labels.working}" }
    return LiveRunContent(
        title = labels.manyTitle(ordered.size),
        text = ordered.joinToString(", ") { titleOf(it) },
        lines = lines,
        single = null,
        since = since,
    )
}

/**
 * Keeps one ongoing, silent notification up while any chat is running, the phone's stand-in for
 * the live activities of desktop and iOS agent clients: you can leave the app and still see that
 * the run is alive, what it is doing and for how long, and stop it from the shade.
 *
 * It lives on its own low-importance channel, so it never buzzes and can be switched off in the
 * system settings without losing the approval and "finished" notices.
 */
class LiveRunNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val activity: StateFlow<RuntimeActivityState>,
    private val sessions: StateFlow<List<RuntimeSessionRef>>,
    private val agentName: (RuntimeSessionRef?) -> String?,
    /** Whether a session is a subagent's, known from the runtime's events before the catalog refreshes. */
    private val isSubagent: (String) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val startedAt = mutableMapOf<String, Long>()

    fun start() {
        scope.launch {
            combine(activity, sessions) { state, refs -> runsFrom(state, refs) }
                .distinctUntilChanged()
                .collect(::render)
        }
    }

    private fun runsFrom(
        state: RuntimeActivityState,
        refs: List<RuntimeSessionRef>,
    ): List<LiveRun> {
        val active = state.activeSessionIds
        startedAt.keys.retainAll(active)
        // A subagent's session is active while its parent works; the parent already stands for it.
        return active.mapNotNull { id ->
            val ref = refs.firstOrNull { it.session.id == id }
            if (ref?.session?.parentId != null || isSubagent(id)) return@mapNotNull null
            LiveRun(
                sessionId = id,
                runtimeId = ref?.runtimeId,
                title = ref?.session?.title.orEmpty(),
                agentName = agentName(ref),
                step = state.currentSteps[id],
                startedAt = startedAt.getOrPut(id) { now() },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun render(runs: List<LiveRun>) {
        val content =
            liveRunContent(
                runs,
                LiveRunLabels(
                    untitled = context.getString(R.string.new_chat),
                    working = context.getString(R.string.notification_live_working),
                    manyTitle = { count -> context.getString(R.string.notification_live_many, count) },
                ),
            )
        if (content == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!RuntimeNotificationHelper.canPost(context)) return
        val single = content.single
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    if (single != null) {
                        putExtra(RuntimeNotificationHelper.EXTRA_OPEN_CHAT, true)
                        putExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID, single.sessionId)
                        single.runtimeId?.let { putExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID, it) }
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, RuntimeNotificationHelper.CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setUsesChronometer(true)
                .setWhen(content.since)
                .setShowWhen(true)
                .setProgress(0, 0, true)
        // With the app lock on, the lock screen shows that something runs, not the chat or its step.
        if ((context.applicationContext as? AndCodeApplication)?.preferences?.state?.value?.biometricLockEnabled == true) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        }
        if (content.lines.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) },
            )
        }
        if (single != null) {
            builder.addAction(
                0,
                context.getString(R.string.stop_run),
                NotificationActionReceiver.stopIntent(context, single.sessionId, single.runtimeId),
            )
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    companion object {
        private const val NOTIFICATION_ID = 4_900_001
    }
}
