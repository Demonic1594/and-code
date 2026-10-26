package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What one assistant turn used, as the runtime recorded it. */
data class TurnUsage(
    /** Epoch ms the turn was created. */
    val time: Long,
    /** `provider/model`, or empty when the runtime did not say. */
    val model: String,
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    /** USD, or null when the runtime does not report cost. */
    val cost: Double?,
) {
    val tokens: Long get() = input + output + reasoning + cacheRead + cacheWrite
}

/** One chat's turns. A chat whose runtime could not be reached has [unreadable] set and no turns. */
data class ChatUsage(
    val ref: RuntimeSessionRef,
    val turns: List<TurnUsage>,
    val unreadable: Boolean = false,
)

/** Reading the chats in progress: what was read so far, and how many there are. */
data class UsageProgress(
    val chats: List<ChatUsage>,
    val read: Int,
    val total: Int,
) {
    val done: Boolean get() = read >= total
}

/**
 * Collects token use and cost across every chat for the usage page.
 *
 * As with [MessageSearchRepository], the runtimes keep the messages, so each chat is read through
 * its runtime a few at a time and the page fills in as they arrive. A chat's turns are cached while
 * its `updated` time is unchanged, so opening the page again only reads what changed.
 */
class UsageRepository(
    private val targets: () -> List<RuntimeTarget>,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class CachedChat(
        val updated: Long,
        val turns: List<TurnUsage>,
    )

    private val cache = LinkedHashMap<String, CachedChat>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    fun collect(chats: List<RuntimeSessionRef>): Flow<UsageProgress> =
        channelFlow {
            if (chats.isEmpty()) {
                send(UsageProgress(emptyList(), 0, 0))
                return@channelFlow
            }
            val byId = targets().associateBy { it.id }
            val permits = Semaphore(parallelism)
            val read = mutableListOf<ChatUsage>()
            val lock = Any()
            send(UsageProgress(emptyList(), 0, chats.size))
            chats.forEach { ref ->
                launch {
                    val usage = permits.withPermit { usageOf(ref, byId[ref.runtimeId]) }
                    val progress =
                        synchronized(lock) {
                            read += usage
                            UsageProgress(read.toList(), read.size, chats.size)
                        }
                    send(progress)
                }
            }
        }

    private suspend fun usageOf(
        ref: RuntimeSessionRef,
        target: RuntimeTarget?,
    ): ChatUsage {
        val key = "${ref.runtimeId}/${ref.session.id}"
        val updated = ref.session.time.updated ?: ref.session.time.created
        synchronized(cache) { cache[key] }?.takeIf { it.updated == updated }?.let { return ChatUsage(ref, it.turns) }
        // Off the main thread: a local agent reads its message store from disk here.
        val messages =
            target?.let { withContext(ioDispatcher) { runCatching { it.listMessages(ref.session.id) }.getOrNull() } }
                ?: return ChatUsage(ref, emptyList(), unreadable = true)
        val turns = turnsOf(messages)
        synchronized(cache) {
            cache[key] = CachedChat(updated, turns)
            while (cache.size > MAX_CACHED_CHATS) cache.remove(cache.keys.first())
        }
        return ChatUsage(ref, turns)
    }

    companion object {
        private const val DEFAULT_PARALLELISM = 4
        private const val MAX_CACHED_CHATS = 1_000
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        /** The assistant turns that reported tokens or a cost; the user's own messages use nothing. */
        internal fun turnsOf(messages: List<OpenCodeMessage>): List<TurnUsage> =
            messages
                .filterNot { it.info.role.equals("user", ignoreCase = true) }
                .filter { it.info.tokens != null || it.info.cost != null }
                .map { message ->
                    val info = message.info
                    val tokens = info.tokens
                    TurnUsage(
                        time = info.time.created,
                        model = info.model?.let { "${it.providerId}/${it.modelId}" }.orEmpty(),
                        input = tokens?.input ?: 0,
                        output = tokens?.output ?: 0,
                        reasoning = tokens?.reasoning ?: 0,
                        cacheRead = tokens?.cache?.read ?: 0,
                        cacheWrite = tokens?.cache?.write ?: 0,
                        cost = info.cost,
                    )
                }
    }
}
