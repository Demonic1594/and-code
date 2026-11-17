package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The inserted issue as each of the app's languages words it, read from the raw `strings.xml` files so
 * it runs on the JVM. Every language must keep the two things the text is checked for: it names the
 * issue as `owner/repo#n` in backticks (no link back to itself, and the mark [IssuePrompt.isInserted]
 * finds), and it says the issue's text is a description, not instructions.
 */
class IssuePromptLocalesTest {
    private val locales =
        mapOf(
            "values" to "en",
            "values-ar" to "ar",
            "values-es" to "es",
            "values-fr" to "fr",
            "values-ja" to "ja",
            "values-pt-rBR" to "pt-BR",
            "values-ru" to "ru",
            "values-zh-rCN" to "zh-CN",
        )

    private val ref = IssueRef("octo-org", "my.repo", 42)

    private val thread =
        IssueThread(
            ref = ref,
            title = "Crash on start",
            author = "alice",
            body = "Dup of https://github.com/octo-org/my.repo/issues/42\n" + "x".repeat(9_000),
            state = "closed",
            labels = listOf("bug", "good first issue"),
            comments = (1..25).map { IssueComment("u$it", "comment $it") },
        )

    @Test
    fun `every language names the issue without linking to it, and its draft is known as inserted`() {
        locales.forEach { (dir, tag) ->
            val prompt = IssuePrompt.build(thread, labels(dir, tag))
            val header = prompt.lineSequence().first()

            assertEquals("$dir: the header names the issue once", 1, header.split("`octo-org/my.repo#42`").size - 1)
            assertTrue("$dir: the guard line follows the header", prompt.lines()[1].isNotBlank())
            assertTrue("$dir: inserted", IssuePrompt.isInserted(prompt, ref))
            assertEquals("$dir: no chip for its own text", emptyList<IssueRef>(), IssuePrompt.links(prompt))
            listOf("Crash on start", "alice", "bug, good first issue", "u25:\ncomment 25").forEach {
                assertTrue("$dir: GitHub's \"$it\" is kept as it is", prompt.contains(it))
            }
            assertTrue("$dir: the issue number is GitHub's", prompt.contains("#42"))
        }
    }

    /**
     * A line takes its direction from its first strong letter. An Arabic header that opened with the
     * Latin `owner/repo#n` would be laid out left to right in the right-to-left composer.
     */
    @Test
    fun `the Arabic header reads right to left`() {
        val header = IssuePrompt.build(thread, labels("values-ar", "ar")).lineSequence().first()
        val first = header.first { Character.getDirectionality(it) in STRONG }

        assertTrue(
            "first strong letter of \"$header\" is right-to-left",
            Character.getDirectionality(first) != Character.DIRECTIONALITY_LEFT_TO_RIGHT,
        )
    }

    /** Each plural form formats with the arguments the app passes, in every language. */
    @Test
    fun `every plural form formats`() {
        locales.forEach { (dir, tag) ->
            val strings = Strings(dir, tag)
            listOf(
                "chat_issue_prompt_comments_latest" to arrayOf<Any>(20, 5),
                "chat_issue_prompt_cut" to arrayOf<Any>(1000),
            ).forEach { (name, args) ->
                val forms = strings.plurals.getValue(name)
                assertTrue("$dir: $name has an other form", "other" in forms)
                forms.forEach { (quantity, value) ->
                    val text = String.format(strings.locale, value, *args)
                    assertFalse("$dir: $name/$quantity is formatted", text.contains('%'))
                }
            }
        }
    }

    private fun labels(
        dir: String,
        tag: String,
    ): IssuePromptLabels {
        val strings = Strings(dir, tag)
        return IssuePromptLabels(
            header = { strings.string("chat_issue_prompt_header", it) },
            guard = strings.string("chat_issue_prompt_guard"),
            meta = { number, state, author -> strings.string("chat_issue_prompt_meta", number, state, author) },
            open = strings.string("chat_issue_prompt_state_open"),
            closed = strings.string("chat_issue_prompt_state_closed"),
            labels = { strings.string("chat_issue_prompt_labels", it) },
            noDescription = strings.string("chat_issue_prompt_no_description"),
            comments = strings.string("chat_issue_prompt_comments"),
            commentsLatest = { shown, skipped -> strings.plural("chat_issue_prompt_comments_latest", skipped, shown, skipped) },
            commentsPartial = { shown, read -> strings.string("chat_issue_prompt_comments_partial", shown, read) },
            cut = { strings.plural("chat_issue_prompt_cut", it, it) },
        )
    }

    /** One language's strings and plurals, unescaped the way Android's resource compiler does it. */
    private class Strings(
        dir: String,
        tag: String,
    ) {
        val locale: Locale = Locale.forLanguageTag(tag)
        private val root =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir(), "$dir/strings.xml")).documentElement
        private val values =
            root.elements("string").associate { it.getAttribute("name") to unescape(it.textContent) }
        val plurals =
            root.elements("plurals").associate { plural ->
                plural.getAttribute("name") to
                    plural.elements("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }

        fun string(
            name: String,
            vararg args: Any,
        ): String = String.format(locale, values.getValue(name), *args)

        /** The `other` form, or `one` for a count of one where the language has it. */
        fun plural(
            name: String,
            count: Int,
            vararg args: Any,
        ): String {
            val forms = plurals.getValue(name)
            return String.format(locale, (if (count == 1) forms["one"] else null) ?: forms.getValue("other"), *args)
        }

        private fun Element.elements(tag: String): List<Element> =
            getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

        private fun unescape(raw: String): String =
            raw.trim()
                .removeSurrounding("\"")
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\@", "@")
                .replace("\\?", "?")

        private fun resDir(): File {
            var dir = File(".").canonicalFile
            repeat(8) {
                File(dir, "app/src/main/res").takeIf { it.isDirectory }?.let { return it }
                File(dir, "src/main/res").takeIf { it.isDirectory }?.let { return it }
                dir = dir.parentFile ?: return@repeat
            }
            error("No res directory above ${File(".").canonicalPath}")
        }
    }

    private companion object {
        val STRONG =
            setOf(
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            )
    }
}
