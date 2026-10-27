package com.yugahashimoto.andcode.feature.workspace.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {
    /** Each token as `kind:text`, for readable assertions. */
    private fun tokens(
        text: String,
        language: CodeLanguage,
    ): List<String> = SyntaxHighlighter.tokenize(text, language).map { "${it.kind}:${text.substring(it.start, it.end)}" }

    @Test
    fun `kotlin keywords, strings, comments, numbers, calls and types`() {
        val t = tokens("val x: Int = parse(\"a\\\"b\", 0x1F) // done\n/* multi\nline */ @Suppress", CodeLanguage.KOTLIN)
        assertEquals(
            listOf(
                "KEYWORD:val",
                "BUILTIN:Int",
                "FUNCTION:parse",
                "PUNCTUATION:(",
                "STRING:\"a\\\"b\"",
                "NUMBER:0x1F",
                "PUNCTUATION:)",
                "COMMENT:// done",
                "COMMENT:/* multi\nline */",
                "ANNOTATION:@Suppress",
            ),
            t,
        )
    }

    @Test
    fun `an unclosed quote stops at the end of its line`() {
        val t = tokens("it's\nval y", CodeLanguage.KOTLIN)
        assertEquals(listOf("BUILTIN:it", "STRING:'s", "KEYWORD:val"), t)
    }

    @Test
    fun `python triple quotes and hash comments`() {
        val t = tokens("def f():\n    \"\"\"doc\nmore\"\"\"  # note\n    return None", CodeLanguage.PYTHON)
        assertEquals(
            listOf(
                "KEYWORD:def",
                "FUNCTION:f",
                "PUNCTUATION:(",
                "PUNCTUATION:)",
                "STRING:\"\"\"doc\nmore\"\"\"",
                "COMMENT:# note",
                "KEYWORD:return",
                "BUILTIN:None",
            ),
            t,
        )
    }

    @Test
    fun `shell variables, and a hash inside a word is not a comment`() {
        val t = tokens("echo \"\$HOME\" \${#arr} a#b # c", CodeLanguage.SHELL)
        assertEquals(listOf("KEYWORD:echo", "STRING:\"\$HOME\"", "VARIABLE:\${#arr}", "COMMENT:# c"), t)
    }

    @Test
    fun `json keys are properties and values are strings`() {
        val t = tokens("{\"name\": \"x\", \"n\": 1.5e3, \"ok\": true}", CodeLanguage.JSON)
        assertEquals(
            listOf(
                "PUNCTUATION:{",
                "PROPERTY:\"name\"",
                "STRING:\"x\"",
                "PROPERTY:\"n\"",
                "NUMBER:1.5e3",
                "PROPERTY:\"ok\"",
                "BUILTIN:true",
                "PUNCTUATION:}",
            ),
            t,
        )
    }

    @Test
    fun `markup tags, attributes and embedded script`() {
        val t = tokens("<a href=\"/x\">hi</a><!-- c --><script>let v = 1</script>", CodeLanguage.HTML)
        assertTrue(
            t.containsAll(listOf("TAG:<a", "ATTRIBUTE:href", "STRING:\"/x\"", "TAG:</a", "COMMENT:<!-- c -->", "KEYWORD:let", "NUMBER:1")),
        )
    }

    @Test
    fun `css properties, colours and units`() {
        val t = tokens("a:hover { color: #fff; margin: 12px }", CodeLanguage.CSS)
        assertTrue(t.containsAll(listOf("PROPERTY:color", "NUMBER:#fff", "PROPERTY:margin", "NUMBER:12px")))
        assertTrue("a selector's pseudo-class is not a property", "PROPERTY:a" !in t)
    }

    @Test
    fun `sql keywords ignore case`() {
        assertEquals(listOf("KEYWORD:SELECT", "KEYWORD:from"), tokens("SELECT * from t", CodeLanguage.SQL))
    }

    @Test
    fun `markdown headings, fences and inline code`() {
        val t = tokens("# Title\nSome `code` and **bold**\n```\nx\n```", CodeLanguage.MARKDOWN)
        assertEquals(listOf("HEADING:# Title", "STRING:`code`", "TYPE:**bold**", "COMMENT:```", "STRING:x", "COMMENT:```"), t)
    }

    @Test
    fun `languages are found by extension and by name`() {
        assertEquals("TypeScript", CodeLanguage.forPath("src/App.tsx").name)
        assertEquals("Dockerfile", CodeLanguage.forPath("/w/Dockerfile").name)
        assertEquals("Plain text", CodeLanguage.forPath("notes.unknown").name)
        assertEquals("Plain text", CodeLanguage.forPath("LICENSE").name)
    }

    @Test
    fun `a huge file is left uncoloured`() {
        val big = "x".repeat(SyntaxHighlighter.MAX_HIGHLIGHT_CHARS + 1)
        assertTrue(SyntaxHighlighter.tokenize(big, CodeLanguage.KOTLIN).isEmpty())
    }

    @Test
    fun `a hash after code is a comment in python but not inside a shell word`() {
        assertEquals(listOf("NUMBER:1", "COMMENT:#c"), tokens("x=1#c", CodeLanguage.PYTHON))
        assertTrue("COMMENT:#b" !in tokens("a#b", CodeLanguage.SHELL))
    }
}
