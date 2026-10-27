package com.yugahashimoto.andcode.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.workspace.editor.CodeEditor
import com.yugahashimoto.andcode.feature.workspace.editor.CodeEditorState
import com.yugahashimoto.andcode.feature.workspace.editor.CodeLanguage
import com.yugahashimoto.andcode.feature.workspace.editor.EditorKey
import com.yugahashimoto.andcode.feature.workspace.editor.EditorKeyBar
import com.yugahashimoto.andcode.feature.workspace.editor.EditorOps
import com.yugahashimoto.andcode.feature.workspace.editor.EditorPalette
import com.yugahashimoto.andcode.feature.workspace.editor.FindBar
import com.yugahashimoto.andcode.feature.workspace.editor.SyntaxHighlighter
import com.yugahashimoto.andcode.feature.workspace.editor.Token
import com.yugahashimoto.andcode.feature.workspace.editor.lineStartsOf
import com.yugahashimoto.andcode.ui.components.HapticEvent
import com.yugahashimoto.andcode.ui.components.rememberHaptics
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import kotlinx.coroutines.launch

private const val MIN_FONT = 9f
private const val MAX_FONT = 24f

/**
 * A file, read or edited. Reading shows the whole file with line numbers, colours and search, and
 * stays fast on big files by drawing only the lines on screen. Editing is a code editor: colours as
 * you type, auto-indent, a key bar with Tab, arrows, symbols and line commands, find and replace
 * with regular expressions, go to line, and an undo history in the view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(
    state: CodeViewerUiState,
    onBack: () -> Unit,
    syntaxThemeKey: String? = null,
    onStartEditing: () -> Unit = {},
    onDraftChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onStopEditing: () -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    /** A command's edit (key bar, replace, line move): its own undo step, unlike typing. */
    onApplyEdit: (String) -> Unit = onDraftChange,
) {
    val palette = remember(syntaxThemeKey) { EditorPalette.forKey(syntaxThemeKey) }
    val language = remember(state.filePath) { CodeLanguage.forPath(state.filePath) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableFloatStateOf(13f) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var replaceOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(EditorOps.FindOptions()) }
    var currentMatch by remember { mutableIntStateOf(-1) }
    val findFocus = remember { FocusRequester() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    // The editor's own value, carrying the cursor and selection; the view model holds the text.
    var field by remember { mutableStateOf(TextFieldValue(state.draft)) }
    LaunchedEffect(state.isEditing) { if (state.isEditing) field = TextFieldValue(state.draft, TextRange(0)) }
    // Undo, redo and a refused save change the draft from outside: follow it, keeping the cursor in range.
    LaunchedEffect(state.draft) {
        if (state.isEditing && field.text != state.draft) {
            field = TextFieldValue(state.draft, TextRange(field.selection.end.coerceAtMost(state.draft.length)))
        }
    }
    val editorState = remember { CodeEditorState() }
    val viewerList = rememberLazyListState()

    val searchText = if (state.isEditing) field.text else state.content
    val matches =
        remember(searchText, query, options, findOpen) { if (findOpen) EditorOps.findAll(searchText, query, options) else emptyList() }
    // The first match at or after the cursor is the current one, so the count reads "1 / 7" at once.
    LaunchedEffect(matches) {
        if (currentMatch !in matches.indices) {
            val from = if (state.isEditing) field.selection.min else 0
            currentMatch = if (matches.isEmpty()) -1 else matches.indexOfFirst { it.first >= from }.let { if (it < 0) 0 else it }
        }
    }

    LaunchedEffect(state.savedCount) { if (state.savedCount > 0) haptics.perform(HapticEvent.CONFIRM) }
    LaunchedEffect(state.editError) { if (state.editError != null) haptics.perform(HapticEvent.REJECT) }
    LaunchedEffect(findOpen) { if (findOpen) runCatching { findFocus.requestFocus() } }

    fun edit(next: TextFieldValue) {
        field = next
        onApplyEdit(next.text)
    }

    fun jumpTo(offset: Int) {
        scope.launch {
            if (state.isEditing) {
                editorState.reveal(offset)
            } else {
                viewerList.animateScrollToItem((EditorOps.lineAndColumn(state.content, offset).first - 1).coerceAtLeast(0))
            }
        }
    }

    fun step(forward: Boolean) {
        if (matches.isEmpty()) return
        currentMatch =
            when {
                forward -> (currentMatch + 1) % matches.size
                currentMatch <= 0 -> matches.lastIndex
                else -> currentMatch - 1
            }
        val range = matches[currentMatch]
        if (state.isEditing) field = field.copy(selection = TextRange(range.first, range.last + 1))
        jumpTo(range.first)
    }

    fun onKey(key: EditorKey) {
        if (state.isSaving) return
        val next =
            when (key) {
                is EditorKey.Insert -> {
                    val c = key.text.single()
                    val after = field.text.getOrNull(field.selection.end)
                    when {
                        // Typing a closer over the one auto-inserted steps past it.
                        field.selection.collapsed && c in ")]}\"'`" && after == c ->
                            field.copy(
                                selection = TextRange(field.selection.end + 1),
                            )
                        c in "([{\"'`" -> EditorOps.insertPair(field, c)
                        else -> EditorOps.insert(field, key.text)
                    }
                }
                is EditorKey.Move -> {
                    field = EditorOps.moveCursor(field, key.direction)
                    return
                }
                EditorKey.Indent -> EditorOps.indent(field, language)
                EditorKey.Outdent -> EditorOps.outdent(field, language)
                EditorKey.Comment -> EditorOps.toggleComment(field, language)
                EditorKey.Duplicate -> EditorOps.duplicateLines(field)
                EditorKey.DeleteLine -> EditorOps.deleteLines(field)
                EditorKey.MoveLineUp -> EditorOps.moveLines(field, down = false)
                EditorKey.MoveLineDown -> EditorOps.moveLines(field, down = true)
                EditorKey.SelectLine -> {
                    val start = EditorOps.lineStart(field.text, field.selection.min)
                    val end = EditorOps.lineEnd(field.text, field.selection.max)
                    field = field.copy(selection = TextRange(start, end))
                    return
                }
            }
        if (next.text == field.text) field = next else edit(next)
    }

    // Back while editing with unsaved text asks first; losing an edit typed on a phone keyboard hurts.
    // While a save is in flight, back does nothing: discarding then would be undone by the save landing.
    BackHandler(enabled = state.isEditing || findOpen) {
        when {
            findOpen -> findOpen = false
            state.isSaving -> Unit
            state.hasUnsavedChanges -> confirmDiscard = true
            else -> onStopEditing()
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.file_edit_discard_title)) },
            text = { Text(stringResource(R.string.file_edit_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onStopEditing()
                }) { Text(stringResource(R.string.file_edit_discard_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.file_edit_keep_editing)) }
            },
        )
    }
    if (showGoToLine) {
        val lineCount = remember(searchText) { lineStartsOf(searchText).size }
        GoToLineDialog(
            lineCount = lineCount,
            onDismiss = { showGoToLine = false },
            onGo = { line ->
                showGoToLine = false
                val offset = EditorOps.offsetOfLine(searchText, line)
                if (state.isEditing) field = field.copy(selection = TextRange(offset))
                jumpTo(offset)
            },
        )
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.gutter,
                        titleContentColor = palette.foreground,
                        navigationIconContentColor = palette.foreground,
                        actionIconContentColor = palette.foreground,
                    ),
                title = {
                    Column {
                        Text(
                            (if (state.hasUnsavedChanges) "● " else "") + state.filePath.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.filePath.substringBeforeLast('/', "").ifEmpty { language.name },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.gutterText,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(enabled = !state.isSaving, onClick = {
                        when {
                            state.hasUnsavedChanges -> confirmDiscard = true
                            state.isEditing -> onStopEditing()
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = onUndo, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.code_undo))
                        }
                        IconButton(onClick = onRedo, enabled = state.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.code_redo))
                        }
                    } else if (state.canEdit) {
                        IconButton(onClick = onStartEditing, enabled = !state.isPreparingEdit) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.file_edit_action))
                        }
                    }
                    IconButton(onClick = {
                        findOpen = !findOpen
                        replaceOpen = false
                    }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                    }
                    if (state.isEditing) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(end = 4.dp),
                                strokeWidth = 2.dp,
                                color = palette.foreground,
                            )
                        } else {
                            IconButton(onClick = onSave, enabled = state.hasUnsavedChanges) {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.file_edit_save))
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.editor_more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (state.isEditing) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.code_find_replace)) }, onClick = {
                                    showMenu = false
                                    findOpen = true
                                    replaceOpen = true
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.editor_go_to_line)) }, onClick = {
                                showMenu = false
                                showGoToLine = true
                            })
                            DropdownMenuItem(
                                text = { Text(stringResource(if (wrap) R.string.editor_wrap_off else R.string.editor_wrap_on)) },
                                onClick = {
                                    showMenu = false
                                    wrap = !wrap
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_font_larger)) },
                                onClick = { fontSize = (fontSize + 1).coerceAtMost(MAX_FONT) },
                                enabled = fontSize < MAX_FONT,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_font_smaller)) },
                                onClick = { fontSize = (fontSize - 1).coerceAtLeast(MIN_FONT) },
                                enabled = fontSize > MIN_FONT,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(palette.background).imePadding()) {
            // A refused start (binary, too large, read-only, outside the workspace) or save says why here.
            state.editError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(palette.gutter).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (findOpen && !state.isLoading && state.error == null && !state.isBinary) {
                FindBar(
                    palette = palette,
                    query = query,
                    onQueryChange = { query = it },
                    options = options,
                    onOptionsChange = { options = it },
                    matchCount = matches.size,
                    current = currentMatch,
                    onPrevious = { step(forward = false) },
                    onNext = { step(forward = true) },
                    onClose = { findOpen = false },
                    focusRequester = findFocus,
                    replacement = if (state.isEditing && replaceOpen) replacement else null,
                    onReplacementChange = { replacement = it },
                    onReplace = {
                        val range = matches.getOrNull(currentMatch.coerceAtLeast(0)) ?: return@FindBar
                        edit(EditorOps.replaceMatch(field, range, query, replacement, options))
                    },
                    onReplaceAll = {
                        val (text, count) = EditorOps.replaceAll(field.text, query, replacement, options)
                        if (count > 0) edit(TextFieldValue(text, TextRange(field.selection.end.coerceAtMost(text.length))))
                    },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = palette.foreground)
                    state.error != null || state.isBinary ->
                        Text(
                            text = state.error ?: stringResource(R.string.binary_file_no_preview),
                            color = if (state.error != null) MaterialTheme.colorScheme.error else palette.foreground,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    state.isEditing ->
                        CodeEditor(
                            value = field,
                            onValueChange = { next ->
                                if (state.isSaving) return@CodeEditor
                                val indented = EditorOps.autoIndent(field, next, language) ?: next
                                field = indented
                                if (indented.text != state.draft) onDraftChange(indented.text)
                            },
                            language = language,
                            palette = palette,
                            state = editorState,
                            fontSize = fontSize.sp,
                            wrap = wrap,
                            readOnly = state.isSaving,
                            matches = matches,
                            currentMatch = currentMatch,
                            modifier = Modifier.fillMaxSize(),
                        )
                    else ->
                        CodeReader(
                            content = state.content,
                            language = language,
                            palette = palette,
                            listState = viewerList,
                            fontSize = fontSize,
                            wrap = wrap,
                            matches = matches,
                            currentMatch = currentMatch,
                        )
                }
            }
            if (!state.isLoading && state.error == null && !state.isBinary) {
                StatusBar(state, field, language, palette)
            }
            if (state.isEditing) EditorKeyBar(palette = palette, onKey = ::onKey)
        }
    }
}

@Composable
private fun StatusBar(
    state: CodeViewerUiState,
    field: TextFieldValue,
    language: CodeLanguage,
    palette: EditorPalette,
) {
    val text =
        if (state.isEditing) {
            val (line, column) = remember(field.text, field.selection) { EditorOps.lineAndColumn(field.text, field.selection.end) }
            val selected = field.selection.length
            listOfNotNull(
                stringResource(R.string.editor_position, line, column),
                if (selected > 0) pluralStringResource(R.plurals.editor_selected, selected, selected) else null,
                language.name,
                if (language.indent == "\t") {
                    stringResource(
                        R.string.editor_indent_tabs,
                    )
                } else {
                    stringResource(R.string.editor_indent_spaces, language.indent.length)
                },
            ).joinToString("  ·  ")
        } else {
            val lines = remember(state.content) { lineStartsOf(state.content).size }
            listOf(pluralStringResource(R.plurals.editor_lines, lines, lines), language.name).joinToString("  ·  ")
        }
    Text(
        text,
        color = palette.gutterText,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(palette.gutter).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * The read-only view: one row per line, drawn lazily so a file of any length scrolls smoothly. The
 * whole file is lexed once and each line takes its share of the tokens, so a comment or string that
 * spans lines is coloured correctly here too.
 */
@Composable
private fun CodeReader(
    content: String,
    language: CodeLanguage,
    palette: EditorPalette,
    listState: LazyListState,
    fontSize: Float,
    wrap: Boolean,
    matches: List<IntRange>,
    currentMatch: Int,
) {
    val lineStarts = remember(content) { lineStartsOf(content) }
    val tokens = remember(content, language) { SyntaxHighlighter.tokenize(content, language) }
    val numbersList = rememberLazyListState()
    val hScroll = rememberScrollState()
    val numberWidth = (maxOf(3, lineStarts.size.toString().length) * fontSize * 0.62f + 16).dp
    val lineHeight = (fontSize * 1.5f).sp

    fun lineText(index: Int): AnnotatedString {
        val start = lineStarts[index]
        val end = if (index + 1 < lineStarts.size) lineStarts[index + 1] - 1 else content.length
        return highlightRange(content, start, end, tokens, palette, matches, currentMatch)
    }

    LaunchedEffect(listState, wrap) {
        if (wrap) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> numbersList.scrollToItem(index, offset) }
    }

    SelectionContainer {
        if (wrap) {
            // Each row carries its own number, so a wrapped line keeps it beside its first row.
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lineStarts.size) { index ->
                    Row {
                        LineNumber(index, numberWidth, fontSize, lineHeight, palette)
                        Text(
                            lineText(index),
                            color = palette.foreground,
                            fontSize = fontSize.sp,
                            lineHeight = lineHeight,
                            fontFamily = CodeFontFamily,
                            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 12.dp),
                        )
                    }
                }
            }
        } else {
            // Numbers in their own list, kept in step, so they stay put while the code scrolls sideways.
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = numbersList, userScrollEnabled = false, modifier = Modifier.background(palette.gutter)) {
                    items(lineStarts.size) { index -> LineNumber(index, numberWidth, fontSize, lineHeight, palette) }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).horizontalScroll(hScroll)) {
                    items(lineStarts.size) { index ->
                        Text(
                            lineText(index),
                            color = palette.foreground,
                            fontSize = fontSize.sp,
                            lineHeight = lineHeight,
                            fontFamily = CodeFontFamily,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 10.dp, end = 32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineNumber(
    index: Int,
    width: androidx.compose.ui.unit.Dp,
    fontSize: Float,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    palette: EditorPalette,
) {
    androidx.compose.foundation.text.selection.DisableSelection {
        Text(
            text = (index + 1).toString(),
            color = palette.gutterText,
            fontSize = (fontSize * 0.85f).sp,
            lineHeight = lineHeight,
            fontFamily = CodeFontFamily,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(width).background(palette.gutter).padding(end = 8.dp),
        )
    }
}

/** [content] from [start] to [end] with the tokens and matches that fall in it, shifted to start at 0. */
internal fun highlightRange(
    content: String,
    start: Int,
    end: Int,
    tokens: List<Token>,
    palette: EditorPalette,
    matches: List<IntRange>,
    currentMatch: Int,
): AnnotatedString {
    val text = content.substring(start, end)
    val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
    // Tokens are in order: find the first that ends after this line starts.
    var low = 0
    var high = tokens.size
    while (low < high) {
        val mid = (low + high) / 2
        if (tokens[mid].end <= start) low = mid + 1 else high = mid
    }
    var i = low
    while (i < tokens.size && tokens[i].start < end) {
        val token = tokens[i]
        spans += AnnotatedString.Range(palette.styleOf(token.kind), maxOf(token.start, start) - start, minOf(token.end, end) - start)
        i++
    }
    matches.forEachIndexed { index, range ->
        if (range.first < end && range.last >= start) {
            val colour = if (index == currentMatch) palette.currentMatch else palette.match
            spans +=
                AnnotatedString.Range(
                    SpanStyle(background = colour),
                    maxOf(range.first, start) - start,
                    minOf(range.last + 1, end) - start,
                )
        }
    }
    return AnnotatedString(text, spans)
}

@Composable
private fun GoToLineDialog(
    lineCount: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val line = text.toIntOrNull()
    val valid = line != null && line in 1..lineCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_go_to_line)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.editor_line_range, lineCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { line?.let(onGo) }, enabled = valid) { Text(stringResource(R.string.editor_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
