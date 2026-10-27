package com.yugahashimoto.andcode.feature.workspace.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily

/** What a key on the editor's key bar does. */
sealed interface EditorKey {
    data class Insert(
        val text: String,
    ) : EditorKey

    data class Move(
        val direction: EditorOps.Direction,
    ) : EditorKey

    data object Indent : EditorKey

    data object Outdent : EditorKey

    data object Comment : EditorKey

    data object Duplicate : EditorKey

    data object DeleteLine : EditorKey

    data object MoveLineUp : EditorKey

    data object MoveLineDown : EditorKey

    data object SelectLine : EditorKey
}

private val SYMBOLS =
    listOf(
        "{",
        "}",
        "(",
        ")",
        "[",
        "]",
        "<",
        ">",
        "=",
        ";",
        ":",
        "\"",
        "'",
        "`",
        "/",
        "\\",
        "|",
        "&",
        "!",
        "?",
        "-",
        "_",
        "+",
        "*",
        "$",
        "#",
        "@",
        "%",
        ".",
        ",",
    )

/**
 * The keys a phone keyboard hides behind long presses: Tab, arrows, brackets and symbols, and the
 * line commands of a desktop editor, in one scrolling row above the keyboard, as Acode has.
 */
@Composable
fun EditorKeyBar(
    palette: EditorPalette,
    onKey: (EditorKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(palette.toolbar)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconKey(
            Icons.AutoMirrored.Filled.FormatIndentIncrease,
            stringResource(R.string.editor_key_indent),
            palette,
        ) { onKey(EditorKey.Indent) }
        IconKey(
            Icons.AutoMirrored.Filled.FormatIndentDecrease,
            stringResource(R.string.editor_key_outdent),
            palette,
        ) { onKey(EditorKey.Outdent) }
        IconKey(Icons.Default.KeyboardArrowLeft, stringResource(R.string.editor_key_left), palette) {
            onKey(EditorKey.Move(EditorOps.Direction.LEFT))
        }
        IconKey(
            Icons.Default.KeyboardArrowUp,
            stringResource(R.string.editor_key_up),
            palette,
        ) { onKey(EditorKey.Move(EditorOps.Direction.UP)) }
        IconKey(Icons.Default.KeyboardArrowDown, stringResource(R.string.editor_key_down), palette) {
            onKey(EditorKey.Move(EditorOps.Direction.DOWN))
        }
        IconKey(Icons.Default.KeyboardArrowRight, stringResource(R.string.editor_key_right), palette) {
            onKey(EditorKey.Move(EditorOps.Direction.RIGHT))
        }
        TextKey("⇤", stringResource(R.string.editor_key_home), palette) { onKey(EditorKey.Move(EditorOps.Direction.HOME)) }
        TextKey("⇥", stringResource(R.string.editor_key_end), palette) { onKey(EditorKey.Move(EditorOps.Direction.END)) }
        Divider(palette)
        SYMBOLS.forEach { symbol -> TextKey(symbol, symbol, palette) { onKey(EditorKey.Insert(symbol)) } }
        Divider(palette)
        TextKey("//", stringResource(R.string.editor_key_comment), palette) { onKey(EditorKey.Comment) }
        IconKey(Icons.Default.ContentCopy, stringResource(R.string.editor_key_duplicate), palette) { onKey(EditorKey.Duplicate) }
        IconKey(Icons.Default.DeleteSweep, stringResource(R.string.editor_key_delete_line), palette) { onKey(EditorKey.DeleteLine) }
        IconKey(Icons.Default.KeyboardDoubleArrowUp, stringResource(R.string.editor_key_line_up), palette) { onKey(EditorKey.MoveLineUp) }
        IconKey(
            Icons.Default.KeyboardDoubleArrowDown,
            stringResource(R.string.editor_key_line_down),
            palette,
        ) { onKey(EditorKey.MoveLineDown) }
        IconKey(Icons.Default.UnfoldMore, stringResource(R.string.editor_key_select_line), palette) { onKey(EditorKey.SelectLine) }
    }
}

@Composable
private fun Divider(palette: EditorPalette) {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(palette.gutterText))
}

private val KEY_SHAPE = RoundedCornerShape(7.dp)

@Composable
private fun TextKey(
    label: String,
    description: String,
    palette: EditorPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .heightIn(min = 36.dp)
                .widthIn(min = 36.dp)
                .clip(KEY_SHAPE)
                .background(palette.key)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.keyText, fontFamily = CodeFontFamily, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IconKey(
    icon: ImageVector,
    description: String,
    palette: EditorPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(KEY_SHAPE)
                .background(palette.key)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.keyText, modifier = Modifier.size(18.dp))
    }
}

/** The find bar: query, match count, previous and next, options, and replace when editing. */
@Composable
fun FindBar(
    palette: EditorPalette,
    query: String,
    onQueryChange: (String) -> Unit,
    options: EditorOps.FindOptions,
    onOptionsChange: (EditorOps.FindOptions) -> Unit,
    matchCount: Int,
    current: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    replacement: String? = null,
    onReplacementChange: (String) -> Unit = {},
    onReplace: () -> Unit = {},
    onReplaceAll: () -> Unit = {},
) {
    val invalid = options.regex && query.isNotEmpty() && EditorOps.patternOf(query, options) == null
    Column(modifier = modifier.fillMaxWidth().background(palette.toolbar).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BarField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.code_find_label),
                palette = palette,
                error = invalid,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            Text(
                when {
                    query.isEmpty() -> ""
                    matchCount == 0 -> stringResource(R.string.code_no_matches)
                    else -> stringResource(R.string.code_match_counter, current + 1, matchCount)
                },
                color = palette.gutterActive,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            BarIcon(
                Icons.Default.KeyboardArrowUp,
                stringResource(R.string.cd_previous_match),
                palette,
                enabled = matchCount > 0,
                onClick = onPrevious,
            )
            BarIcon(
                Icons.Default.KeyboardArrowDown,
                stringResource(R.string.cd_next_match),
                palette,
                enabled = matchCount > 0,
                onClick = onNext,
            )
            BarIcon(Icons.Default.Close, stringResource(R.string.close_description), palette, onClick = onClose)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Toggle("Aa", stringResource(R.string.editor_find_match_case), options.matchCase, palette) {
                onOptionsChange(options.copy(matchCase = !options.matchCase))
            }
            Toggle("W", stringResource(R.string.editor_find_whole_word), options.wholeWord, palette) {
                onOptionsChange(options.copy(wholeWord = !options.wholeWord))
            }
            Toggle(
                ".*",
                stringResource(R.string.editor_find_regex),
                options.regex,
                palette,
            ) { onOptionsChange(options.copy(regex = !options.regex)) }
            Spacer(Modifier.weight(1f))
        }
        if (replacement != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                BarField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    placeholder = stringResource(R.string.code_replace_label),
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )
                BarButton(stringResource(R.string.editor_replace_one), palette, enabled = matchCount > 0, onClick = onReplace)
                BarButton(stringResource(R.string.code_replace_all), palette, enabled = matchCount > 0, onClick = onReplaceAll)
            }
        }
    }
}

@Composable
private fun BarField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    palette: EditorPalette,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = palette.foreground, fontFamily = CodeFontFamily, fontSize = 14.sp),
        cursorBrush = SolidColor(palette.syntax.function),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.background)
                .border(1.dp, if (error) palette.syntax.variable else palette.key, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = palette.gutterText, fontSize = 14.sp)
                inner()
            }
        },
    )
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    description: String,
    palette: EditorPalette,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, tint = if (enabled) palette.keyText else palette.gutterText)
    }
}

@Composable
private fun Toggle(
    label: String,
    description: String,
    on: Boolean,
    palette: EditorPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(KEY_SHAPE)
                .background(if (on) palette.syntax.function.copy(alpha = 0.3f) else palette.key)
                // toggleable reports the on/off state to accessibility services in the user's language.
                .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (on) palette.foreground else palette.gutterActive,
            fontFamily = CodeFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BarButton(
    label: String,
    palette: EditorPalette,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(start = 6.dp)
                .clip(KEY_SHAPE)
                .background(palette.key)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (enabled) palette.keyText else palette.gutterText, fontSize = 13.sp)
    }
}
