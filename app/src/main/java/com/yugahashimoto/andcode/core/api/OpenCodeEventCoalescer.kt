package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coalesces the high-frequency streaming events out of an OpenCode event stream.
 *
 * A streaming reply arrives as dozens of `message.part.updated` / `message.part.delta` events per
 * second, and every one of them rebuilds the chat's message list and recomposes the transcript.
 * The intermediate snapshots are redundant - the server sends the part's full accumulated payload
 * every time, so only the latest matters - and consecutive deltas for one part can be concatenated
 * without changing the accumulated text. Folding both into at most one update per part per window
 * keeps the rendered output identical while cutting the state churn by an order of magnitude.
 *
 * Ordering guarantees:
 *  - events that are not streaming updates (permissions, questions, session status, errors...)
 *    pass through untouched and act as barriers: anything pending is flushed - in arrival order -
 *    before the barrier itself is emitted;
 *  - a `message.updated` that arrived before a part of the same message still flushes before it
 *    (the chat relies on the role it carries to filter out the user's own echoed prompt);
 *  - upstream failure propagates, but only after pending work is flushed.
 *
 * The added latency is bounded by [windowMillis] and applies only while a stream is actively
 * flooding; a quiet stream flushes each lone update after one idle window. A flood that never
 * pauses still flushes every window: the window closes on elapsed time, not on stream silence.
 */
fun Flow<OpenCodeEvent>.coalesceStreamingUpdates(
    windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    clockNanos: () -> Long = System::nanoTime,
): Flow<OpenCodeEvent> =
    channelFlow {
        val input = Channel<OpenCodeEvent>(Channel.UNLIMITED)
        launch {
            try {
                this@coalesceStreamingUpdates.collect { input.send(it) }
                input.close()
            } catch (error: Throwable) {
                input.close(error)
            }
        }

        val pending = LinkedHashMap<String, PendingSlot>()
        // Null while no window is open, so a clock that genuinely reports zero cannot be mistaken
        // for "no window" the way a 0L sentinel would be.
        var windowStartedAtNanos: Long? = null

        suspend fun flush() {
            if (pending.isEmpty()) return
            val slots = ArrayList<PendingSlot>(pending.values)
            pending.clear()
            windowStartedAtNanos = null
            for (slot in slots) slot.emitInto { send(it) }
        }

        while (true) {
            val received: ChannelResult<OpenCodeEvent>? =
                withTimeoutOrNull(windowMillis) { input.receiveCatching() }
            when {
                // Idle window elapsed: hand whatever accumulated to the collector.
                received == null -> flush()
                received.isClosed -> {
                    flush()
                    received.exceptionOrNull()?.let { throw it }
                    break
                }
                else -> {
                    val event = received.getOrThrow()
                    val windowStart = windowStartedAtNanos ?: clockNanos().also { windowStartedAtNanos = it }
                    if (mergeIntoPending(event, pending)) {
                        if (clockNanos() - windowStart >= windowMillis * NANOS_PER_MILLI) flush()
                    } else {
                        // Barrier: preserve ordering by releasing everything that arrived first.
                        flush()
                        send(event)
                    }
                }
            }
        }
    }

/** [coalesceStreamingUpdates]'s default window: fast enough to look live, slow enough to batch. */
internal const val DEFAULT_WINDOW_MILLIS: Long = 80L

private const val NANOS_PER_MILLI: Long = 1_000_000L

/**
 * Folds [event] into [pending] when it is a streaming update that a later event can supersede or
 * extend; returns false for events that must be emitted as-is, in order (barriers).
 */
private fun mergeIntoPending(
    event: OpenCodeEvent,
    pending: LinkedHashMap<String, PendingSlot>,
): Boolean {
    when (event) {
        is OpenCodeEvent.MessageUpdated -> {
            val key = "u:${event.info.sessionId}:${event.info.id}"
            pending.getOrPut(key) { PendingSlot(event.info.sessionId, event.info.id, partId = null) }
                .let { it.roleUpdate = event }
            return true
        }
        is OpenCodeEvent.MessagePartUpdated -> {
            val part = event.part
            val sessionId = part.sessionId ?: return false
            val messageId = part.messageId ?: part.id ?: return false
            val partId = part.id ?: messageId
            val key = "p:$sessionId:$messageId:$partId"
            pending.getOrPut(key) { PendingSlot(sessionId, messageId, partId) }.offerSnapshot(event)
            return true
        }
        is OpenCodeEvent.MessagePartDelta -> {
            val key = "p:${event.sessionId}:${event.messageId}:${event.partId}"
            val slot = pending.getOrPut(key) { PendingSlot(event.sessionId, event.messageId, event.partId) }
            slot.offerDelta(event.field, event.delta)
            return true
        }
        else -> return false
    }
}

/**
 * Per-part accumulation state. At most one exists per (session, message, part) while a window is
 * open; its position in [pending] is the arrival order of its first event, which is what keeps
 * ordering stable across different parts and messages.
 *
 * Inside the slot, arrival order is preserved too: [pieces] holds snapshots and delta runs in
 * the order they arrived. Consecutive same-field deltas concatenate into one run, and a snapshot
 * that directly follows another replaces it - but a snapshot that arrives *after* deltas keeps
 * its place behind them. Antigravity and Codex both end a part with a full-text snapshot after
 * its deltas (same part id); emitting that snapshot before the deltas would make the chat append
 * the delta text to the already-complete snapshot and show the tail twice.
 */
private class PendingSlot(
    private val sessionId: String,
    private val messageId: String,
    private val partId: String?,
) {
    private var roleUpdate: OpenCodeEvent.MessageUpdated? = null
    private val pieces = mutableListOf<Piece>()

    private sealed interface Piece {
        data class Snapshot(val event: OpenCodeEvent.MessagePartUpdated) : Piece

        class DeltaRun(
            val field: String,
            val text: StringBuilder,
            var count: Int,
        ) : Piece
    }

    fun offerSnapshot(event: OpenCodeEvent.MessagePartUpdated) {
        val last = pieces.lastOrNull()
        if (last is Piece.Snapshot) {
            pieces[pieces.lastIndex] = Piece.Snapshot(event)
        } else {
            pieces.add(Piece.Snapshot(event))
        }
    }

    fun offerDelta(
        field: String,
        delta: String,
    ) {
        val last = pieces.lastOrNull()
        if (last is Piece.DeltaRun && last.field == field) {
            last.text.append(delta)
            last.count++
        } else {
            pieces.add(Piece.DeltaRun(field, StringBuilder(delta), 1))
        }
    }

    suspend fun emitInto(send: suspend (OpenCodeEvent) -> Unit) {
        roleUpdate?.let { send(it) }
        for (piece in pieces) {
            when (piece) {
                is Piece.Snapshot -> send(piece.event)
                is Piece.DeltaRun ->
                    if (piece.text.isNotEmpty()) {
                        send(
                            OpenCodeEvent.MessagePartDelta(
                                sessionId = sessionId,
                                messageId = messageId,
                                partId = partId ?: continue,
                                field = piece.field,
                                delta = piece.text.toString(),
                                mergeCount = piece.count,
                            ),
                        )
                    }
            }
        }
    }
}
