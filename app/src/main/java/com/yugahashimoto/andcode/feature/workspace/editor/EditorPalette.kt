package com.yugahashimoto.andcode.feature.workspace.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.yugahashimoto.andcode.ui.theme.SyntaxTheme
import com.yugahashimoto.andcode.ui.theme.syntaxThemeFor

/**
 * The editor's own colours, matched to the chosen syntax theme so the surface, gutter and
 * highlights belong to the same palette as the code. The code surface stays dark in every app
 * theme: the syntax palettes are all dark ones.
 */
data class EditorPalette(
    val syntax: SyntaxTheme,
    val background: Color,
    val foreground: Color,
    val gutter: Color,
    val gutterText: Color,
    val gutterActive: Color,
    val currentLine: Color,
    val selection: Color,
    val bracket: Color,
    val match: Color,
    val currentMatch: Color,
    val toolbar: Color,
    val key: Color,
    val keyText: Color,
) {
    fun styleOf(kind: TokenKind): SpanStyle =
        when (kind) {
            TokenKind.KEYWORD -> SpanStyle(color = syntax.keyword)
            TokenKind.BUILTIN -> SpanStyle(color = syntax.number)
            TokenKind.TYPE -> SpanStyle(color = syntax.type)
            TokenKind.STRING -> SpanStyle(color = syntax.string)
            TokenKind.COMMENT -> SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic)
            TokenKind.NUMBER -> SpanStyle(color = syntax.number)
            TokenKind.FUNCTION -> SpanStyle(color = syntax.function)
            TokenKind.VARIABLE -> SpanStyle(color = syntax.variable)
            TokenKind.ANNOTATION -> SpanStyle(color = syntax.type)
            TokenKind.TAG -> SpanStyle(color = syntax.variable)
            TokenKind.ATTRIBUTE -> SpanStyle(color = syntax.number)
            TokenKind.PROPERTY -> SpanStyle(color = syntax.variable)
            TokenKind.HEADING -> SpanStyle(color = syntax.function, fontWeight = FontWeight.Bold)
            TokenKind.PUNCTUATION -> SpanStyle(color = syntax.operator)
        }

    companion object {
        fun forKey(key: String?): EditorPalette {
            val syntax = syntaxThemeFor(key)
            return when (key) {
                "monokai" -> base(syntax, Color(0xFF272822), Color(0xFFF8F8F2), Color(0xFF2D2E27))
                "github-dark" -> base(syntax, Color(0xFF0D1117), Color(0xFFC9D1D9), Color(0xFF161B22))
                "solarized-dark" -> base(syntax, Color(0xFF002B36), Color(0xFF93A1A1), Color(0xFF073642))
                else -> base(syntax, Color(0xFF282C34), Color(0xFFABB2BF), Color(0xFF21252B))
            }
        }

        private fun base(
            syntax: SyntaxTheme,
            background: Color,
            foreground: Color,
            gutter: Color,
        ) = EditorPalette(
            syntax = syntax,
            background = background,
            foreground = foreground,
            gutter = gutter,
            gutterText = foreground.copy(alpha = 0.38f),
            gutterActive = foreground.copy(alpha = 0.9f),
            currentLine = foreground.copy(alpha = 0.06f),
            selection = syntax.function.copy(alpha = 0.32f),
            bracket = foreground.copy(alpha = 0.22f),
            match = Color(0x66E5C07B),
            currentMatch = Color(0xCCE5C07B),
            toolbar = gutter,
            key = foreground.copy(alpha = 0.08f),
            keyText = foreground,
        )
    }
}
