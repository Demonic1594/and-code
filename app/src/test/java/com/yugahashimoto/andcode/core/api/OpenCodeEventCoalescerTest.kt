package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeEventCoalescerTest {
    @Test
    fun `consecutive snapshots of one part collapse to the latest`() =
        runTest {
            val out =
                flowOf(
                    partUpdated("s1", "m1", "p1", "one"),
                    partUpdated("s1", "m1", "p1", "two"),
                    partUpdated("s1", "m1", "p1", "three"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            assertEquals(1, out.size)
            assertEquals("three", (out.single() as OpenCodeEvent.MessagePartUpdated).part.text)
        }

    @Test
    fun `different parts stay separate and keep first-arrival order`() =
        runTest {
            val out =
                flowOf(
                    partUpdated("s1", "m1", "p1", "one"),
                    partUpdated("s1", "m1", "p2", "two"),
                    partUpdated("s1", "m1", "p1", "one-later"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            // Slots keep the position of their first arrival; each carries its latest snapshot.
            assertEquals(2, out.size)
            assertEquals("p1", (out[0] as OpenCodeEvent.MessagePartUpdated).part.id)
            assertEquals("one-later", (out[0] as OpenCodeEvent.MessagePartUpdated).part.text)
            assertEquals("two", (out[1] as OpenCodeEvent.MessagePartUpdated).part.text)
        }

    @Test
    fun `consecutive deltas for one part concatenate and report the merge count`() =
        runTest {
            val out =
                flowOf(
                    delta("s1", "m1", "p1", "hel"),
                    delta("s1", "m1", "p1", "lo w"),
                    delta("s1", "m1", "p1", "orld"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            assertEquals(1, out.size)
            val merged = out.single() as OpenCodeEvent.MessagePartDelta
            assertEquals("hello world", merged.delta)
            assertEquals(3, merged.mergeCount)
        }

    @Test
    fun `a message role update flushes before the part it gates`() =
        runTest {
            val out =
                flowOf(
                    OpenCodeEvent.MessageUpdated(messageInfo("s1", "m1", role = "user")),
                    partUpdated("s1", "m1", "p1", "echo"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            assertEquals(2, out.size)
            assertTrue(out[0] is OpenCodeEvent.MessageUpdated)
            assertTrue(out[1] is OpenCodeEvent.MessagePartUpdated)
        }

    @Test
    fun `barrier events pass through immediately and flush pending work first`() =
        runTest {
            val out =
                flowOf(
                    partUpdated("s1", "m1", "p1", "one"),
                    partUpdated("s1", "m1", "p1", "two"),
                    OpenCodeEvent.SessionStatusChanged("s1", "busy"),
                    partUpdated("s1", "m1", "p1", "three"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            assertEquals(listOf("part:two", "status", "part:three"), out.map(::label))
        }

    @Test
    fun `an idle window flushes pending updates without waiting for the stream to end`() =
        runTest {
            val source =
                flow {
                    emit(partUpdated("s1", "m1", "p1", "one"))
                    kotlinx.coroutines.delay(120)
                    emit(delta("s1", "m1", "p1", "tail"))
                }
            val out = source.coalesceStreamingUpdates(windowMillis = 50L).toList()

            // The snapshot left with the first idle window; the later delta flushed on completion.
            assertEquals(listOf("part:one", "delta:tail"), out.map(::label))
        }

    @Test
    fun `a flood that never pauses is flushed window by window`() =
        runTest {
            // The clock advances only while the producer is between emissions (the delay yields
            // to the collector), so time passes as the consumer sees events - not all at once
            // before the first one is processed.
            var nowNanos = 0L
            val source =
                flow {
                    repeat(30) {
                        emit(delta("s1", "m1", "p1", "x"))
                        nowNanos += 20_000_000L
                        delay(1)
                    }
                }
            val out = source.coalesceStreamingUpdates(windowMillis = 50L, clockNanos = { nowNanos }).toList()

            assertTrue("expected multiple windows, got ${out.size}", out.size > 1)
            val deltas = out.filterIsInstance<OpenCodeEvent.MessagePartDelta>()
            assertEquals(30, deltas.sumOf { it.delta.length })
            assertEquals(30, deltas.sumOf { it.mergeCount })
        }

    @Test
    fun `deltas followed by a full snapshot keep their order within one window`() =
        runTest {
            // Antigravity and Codex end a part with its full-text snapshot after the deltas; the
            // snapshot must stay behind them or the chat appends the delta tail to the completed
            // text and shows it twice.
            val out =
                flowOf(
                    delta("s1", "m1", "p1", "tail-"),
                    delta("s1", "m1", "p1", "end"),
                    partUpdated("s1", "m1", "p1", "the complete text"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            assertEquals(listOf("delta:tail-end", "part:the complete text"), out.map(::label))
        }

    @Test
    fun `upstream failure propagates after pending work is flushed`() =
        runTest {
            val failure = IllegalStateException("stream broke")

            val out =
                runCatching {
                    flow {
                        emit(partUpdated("s1", "m1", "p1", "one"))
                        emit(partUpdated("s1", "m1", "p1", "two"))
                        throw failure
                    }
                        .coalesceStreamingUpdates(windowMillis = 60_000L)
                        .toList()
                }
                    .exceptionOrNull()

            assertEquals(failure, out)
        }

    @Test
    fun `snapshots and deltas of the same part merge into snapshot then delta`() =
        runTest {
            val out =
                flowOf(
                    partUpdated("s1", "m1", "p1", "base"),
                    delta("s1", "m1", "p1", "A"),
                    delta("s1", "m1", "p1", "B"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            assertEquals(listOf("part:base", "delta:AB"), out.map(::label))
        }

    @Test
    fun `reasoning and text deltas of one part stay separate fields`() =
        runTest {
            val out =
                flowOf(
                    OpenCodeEvent.MessagePartDelta("s1", "m1", "p1", "reasoning", "think", mergeCount = 1),
                    delta("s1", "m1", "p1", "answer"),
                ).coalesceStreamingUpdates(windowMillis = 60_000L).toList()

            val deltas = out.filterIsInstance<OpenCodeEvent.MessagePartDelta>()
            assertEquals(setOf("reasoning", "text"), deltas.map { it.field }.toSet())
            assertEquals("think", deltas.first { it.field == "reasoning" }.delta)
            assertEquals("answer", deltas.first { it.field == "text" }.delta)
        }
}

private fun partUpdated(
    sessionId: String,
    messageId: String,
    partId: String,
    text: String,
) = OpenCodeEvent.MessagePartUpdated(
    OpenCodePart(
        id = partId,
        sessionId = sessionId,
        messageId = messageId,
        type = "text",
        text = text,
    ),
)

private fun delta(
    sessionId: String,
    messageId: String,
    partId: String,
    text: String,
) = OpenCodeEvent.MessagePartDelta(
    sessionId = sessionId,
    messageId = messageId,
    partId = partId,
    field = "text",
    delta = text,
)

private fun messageInfo(
    sessionId: String,
    messageId: String,
    role: String,
) = OpenCodeMessageInfo(
    id = messageId,
    sessionId = sessionId,
    role = role,
)

private fun label(event: OpenCodeEvent): String =
    when (event) {
        is OpenCodeEvent.MessagePartUpdated -> "part:${event.part.text}"
        is OpenCodeEvent.MessagePartDelta -> "delta:${event.delta}"
        is OpenCodeEvent.SessionStatusChanged -> "status"
        else -> "other"
    }
