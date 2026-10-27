package com.yugahashimoto.andcode.feature.workspace.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily

/** Scroll positions and the latest text layout, shared with the screen so it can jump to a line or a match. */
@Stable
class CodeEditorState {
    val vertical = ScrollState(0)
    val horizontal = ScrollState(0)
    internal var layout by mutableStateOf<TextLayoutResult?>(null)
    internal var viewportHeight by mutableIntStateOf(0)
    internal var viewportWidth by mutableIntStateOf(0)
    internal var paddingTopPx = 0f

    /** Scrolls so [offset] sits a third of the way down the screen, and into view sideways. */
    suspend fun reveal(offset: Int) {
        val layout = layout ?: return
        val at = offset.coerceIn(0, layout.layoutInput.text.length)
        val top = layout.getLineTop(layout.getLineForOffset(at)) + paddingTopPx
        vertical.animateScrollTo((top - viewportHeight / 3f).toInt().coerceAtLeast(0))
        val x = layout.getHorizontalPosition(at, usePrimaryDirection = true).toInt()
        if (x < horizontal.value || x > horizontal.value + viewportWidth * 3 / 4) {
            horizontal.animateScrollTo((x - viewportWidth / 3).coerceAtLeast(0))
        }
    }
}

/**
 * Colours the text from [SyntaxHighlighter]'s tokens and marks search matches, without changing a
 * character: the offset mapping is the identity, so the cursor and selection are untouched. The
 * tokens are cached by text, since the field asks for the transformation on every recomposition.
 */
private class CodeTransformation(
    private val language: CodeLanguage,
    private val palette: EditorPalette,
    private val matches: List<IntRange>,
    private val currentMatch: Int,
    private val cache: TokenCache,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        cache.tokens(raw, language).forEach { spans += AnnotatedString.Range(palette.styleOf(it.kind), it.start, it.end) }
        matches.forEachIndexed { index, range ->
            if (range.last < raw.length) {
                val colour = if (index == currentMatch) palette.currentMatch else palette.match
                spans += AnnotatedString.Range(SpanStyle(background = colour), range.first, range.last + 1)
            }
        }
        return TransformedText(AnnotatedString(raw, spans), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is CodeTransformation && other.language == language && other.palette == palette && other.matches == matches &&
            other.currentMatch == currentMatch

    override fun hashCode(): Int = language.hashCode() * 31 + matches.hashCode() + currentMatch
}

internal class TokenCache {
    private var text: String? = null
    private var language: CodeLanguage? = null
    private var tokens: List<Token> = emptyList()

    fun tokens(
        raw: String,
        lang: CodeLanguage,
    ): List<Token> {
        if (raw != text || lang != language) {
            tokens = SyntaxHighlighter.tokenize(raw, lang)
            text = raw
            language = lang
        }
        return tokens
    }
}

/** Start offset of every line, for the gutter. */
internal fun lineStartsOf(text: String): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val starts = IntArray(count)
    var line = 1
    for (i in text.indices) if (text[i] == '\n') starts[line++] = i + 1
    return starts
}

private val PAD_TOP = 8.dp
private val PAD_START = 10.dp
private val PAD_END = 32.dp

/**
 * The code editor: a text field with line numbers that follow wrapped lines, the current line and
 * matching bracket marked, syntax colours, search matches, and horizontal scrolling when lines are
 * not wrapped. Edits go through [onValueChange] as whole [TextFieldValue]s, so the screen can apply
 * auto-indent and the key bar's commands to the same value.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: CodeLanguage,
    palette: EditorPalette,
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    wrap: Boolean = false,
    readOnly: Boolean = false,
    matches: List<IntRange> = emptyList(),
    currentMatch: Int = -1,
) {
    val density = LocalDensity.current
    val cache = remember { TokenCache() }
    val lineStarts = remember(value.text) { lineStartsOf(value.text) }
    val textStyle =
        remember(fontSize, palette) {
            TextStyle(color = palette.foreground, fontSize = fontSize, lineHeight = fontSize * 1.5f, fontFamily = CodeFontFamily)
        }
    val gutterStyle =
        remember(fontSize, palette) { textStyle.copy(color = palette.gutterText, fontSize = fontSize * 0.85f, textAlign = TextAlign.End) }
    val measurer = rememberTextMeasurer()
    val digitWidth = remember(gutterStyle) { measurer.measure("0", gutterStyle).size.width }
    val gutterWidth: Dp = with(density) { (digitWidth * maxOf(3, lineStarts.size.toString().length)).toDp() } + 20.dp
    val transformation = CodeTransformation(language, palette, matches, currentMatch, cache)
    val cursor = value.selection.end
    val bracket =
        remember(value.text, cursor) {
            if (value.text.length <= SyntaxHighlighter.MAX_HIGHLIGHT_CHARS) EditorOps.matchingBracket(value.text, cursor) else null
        }
    val selectionColors =
        remember(palette) { TextSelectionColors(handleColor = palette.syntax.function, backgroundColor = palette.selection) }
    state.paddingTopPx = with(density) { PAD_TOP.toPx() }

    BoxWithConstraints(
        modifier =
            modifier
                .background(palette.background)
                .onSizeChanged {
                    state.viewportHeight = it.height
                    state.viewportWidth = it.width
                },
    ) {
        val textViewport = maxWidth - gutterWidth
        val viewportHeight = maxHeight
        val layout = state.layout
        val contentHeight = with(density) { ((layout?.size?.height ?: 0) + 2 * PAD_TOP.toPx()).toDp() }
        Row(modifier = Modifier.fillMaxSize().verticalScroll(state.vertical)) {
            Gutter(
                lineStarts = lineStarts,
                layout = layout,
                cursor = cursor,
                palette = palette,
                style = gutterStyle,
                scroll = state.vertical.value,
                viewport = state.viewportHeight,
                modifier = Modifier.width(gutterWidth).height(maxOf(contentHeight, viewportHeight)),
                measurer = measurer,
            )
            Box(modifier = Modifier.weight(1f).then(if (wrap) Modifier else Modifier.horizontalScroll(state.horizontal))) {
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(palette.syntax.function),
                        visualTransformation = transformation,
                        // Code, paths and secrets: no autocorrect or sentence capitals rewriting them.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        onTextLayout = { state.layout = it },
                        modifier =
                            Modifier
                                .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = textViewport))
                                .drawBehind {
                                    val result = state.layout ?: return@drawBehind
                                    val top = PAD_TOP.toPx()
                                    val start = PAD_START.toPx()
                                    val text = result.layoutInput.text.text
                                    if (text.isEmpty() && cursor == 0) return@drawBehind
                                    // The whole logical line, across its wrapped rows.
                                    val from = EditorOps.lineStart(text, cursor.coerceAtMost(text.length))
                                    val to = EditorOps.lineEnd(text, cursor.coerceAtMost(text.length))
                                    val lineTop = result.getLineTop(result.getLineForOffset(from)) + top
                                    val lineBottom = result.getLineBottom(result.getLineForOffset(to)) + top
                                    drawRect(palette.currentLine, Offset(0f, lineTop), Size(size.width, lineBottom - lineTop))
                                    bracket?.toList()?.forEach { at ->
                                        if (at < text.length) {
                                            val box = result.getBoundingBox(at)
                                            drawRoundRect(
                                                palette.bracket,
                                                Offset(box.left + start, box.top + top),
                                                Size(box.width, box.height),
                                                CornerRadius(3.dp.toPx()),
                                            )
                                        }
                                    }
                                },
                        decorationBox = { inner ->
                            Box(modifier = Modifier.padding(start = PAD_START, end = PAD_END, top = PAD_TOP, bottom = PAD_TOP)) { inner() }
                        },
                    )
                }
            }
        }
    }
}

/** Line numbers drawn beside the text, one per logical line, only for the part on screen. */
@Composable
private fun Gutter(
    lineStarts: IntArray,
    layout: TextLayoutResult?,
    cursor: Int,
    palette: EditorPalette,
    style: TextStyle,
    scroll: Int,
    viewport: Int,
    modifier: Modifier,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val active = style.copy(color = palette.gutterActive)
    Canvas(modifier = modifier.background(palette.gutter)) {
        val result = layout ?: return@Canvas
        val text = result.layoutInput.text.text
        if (lineStarts.last() > text.length) return@Canvas
        val top = PAD_TOP.toPx()
        val firstVisual = result.getLineForVerticalPosition((scroll - top).coerceAtLeast(0f))
        val firstOffset = result.getLineStart(firstVisual)
        var line = lineStarts.binarySearch(firstOffset).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val cursorLine = lineStarts.binarySearch(cursor.coerceIn(0, text.length)).let { if (it >= 0) it else -it - 2 }
        val bottom = scroll + viewport
        val right = size.width - 8.dp.toPx()
        while (line < lineStarts.size) {
            val y = result.getLineTop(result.getLineForOffset(lineStarts[line])) + top
            if (y > bottom) break
            val label = measurer.measure((line + 1).toString(), if (line == cursorLine) active else style)
            val rowHeight =
                result.getLineBottom(
                    result.getLineForOffset(lineStarts[line]),
                ) - result.getLineTop(result.getLineForOffset(lineStarts[line]))
            drawText(label, topLeft = Offset(right - label.size.width, y + (rowHeight - label.size.height) / 2))
            line++
        }
    }
}
