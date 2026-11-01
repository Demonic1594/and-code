package com.yugahashimoto.andcode.feature.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An `@word` being typed in the composer: where it is, and what follows the `@`. */
data class MentionQuery(
    val start: Int,
    val end: Int,
    val query: String,
)

/**
 * `@` mentions of project files in the composer. Typing `@` at the start of a word asks for files
 * whose path matches what follows; picking one puts `@path/to/file` in its place, which the agents
 * read as a reference to that file.
 */
object FileMentions {
    /** The mention the cursor ends, or null when the cursor is not at the end of an `@word`. */
    fun queryAt(value: TextFieldValue): MentionQuery? {
        val selection = value.selection
        if (!selection.collapsed) return null
        val text = value.text
        val end = selection.end.coerceIn(0, text.length)
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        if (start >= end || text[start] != '@') return null
        val query = text.substring(start + 1, end)
        // An e-mail address or a second @ is not a mention.
        if (query.contains('@')) return null
        return MentionQuery(start, end, query)
    }

    /** [value] with [mention] replaced by `@path ` and the cursor after it. */
    fun insert(
        value: TextFieldValue,
        mention: MentionQuery,
        path: String,
    ): TextFieldValue {
        val inserted = "@$path "
        // The whole word goes, also what follows the cursor when it sits inside it (`@ma|in`).
        var end = mention.end
        while (end < value.text.length && !value.text[end].isWhitespace()) end++
        if (end < value.text.length) end++
        val text = value.text.replaceRange(mention.start, end, inserted)
        return TextFieldValue(text, TextRange(mention.start + inserted.length))
    }
}

/**
 * Finds files for the mention being typed, one search at a time: a new query cancels the one before,
 * and waits a moment so each key press does not start a search of its own.
 */
class FileMentionSearch(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val search: suspend (String) -> List<String>,
) {
    private val mutableResults = MutableStateFlow<List<String>>(emptyList())

    /** Files matching the current query, at most [LIMIT]; empty while there is no mention. */
    val results: StateFlow<List<String>> = mutableResults.asStateFlow()

    private var job: Job? = null

    /** Searches for [query], or clears the results when it is null (no mention at the cursor). */
    fun query(query: String?) {
        job?.cancel()
        if (query == null) {
            mutableResults.value = emptyList()
            return
        }
        job =
            scope.launch {
                delay(debounceMillis)
                val found =
                    try {
                        search(query)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        emptyList()
                    }
                mutableResults.value = found.take(LIMIT)
            }
    }

    companion object {
        const val LIMIT = 8
        const val DEBOUNCE_MILLIS = 150L
    }
}

/** What the composer needs to offer files for an `@` mention, provided by the chat route. */
class FileMentionsUi(
    val results: List<String>,
    val onQuery: (String?) -> Unit,
)

val LocalFileMentions = compositionLocalOf<FileMentionsUi?> { null }
