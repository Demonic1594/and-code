package com.yugahashimoto.andcode.core.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.followUpPrompt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Answers the run notifications' own buttons: Stop on the live notification, and Reply on a
 * "finished" or "failed" notice, which sends the typed text to that chat as its next message
 * without opening the app - the way messaging apps reply from the shade.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val sessionId = intent?.getStringExtra(RuntimeNotificationHelper.EXTRA_SESSION_ID) ?: return
        val runtimeId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID)
        val app = context.applicationContext as? AndCodeApplication ?: return
        when (intent.action) {
            ACTION_STOP -> {
                // As the chat's own Stop does: the app stops treating the chat as running whatever
                // the runtime answers, so a runtime with nothing left to stop cannot leave the live
                // notification stuck.
                app.activityRepository.markSessionAborted(sessionId)
                app.notifications.cancelStalled(sessionId)
                run(app, runtimeId, "stop $sessionId") { backend -> backend.abortSession(sessionId) }
            }
            ACTION_REPLY -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                // A Reply left on a notification posted before the app lock was turned on would send
                // past it. Stop stays: it only ends a run, which is the safe direction.
                if (app.preferences.state.value.biometricLockEnabled) {
                    app.notifications.cancel(notificationId)
                    return
                }
                val text =
                    RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(KEY_REPLY)
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                if (text.isEmpty()) {
                    // Leaving it would keep the system's "sending" spinner on the notice.
                    app.notifications.cancel(notificationId)
                    return
                }
                var sent = false
                run(
                    app,
                    runtimeId,
                    "reply $sessionId",
                    // A timeout that fires after the message went out is not a failed reply;
                    // offering Reply again would only send it twice.
                    onFailure = { if (!sent) app.notifications.notifyReplyFailed(sessionId, notificationId, runtimeId) },
                ) { backend ->
                    backend.sendMessage(sessionId, followUpPrompt(backend, sessionId, text))
                    sent = true
                    app.activityRepository.markSessionRunning(sessionId)
                    // The live notification takes over from here; the answered notice goes.
                    app.notifications.cancel(notificationId)
                }
            }
        }
    }

    private fun run(
        app: AndCodeApplication,
        runtimeId: String?,
        what: String,
        onFailure: () -> Unit = {},
        block: suspend (OpenCodeBackend) -> Unit,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                runCatching {
                    // goAsync() only keeps the process alive briefly, so a hung request must not
                    // outlive it; an exception escaping here would kill the process.
                    withTimeout(TIMEOUT_MS) {
                        val backend =
                            runtimeId
                                ?.let(app.runtimeRegistry::target)
                                ?: app.runtimeRegistry.selected.value
                                ?: error("No runtime is selected")
                        block(backend)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Notification action failed: $what", error)
                    runCatching(onFailure)
                }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        private const val TAG = "NotificationAction"
        private const val TIMEOUT_MS = 8_000L
        const val ACTION_STOP = "com.yugahashimoto.andcode.STOP_RUN"
        const val ACTION_REPLY = "com.yugahashimoto.andcode.REPLY"
        const val KEY_REPLY = "reply_text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        private val handler = CoroutineExceptionHandler { _, error -> Log.e(TAG, "Unhandled notification action failure", error) }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

        fun stopIntent(
            context: Context,
            sessionId: String,
            runtimeId: String?,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                ("stop:$sessionId").hashCode(),
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = ACTION_STOP
                    putExtra(RuntimeNotificationHelper.EXTRA_SESSION_ID, sessionId)
                    runtimeId?.let { putExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID, it) }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Mutable on purpose: the system writes the typed reply into the intent. */
        fun replyIntent(
            context: Context,
            sessionId: String,
            runtimeId: String?,
            notificationId: Int,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                // One per notice, not per chat: a chat's "finished" and "failed" notices can be up
                // together, and a shared intent would carry whichever was posted last.
                notificationId,
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = ACTION_REPLY
                    putExtra(RuntimeNotificationHelper.EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                    runtimeId?.let { putExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID, it) }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
    }
}
