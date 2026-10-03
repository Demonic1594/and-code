package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnectionStatus {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    DISCONNECTED,
    ;

    companion object {
        fun fromLatency(latencyMs: Long): ConnectionStatus =
            when {
                latencyMs < 100L -> EXCELLENT
                latencyMs < 300L -> GOOD
                latencyMs < 1000L -> FAIR
                else -> POOR
            }
    }
}

data class ConnectionQuality(
    val latencyMs: Long = 0L,
    val tokensPerSecond: Double = 0.0,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
)

class ConnectionQualityMonitor(
    private val scope: CoroutineScope,
    private val smoothingFactor: Double = DEFAULT_SMOOTHING_FACTOR,
    /**
     * Suspends until the app is back in the foreground. The default no-op keeps every existing
     * test running unattended forever, the way a virtual test clock expects; the real app wires in
     * a wait on [com.yugahashimoto.andcode.core.lifecycle.AppForeground], so a backgrounded chat
     * stops spending battery on a health check nobody can see the result of.
     */
    private val awaitForeground: suspend () -> Unit = {},
    /**
     * Timebase for the token-rate window. The event stream coalesces consecutive deltas into
     * single events carrying a chunk count; tests inject a controllable clock because the window
     * spans a full second of real time.
     */
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val _quality = MutableStateFlow(ConnectionQuality())
    val quality: StateFlow<ConnectionQuality> = _quality.asStateFlow()

    private val lock = Any()
    private var smoothedLatencyMs: Double = 0.0
    private var hasLatencySample = false
    private var smoothedTokensPerSecond: Double = 0.0
    private var hasTokenSample = false
    private var streamTokenCount = 0
    private var streamWindowStartNanos = 0L
    private var lastTokenNanos = 0L

    fun startMonitoring(healthCheck: suspend () -> Unit) {
        scope.launch {
            while (isActive) {
                awaitForeground()
                probe(healthCheck)
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    fun recordStreamToken() {
        recordStreamTokens(1)
    }

    /**
     * Records [count] chunks of streamed output at once. The event stream coalesces consecutive
     * deltas for a part into single events; counting them as one would understate the rate by
     * the coalescing factor, so the merged event reports how many it folds together.
     *
     * The window only spans time the stream was actually delivering tokens. A pause longer than
     * [TOKEN_RATE_SILENCE_SECONDS] - a tool call mid-turn, the gap between two turns - closes
     * the window on the span it was active for; counting the silence in the denominator is what
     * used to read 10-100x low for the whole EMA tail after every pause.
     */
    fun recordStreamTokens(count: Int) {
        if (count <= 0) return
        val nowNanos = clockNanos()
        synchronized(lock) {
            if (streamTokenCount > 0 && (nowNanos - lastTokenNanos).toDouble() > TOKEN_RATE_SILENCE_SECONDS * NANOS_PER_SECOND) {
                closeWindowLocked(endNanos = lastTokenNanos)
            }
            if (streamTokenCount == 0) {
                streamWindowStartNanos = nowNanos
                lastTokenNanos = nowNanos
                streamTokenCount = count
                return
            }
            lastTokenNanos = nowNanos
            streamTokenCount += count
            val elapsedSeconds = (nowNanos - streamWindowStartNanos) / NANOS_PER_SECOND
            if (elapsedSeconds < TOKEN_RATE_WINDOW_SECONDS) return
            publishRateLocked(streamTokenCount / elapsedSeconds)
            streamTokenCount = 0
            streamWindowStartNanos = nowNanos
        }
    }

    /**
     * Publishes and resets any partially-filled window. Called when a run ends: most replies
     * stream for well under the window, and without a flush their chunks would sit unpublished
     * until the next turn's first delta - or never, on a session's last turn.
     */
    fun flushStreamRate() {
        synchronized(lock) { closeWindowLocked(endNanos = lastTokenNanos) }
    }

    /** Closes the current window against [endNanos], publishing only spans long enough to rate. */
    private fun closeWindowLocked(endNanos: Long) {
        val elapsedSeconds = (endNanos - streamWindowStartNanos) / NANOS_PER_SECOND
        if (streamTokenCount > 0 && elapsedSeconds >= TOKEN_RATE_MIN_SPAN_SECONDS) {
            publishRateLocked(streamTokenCount / elapsedSeconds)
        }
        streamTokenCount = 0
        streamWindowStartNanos = 0L
        lastTokenNanos = 0L
    }

    private fun publishRateLocked(instantaneousRate: Double) {
        smoothedTokensPerSecond =
            if (!hasTokenSample) {
                hasTokenSample = true
                instantaneousRate
            } else {
                smoothingFactor * instantaneousRate + (1.0 - smoothingFactor) * smoothedTokensPerSecond
            }
        _quality.update { it.copy(tokensPerSecond = smoothedTokensPerSecond) }
    }

    private suspend fun probe(healthCheck: suspend () -> Unit) {
        var succeeded = false
        val startNanos = clockNanos()
        try {
            healthCheck()
            succeeded = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            succeeded = false
        }
        // Elapsed from the same (monotonic, injectable) timebase the token window uses. A wall
        // clock could step mid-probe - NTP on a phone does - and a negative sample read as
        // EXCELLENT straight into the latency EMA.
        val elapsedMs = ((clockNanos() - startNanos) / NANOS_PER_MILLI).toLong()
        if (succeeded) {
            recordLatency(elapsedMs)
        } else {
            _quality.update { it.copy(status = ConnectionStatus.DISCONNECTED) }
        }
    }

    private fun recordLatency(latencyMs: Long) {
        synchronized(lock) {
            smoothedLatencyMs =
                if (!hasLatencySample) {
                    hasLatencySample = true
                    latencyMs.toDouble()
                } else {
                    smoothingFactor * latencyMs + (1.0 - smoothingFactor) * smoothedLatencyMs
                }
            val roundedLatency = smoothedLatencyMs.toLong()
            _quality.update {
                it.copy(
                    latencyMs = roundedLatency,
                    status = ConnectionStatus.fromLatency(roundedLatency),
                )
            }
        }
    }

    companion object {
        private const val MONITOR_INTERVAL_MS = 30_000L
        private const val DEFAULT_SMOOTHING_FACTOR = 0.3
        private const val TOKEN_RATE_WINDOW_SECONDS = 1.0

        /**
         * A gap longer than this between two streamed chunks means the window's subject went
         * quiet - tool call, turn boundary. It must sit above [TOKEN_RATE_WINDOW_SECONDS] so a
         * chunk that merely closes a window late (the boundary case the unit test pins) still
         * lands in it, and far below the pauses it exists to ignore.
         */
        private const val TOKEN_RATE_SILENCE_SECONDS = 2.0

        /** Spans shorter than this carry too little signal to publish a rate for. */
        private const val TOKEN_RATE_MIN_SPAN_SECONDS = 0.1

        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
