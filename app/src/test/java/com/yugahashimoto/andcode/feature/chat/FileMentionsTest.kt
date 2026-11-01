package com.yugahashimoto.andcode.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileMentionsTest {
    private fun at(
        text: String,
        cursor: Int = text.length,
    ) = FileMentions.queryAt(TextFieldValue(text, TextRange(cursor)))

    @Test
    fun `an at sign starting a word under the cursor is a mention`() {
        assertEquals(MentionQuery(0, 5, "main"), at("@main"))
        assertEquals(MentionQuery(4, 9, "src/"), at("fix @src/ please", cursor = 9))
        assertEquals(MentionQuery(4, 5, ""), at("fix @"))
    }

    @Test
    fun `an e-mail address, a finished word or a selection is not`() {
        assertNull(at("mail me@host.com"))
        assertNull(at("fix @main.kt now"))
        assertNull(at("@a@b"))
        assertNull(FileMentions.queryAt(TextFieldValue("@main", TextRange(0, 5))))
    }

    @Test
    fun `picking a file puts its path in the mention's place, with the cursor after it`() {
        val value = TextFieldValue("look at @mai and fix it", TextRange(12))
        val mention = FileMentions.queryAt(value)!!

        val picked = FileMentions.insert(value, mention, "src/main.kt")

        assertEquals("look at @src/main.kt  and fix it", picked.text)
        assertEquals(TextRange(21), picked.selection)
    }

    @Test
    fun `only the last query is searched, after a pause, and a failed search shows nothing`() =
        runTest {
            val asked = mutableListOf<String>()
            val search =
                FileMentionSearch(backgroundScope, debounceMillis = 100) { query ->
                    asked += query
                    if (query == "boom") error("no backend")
                    List(20) { "$query$it" }
                }

            search.query("m")
            search.query("ma")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf("ma"), asked)
            assertEquals(FileMentionSearch.LIMIT, search.results.value.size)

            search.query("boom")
            advanceTimeBy(150)
            runCurrent()
            assertEquals(emptyList<String>(), search.results.value)

            search.query("x")
            search.query(null)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(emptyList<String>(), search.results.value)
        }
}
