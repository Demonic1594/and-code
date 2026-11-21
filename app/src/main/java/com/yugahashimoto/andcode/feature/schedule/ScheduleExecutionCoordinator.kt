package com.yugahashimoto.andcode.feature.schedule

import android.os.SystemClock

/** Tracks schedule jobs independently while the shared foreground service is alive. */
internal class ScheduleExecutionCoordinator {
    private val activeSchedules = mutableSetOf<String>()
    private var latestStartId = 0

    @Synchronized
    fun tryStart(
        scheduleId: String,
        startId: Int,
    ): Boolean {
        latestStartId = maxOf(latestStartId, startId)
        return activeSchedules.add(scheduleId)
    }

    /** Returns the newest service start ID when this completion leaves no job running. */
    @Synchronized
    fun finish(scheduleId: String): Int? {
        if (!activeSchedules.remove(scheduleId) || activeSchedules.isNotEmpty()) return null
        return latestStartId
    }
}

/** Why a run's event stream stopped before the run settled. */
internal sealed interface StreamFailure {
    /**
     * It ended without saying why. Worded only when the failure is reported, which can be long
     * after, so the notice is in the language picked by then.
     */
    data object Ended : StreamFailure

    /** It failed with this message, which comes from the runtime and is not translated. */
    data class Error(
        val message: String,
    ) : StreamFailure

    /** The text for the user: [ended] read now for [Ended], the runtime's own message otherwise. */
    fun describe(ended: () -> String): String =
        when (this) {
            Ended -> ended()
            is Error -> message
        }
}

/** Progress and stream state owned by one scheduled session. */
internal class ScheduleExecutionState {
    @Volatile var streamFailure: StreamFailure? = null

    @Volatile var lastProgressAt: Long = 0L

    fun markProgress() {
        lastProgressAt = SystemClock.elapsedRealtime()
    }
}
