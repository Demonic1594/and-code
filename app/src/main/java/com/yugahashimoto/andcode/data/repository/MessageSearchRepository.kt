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

/** One chat that matched a search, with a line of context around its first match. */
data class MessageSearchHit(
    val ref: RuntimeSessionRef,
    /** True when the chat was found by its title rather than (or as well as) its messages. */
    val titleMatch: Boolean,
    /** How many messages contain the query. */
    val messageMatches: Int,
    /** The first matching message, cut to a short window around the match; empty for a title-only hit. */
    val snippet: String,
    /** Where the match sits in [snippet], for highlighting; -1 when [snippet] is empty. */
    val snippetMatchStart: Int,
)

/** A search in progress: the hits so far and how many of the chats have been read. */
data class MessageSearchProgress(
    val hits: List<MessageSearchHit>,
    val searched: Int,
    val total: Int,
) {
    val done: Boolean get() = searched >= total
}

/**
 * Searches the text of every chat's messages, not just titles.
 *
 * The runtimes keep the messages (the local agents on disk, OpenCode behind its server), so there
 * is no index to query: each chat's messages are read through its runtime, a few chats at a time,
 * and results stream in as chats are read. What was read is cached per chat and reused while the
 * chat's `updated` time is unchanged, so a second search, or the next letter typed, costs nothing
 * for chats that did not change.
 */
class MessageSearchRepository(
    private val targets: () -> List<RuntimeTarget>,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class CachedChat(
        val updated: Long,
        val texts: List<String>,
    )

    /** Most recently read chats last; trimmed to [MAX_CACHED_CHATS] so a long history cannot grow it without bound. */
    private val cache = LinkedHashMap<String, CachedChat>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    /** Searches [chats] for [query]; emits progress after each chat is read. A blank query finds nothing. */
    fun search(
        query: String,
        chats: List<RuntimeSessionRef>,
    ): Flow<MessageSearchProgress> =
        channelFlow {
            val needle = query.trim()
            if (needle.isEmpty() || chats.isEmpty()) {
                send(MessageSearchProgress(emptyList(), 0, 0))
                return@channelFlow
            }
            val byId = targets().associateBy { it.id }
            val permits = Semaphore(parallelism)
            val hits = mutableListOf<MessageSearchHit>()
            var searched = 0
            val lock = Any()
            send(MessageSearchProgress(emptyList(), 0, chats.size))
            chats.forEach { ref ->
                launch {
                    val texts = permits.withPermit { textsOf(ref, byId[ref.runtimeId]) }
                    val hit = match(ref, texts, needle)
                    val progress =
                        synchronized(lock) {
                            searched++
                            if (hit != null) hits += hit
                            MessageSearchProgress(rank(hits, chats), searched, chats.size)
                        }
                    send(progress)
                }
            }
        }

    /** Forgets a chat's cached text, e.g. after it was deleted. */
    fun forget(sessionId: String) {
        synchronized(cache) { cache.keys.removeAll { it.endsWith("/$sessionId") } }
    }

    private suspend fun textsOf(
        ref: RuntimeSessionRef,
        target: RuntimeTarget?,
    ): List<String> {
        val key = "${ref.runtimeId}/${ref.session.id}"
        val updated = ref.session.time.updated ?: ref.session.time.created
        synchronized(cache) { cache[key] }?.takeIf { it.updated == updated }?.let { return it.texts }
        // An unreachable runtime or a chat that cannot be read is just left out of the results.
        // Off the main thread: a local agent reads its message store from disk here.
        val messages =
            target?.let { withContext(ioDispatcher) { runCatching { it.listMessages(ref.session.id) }.getOrNull() } }
                ?: return emptyList()
        val texts = messages.map(OpenCodeMessage::text).filter { it.isNotBlank() }
        synchronized(cache) {
            cache[key] = CachedChat(updated, texts)
            while (cache.size > MAX_CACHED_CHATS) cache.remove(cache.keys.first())
        }
        return texts
    }

    companion object {
        private const val DEFAULT_PARALLELISM = 4
        private const val MAX_CACHED_CHATS = 200
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val SNIPPET_RADIUS = 48

        internal fun match(
            ref: RuntimeSessionRef,
            texts: List<String>,
            needle: String,
        ): MessageSearchHit? {
            val titleMatch = ref.session.title.contains(needle, ignoreCase = true)
            val matching = texts.filter { it.contains(needle, ignoreCase = true) }
            if (!titleMatch && matching.isEmpty()) return null
            val first = matching.firstOrNull()
            val (snippet, start) = first?.let { snippet(it, needle) } ?: ("" to -1)
            return MessageSearchHit(ref, titleMatch, matching.size, snippet, start)
        }

        /**
         * A short window of [text] around the first match of [needle], whitespace collapsed, with
         * an ellipsis where it was cut, and where the match starts within it.
         */
        internal fun snippet(
            text: String,
            needle: String,
            radius: Int = SNIPPET_RADIUS,
        ): Pair<String, Int> {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            val at = flat.indexOf(needle, ignoreCase = true)
            if (at < 0) return flat.take(radius * 2) to -1
            val from = (at - radius).coerceAtLeast(0)
            val to = (at + needle.length + radius).coerceAtMost(flat.length)
            val prefix = if (from > 0) "…" else ""
            val suffix = if (to < flat.length) "…" else ""
            return prefix + flat.substring(from, to) + suffix to (at - from + prefix.length)
        }

        /** Title matches first, then by how many messages matched, then in the chats' own (newest first) order. */
        private fun rank(
            hits: List<MessageSearchHit>,
            chats: List<RuntimeSessionRef>,
        ): List<MessageSearchHit> {
            val order = chats.withIndex().associate { (index, ref) -> "${ref.runtimeId}/${ref.session.id}" to index }
            return hits.sortedWith(
                compareByDescending<MessageSearchHit> { it.titleMatch }
                    .thenByDescending { it.messageMatches }
                    .thenBy { order["${it.ref.runtimeId}/${it.ref.session.id}"] ?: Int.MAX_VALUE },
            )
        }
    }
}
