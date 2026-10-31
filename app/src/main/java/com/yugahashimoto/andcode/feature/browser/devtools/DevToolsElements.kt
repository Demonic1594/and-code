package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.components.CopyButton
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

/** A node of the Elements tree as it is drawn: the node, how deep it sits, and whether it is open. */
internal data class TreeRow(
    val node: DomNode,
    val depth: Int,
    val expanded: Boolean,
)

/** The rows on screen: [root] and, under every expanded node, the children loaded for it. */
internal fun flattenTree(
    root: DomNode,
    children: Map<Int, List<DomNode>>,
    expanded: Set<Int>,
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun visit(
        node: DomNode,
        depth: Int,
    ) {
        val open = node.id in expanded
        rows += TreeRow(node, depth, open)
        if (open) children[node.id].orEmpty().forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
    return rows
}

private enum class ElementSection(
    val label: Int,
) {
    STYLES(R.string.devtools_styles),
    COMPUTED(R.string.devtools_computed),
    ATTRIBUTES(R.string.devtools_attributes),
    BOX(R.string.devtools_box_model),
    HTML(R.string.devtools_html),
}

@Composable
internal fun ElementsTab(
    bridge: DevToolsBridge,
    modifier: Modifier = Modifier,
) {
    var doctype by remember { mutableStateOf<String?>(null) }
    var root by remember { mutableStateOf<DomNode?>(null) }
    val children = remember { mutableStateMapOf<Int, List<DomNode>>() }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var details by remember { mutableStateOf<ElementDetails?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val inspected by bridge.store.inspected.collectAsState()
    val listState = rememberLazyListState()

    fun loadChildren(
        id: Int,
        then: () -> Unit = {},
    ) {
        bridge.call("children", id) { raw ->
            children[id] = DevToolsProtocol.parseChildren(raw)
            then()
        }
    }

    fun select(id: Int) {
        selectedId = id
        bridge.call("highlight", id)
        bridge.call("details", id) { details = DevToolsProtocol.parseDetails(it) }
    }

    fun reload() {
        children.clear()
        bridge.call("root") { raw ->
            val parsed = DevToolsProtocol.parseRoot(raw)
            doctype = parsed?.first
            root = parsed?.second
            parsed?.second?.let { html ->
                children[html.id] = DevToolsProtocol.parseRootChildren(raw)
                // <html> and <body> open, as a browser's Elements panel starts.
                val body = children[html.id].orEmpty().firstOrNull { it.tag == "body" }
                expanded = setOfNotNull(html.id, body?.id)
                body?.let { loadChildren(it.id) }
            }
            loaded = true
        }
    }

    // A new page has a new document, and the agent in it has never seen the old node ids.
    val page by bridge.store.page.collectAsState()
    LaunchedEffect(page) {
        selectedId = null
        details = null
        reload()
    }
    // An element picked on the page: open the tree down to it, one level at a time, then select it.
    LaunchedEffect(inspected) {
        val pick = inspected ?: return@LaunchedEffect
        bridge.store.consumeInspected()
        inspecting = false
        if (root == null) reload()

        fun openPath(index: Int) {
            if (index >= pick.path.size - 1) {
                select(pick.id)
                return
            }
            val id = pick.path[index]
            expanded = expanded + id
            // Read again even when cached: the picked element may have been added since.
            loadChildren(id) { openPath(index + 1) }
        }
        openPath(0)
    }

    val rows = remember(root, children.toMap(), expanded) { root?.let { flattenTree(it, children, expanded) }.orEmpty() }
    LaunchedEffect(selectedId, rows.size) {
        val index = rows.indexOfFirst { it.node.id == selectedId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconToggleButton(
                checked = inspecting,
                onCheckedChange = {
                    inspecting = it
                    bridge.call("setInspecting", it)
                },
            ) { Icon(Icons.Outlined.AdsClick, contentDescription = stringResource(R.string.devtools_inspect)) }
            Text(
                if (inspecting) stringResource(R.string.devtools_inspect_hint) else breadcrumb(rows, selectedId),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (inspecting) null else CodeFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
            )
            IconButton(onClick = ::reload) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.devtools_refresh)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val currentRoot = root
        if (currentRoot == null) {
            DevToolsEmpty(stringResource(if (loaded) R.string.devtools_elements_empty else R.string.devtools_loading))
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(if (details != null) 0.45f else 1f).fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            doctype?.let { item { TreeText(AnnotatedString(it), depth = 0, color = valueColors().muted) } }
            items(rows, key = { it.node.id }) { row ->
                TreeNodeRow(
                    row = row,
                    selected = row.node.id == selectedId,
                    onToggle = {
                        if (row.expanded) {
                            expanded = expanded - row.node.id
                        } else {
                            expanded = expanded + row.node.id
                            if (children[row.node.id] == null) loadChildren(row.node.id)
                        }
                    },
                    onSelect = { if (row.node.type == 1) select(row.node.id) },
                )
            }
        }
        details?.let { element ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ElementDetailsPane(
                element = element,
                bridge = bridge,
                onChanged = {
                    select(element.id)
                    // The tree row shows attributes too: reload the parent's children to match.
                    rows.firstOrNull { it.node.id == element.id }?.let { row ->
                        val parent = rows.take(rows.indexOf(row)).lastOrNull { it.depth == row.depth - 1 }
                        parent?.let { loadChildren(it.node.id) }
                    }
                },
                onRemoved = {
                    details = null
                    selectedId = null
                    reload()
                },
                modifier = Modifier.weight(0.55f),
            )
        }
    }
}

private fun breadcrumb(
    rows: List<TreeRow>,
    selectedId: Int?,
): String {
    val index = rows.indexOfFirst { it.node.id == selectedId }
    if (index < 0) return ""
    val path = mutableListOf(rows[index])
    var depth = rows[index].depth
    for (i in index - 1 downTo 0) {
        if (rows[i].depth < depth) {
            path.add(0, rows[i])
            depth = rows[i].depth
        }
    }
    return path.joinToString(" › ") { row -> selectorOf(row.node) }
}

/** `tag#id.class` for a node, as breadcrumbs and copied selectors show it. */
internal fun selectorOf(node: DomNode): String {
    if (node.type != 1) return if (node.type == 3) "#text" else "#comment"
    val id = node.attributes.firstOrNull { it.name == "id" }?.value?.takeIf { it.isNotBlank() }
    val classes =
        node.attributes.firstOrNull { it.name == "class" }?.value?.trim()?.split(
            Regex("\\s+"),
        )?.filter { it.isNotBlank() }.orEmpty()
    return node.tag + (id?.let { "#$it" } ?: "") + classes.take(2).joinToString("") { ".$it" }
}

@Composable
private fun TreeText(
    text: AnnotatedString,
    depth: Int,
    color: Color = Color.Unspecified,
) {
    Text(
        text,
        fontFamily = CodeFontFamily,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(start = (8 + depth * 14).dp, end = Spacing.md, top = 1.dp, bottom = 1.dp),
    )
}

@Composable
private fun TreeNodeRow(
    row: TreeRow,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = valueColors()
    val tagColor = colors.function
    val node = row.node
    val text =
        buildAnnotatedString {
            when (node.type) {
                1 -> {
                    withStyle(SpanStyle(color = tagColor)) { append("<${node.tag}") }
                    node.attributes.forEach { attribute ->
                        append(" ")
                        withStyle(SpanStyle(color = colors.key)) { append(attribute.name) }
                        if (attribute.value.isNotEmpty()) {
                            withStyle(SpanStyle(color = colors.muted)) { append("=") }
                            withStyle(SpanStyle(color = colors.string)) { append("\"${attribute.value.take(80)}\"") }
                        }
                    }
                    withStyle(SpanStyle(color = tagColor)) { append(">") }
                    when {
                        node.text != null && node.childCount <= 1 -> {
                            append(node.text.take(80))
                            withStyle(SpanStyle(color = tagColor)) { append("</${node.tag}>") }
                        }
                        node.childCount > 0 && !row.expanded -> {
                            withStyle(SpanStyle(color = colors.muted)) { append("…") }
                            withStyle(SpanStyle(color = tagColor)) { append("</${node.tag}>") }
                        }
                        node.childCount == 0 -> withStyle(SpanStyle(color = tagColor)) { append("</${node.tag}>") }
                    }
                }
                3 -> append("\"${node.text.orEmpty().take(120)}\"")
                else -> withStyle(SpanStyle(color = colors.muted)) { append("<!-- ${node.text.orEmpty().take(80)} -->") }
            }
        }
    val openable = node.type == 1 && node.childCount > 0 && !(node.text != null && node.childCount <= 1)
    Row(
        modifier =
            Modifier
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                .clickable(onClick = onSelect)
                .padding(start = (4 + row.depth * 14).dp, end = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp).clickable(enabled = openable, onClick = onToggle), contentAlignment = Alignment.Center) {
            if (openable) {
                Icon(
                    if (row.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(text, fontFamily = CodeFontFamily, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ElementDetailsPane(
    element: ElementDetails,
    bridge: DevToolsBridge,
    onChanged: () -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableStateOf(ElementSection.STYLES) }
    var computedQuery by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Header?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(
                selectedTabIndex = section.ordinal,
                edgePadding = Spacing.sm,
                divider = {},
                modifier = Modifier.weight(1f),
            ) {
                ElementSection.entries.forEach { item ->
                    Tab(
                        selected = item == section,
                        onClick = { section = item },
                        text = { Text(stringResource(item.label), fontSize = 12.sp) },
                    )
                }
            }
            IconButton(onClick = { bridge.call("scrollIntoView", element.id) }) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = stringResource(R.string.devtools_scroll_into_view))
            }
            CopyButton(text = { element.outerHtml }, contentDescription = stringResource(R.string.devtools_copy_html), iconSize = 24.dp)
            IconButton(onClick = { bridge.call("remove", element.id) { onRemoved() } }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.devtools_delete_node))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier.fillMaxSize().verticalScroll(
                        rememberScrollState(),
                    ).padding(horizontal = Spacing.md, vertical = Spacing.xs),
            ) {
                when (section) {
                    ElementSection.STYLES -> {
                        StyleRule("element.style", element.inlineStyle, null)
                        element.rules.asReversed().forEach { StyleRule(it.selector, it.css, it.source) }
                        if (element.rules.isEmpty() && element.inlineStyle.isBlank()) {
                            Text(
                                stringResource(R.string.devtools_no_rules),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ElementSection.COMPUTED -> {
                        OutlinedTextField(
                            value = computedQuery,
                            onValueChange = { computedQuery = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.devtools_filter_hint)) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                        )
                        element.computed
                            .filter {
                                computedQuery.isBlank() ||
                                    it.name.contains(
                                        computedQuery,
                                        ignoreCase = true,
                                    ) || it.value.contains(computedQuery, ignoreCase = true)
                            }
                            .forEach { DevToolsKeyValue(it.name, it.value, keyWidth = 170.dp) }
                    }
                    ElementSection.ATTRIBUTES -> {
                        element.attributes.forEach { attribute ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { editing = attribute },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DevToolsKeyValue(attribute.name, attribute.value, modifier = Modifier.weight(1f))
                            }
                        }
                        TextButton(onClick = { adding = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.devtools_add_attribute), modifier = Modifier.padding(start = Spacing.xs))
                        }
                        if (element.text.isNotBlank()) {
                            DevToolsSectionTitle(stringResource(R.string.devtools_text_content))
                            Text(element.text, fontSize = 12.sp)
                        }
                    }
                    ElementSection.BOX -> element.box?.let { BoxModelDiagram(it) }
                    ElementSection.HTML -> CodeBlock(element.outerHtml)
                }
            }
        }
    }
    editing?.let { attribute ->
        AttributeDialog(
            initialName = attribute.name,
            initialValue = attribute.value,
            nameEditable = false,
            onDismiss = { editing = null },
            onSave = { _, value ->
                editing = null
                bridge.call("setAttribute", element.id, attribute.name, value) { onChanged() }
            },
            onRemove = {
                editing = null
                bridge.call("setAttribute", element.id, attribute.name, null) { onChanged() }
            },
        )
    }
    if (adding) {
        AttributeDialog(
            initialName = "",
            initialValue = "",
            nameEditable = true,
            onDismiss = { adding = false },
            onSave = { name, value ->
                adding = false
                if (name.isNotBlank()) bridge.call("setAttribute", element.id, name.trim(), value) { onChanged() }
            },
            onRemove = null,
        )
    }
}

@Composable
private fun StyleRule(
    selector: String,
    css: String,
    source: String?,
) {
    val colors = valueColors()
    val declarations = css.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row {
            Text(selector, fontFamily = CodeFontFamily, fontSize = 12.sp, color = colors.function, modifier = Modifier.weight(1f))
            source?.let {
                Text(
                    it.substringAfterLast('/'),
                    fontSize = 10.sp,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
        declarations.forEach { declaration ->
            val name = declaration.substringBefore(':').trim()
            val value = declaration.substringAfter(':', "").trim()
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.key)) { append(name) }
                    append(": ")
                    append(value)
                    append(";")
                },
                fontFamily = CodeFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = Spacing.md),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = Spacing.xs),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

/** The nested margin / border / padding / content boxes, with their sizes, as DevTools draws them. */
@Composable
private fun BoxModelDiagram(box: BoxModel) {
    fun side(values: List<Double>) = values.map { if (it == 0.0) "–" else it.toCompact() }

    @Composable
    fun Layer(
        label: String,
        values: List<String>,
        color: Color,
        content: @Composable () -> Unit,
    ) {
        Column(
            modifier =
                Modifier
                    .background(color)
                    .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.25f)), RoundedCornerShape(0.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, fontSize = 9.sp, color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Start))
            Text(values[0], fontSize = 10.sp, color = Color.Black)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(values[3], fontSize = 10.sp, color = Color.Black)
                content()
                Text(values[1], fontSize = 10.sp, color = Color.Black)
            }
            Text(values[2], fontSize = 10.sp, color = Color.Black)
        }
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        Layer("margin", side(box.margin), Color(0xFFF9CC9D)) {
            Layer("border", side(box.border), Color(0xFFFDDD9B)) {
                Layer("padding", side(box.padding), Color(0xFFC3D08B)) {
                    Box(
                        modifier = Modifier.background(Color(0xFF8CB6C0)).padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("${box.width.toCompact()} × ${box.height.toCompact()}", fontSize = 11.sp, color = Color.Black) }
                }
            }
        }
    }
    DevToolsKeyValue("x, y", "${box.x.toCompact()}, ${box.y.toCompact()}")
}

private fun Double.toCompact(): String = if (this == Math.floor(this)) toLong().toString() else "%.1f".format(this)

@Composable
private fun AttributeDialog(
    initialName: String,
    initialValue: String,
    nameEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initialName) }
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (nameEditable) R.string.devtools_add_attribute else R.string.devtools_edit_attribute)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = nameEditable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.devtools_attribute_name)) },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.devtools_attribute_value)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, value) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                onRemove?.let {
                    TextButton(
                        onClick = it,
                    ) { Text(stringResource(R.string.devtools_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
