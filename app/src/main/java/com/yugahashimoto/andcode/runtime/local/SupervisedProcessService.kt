package com.yugahashimoto.andcode.runtime.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground, and the CPU awake, while [ProcessSupervisor] runs anything.
 *
 * A guest process is a PRoot child of the app: it freezes when the device suspends and is cut off
 * from the network when the app has nothing in the foreground. [LocalRuntimeService] covers this
 * only while OpenCode's runtime is managed; a setup with Claude Code or Codex alone has no such
 * service, so this one exists for exactly as long as a supervised process runs. Its notification
 * names what runs and has Stop all, because a held wake lock costs battery and the user must be able
 * to end it without opening the app.
 */
class SupervisedProcessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    /** The newest start, so stopping never drops one that arrived meanwhile (a restart's). */
    @Volatile private var lastStartId = 0

    private val supervisor: ProcessSupervisor
        get() = (application as AndCodeApplication).processSupervisor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!enterForeground(emptyList())) {
            stopSelf()
            return
        }
        acquireWakeLock()
        scope.launch {
            supervisor.processes.collect { processes ->
                val running = processes.filter { it.isActive }
                if (running.isEmpty()) stopSelf(lastStartId) else notify(running)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP_ALL) {
            // Off the main thread: stopping writes the saved list.
            scope.launch(Dispatchers.IO) { supervisor.stopAll() }
            return START_NOT_STICKY
        }
        // Every startForegroundService() must be answered with startForeground(), even when the
        // last process ended in the meantime; then it stops again at once.
        val running = supervisor.processes.value.filter { it.isActive }
        if (!enterForeground(running) || running.isEmpty()) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndCode:supervised").apply {
                setReferenceCounted(false)
                // Each process ends itself at its own time limit; this cap only guards the lock.
                acquire(WAKE_LOCK_CAP_MILLIS)
            }
    }

    private fun enterForeground(running: List<SupervisedProcess>): Boolean =
        runCatching { startForeground(NOTIFICATION_ID, notification(running)) }
            .onFailure { error -> Log.w(TAG, "Could not enter the foreground", error) }
            .isSuccess

    private fun notify(running: List<SupervisedProcess>) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(running))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_supervised), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(running: List<SupervisedProcess>): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopAll =
            PendingIntent.getService(
                this,
                1,
                Intent(this, SupervisedProcessService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val names = running.joinToString(", ") { process -> process.port?.let { "${process.spec.name} (:$it)" } ?: process.spec.name }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.supervised_notification_title, running.size))
            .setContentText(names.ifEmpty { getString(R.string.supervised_notification_keeping_awake) })
            .setSubText(getString(R.string.supervised_notification_keeping_awake))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.supervised_stop_all), stopAll)
            .build()
    }

    companion object {
        private const val TAG = "SupervisedProcesses"
        private const val CHANNEL_ID = "andcode_supervised_processes"
        private const val NOTIFICATION_ID = 4302
        private const val ACTION_STOP_ALL = "com.yugahashimoto.andcode.supervised.STOP_ALL"
        private const val WAKE_LOCK_CAP_MILLIS = 24L * 60 * 60 * 1000

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SupervisedProcessService::class.java)) }
                .onFailure { error -> Log.w(TAG, "Foreground start refused", error) }
        }
    }
}
