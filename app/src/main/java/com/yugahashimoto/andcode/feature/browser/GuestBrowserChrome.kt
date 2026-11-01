package com.yugahashimoto.andcode.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.Spacing

/** What kind of address the bar shows, for its icon. */
internal enum class AddressKind { LOCAL, SECURE, INSECURE, NONE }

/**
 * The host and port of [url], without any `user:password@` in front: an address like
 * `https://bank.example:x@evil.example/` must show, and be judged as, evil.example.
 */
internal fun authorityOf(url: String): Pair<String, String?> {
    val authority =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return if (authority.startsWith("[")) {
        val host = authority.substringBefore(']') + "]"
        host.lowercase() to authority.substringAfter("]:", "").ifEmpty { null }
    } else {
        authority.substringBefore(':').lowercase() to authority.substringAfter(':', "").ifEmpty { null }
    }
}

internal fun addressKindOf(url: String): AddressKind {
    if (url.isBlank()) return AddressKind.NONE
    val host = authorityOf(url).first
    return when {
        host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "[::1]" ||
            host.endsWith(
                ".localhost",
            )
        -> AddressKind.LOCAL
        url.startsWith("https://", ignoreCase = true) -> AddressKind.SECURE
        else -> AddressKind.INSECURE
    }
}

/** The host, plus the port for a local server, as the address bar shows a page it is not editing. */
internal fun displayHost(url: String): String {
    if (!url.contains("://")) return url
    val (host, port) = authorityOf(url)
    return if (addressKindOf(url) == AddressKind.LOCAL && port != null) "$host:$port" else host.removePrefix("www.")
}

/**
 * The address bar: close on the left, the page's title and host in a pill that turns into an
 * address field on tap, and reload (stop while loading) on the right.
 */
@Composable
internal fun BrowserAddressBar(
    url: String,
    title: String,
    editing: Boolean,
    loading: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.guest_browser_close)) }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            if (editing) {
                AddressField(initial = url, onSubmit = onSubmit, onCancel = { onEditingChange(false) })
            } else {
                AddressSummary(url = url, title = title, onClick = { onEditingChange(true) })
            }
        }
        if (loading) {
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.guest_browser_stop)) }
        } else {
            IconButton(onClick = onReload, enabled = url.isNotBlank()) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.guest_browser_reload))
            }
        }
    }
}

@Composable
private fun AddressSummary(
    url: String,
    title: String,
    onClick: () -> Unit,
) {
    val kind = addressKindOf(url)
    val editLabel = stringResource(R.string.guest_browser_edit_address)
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = editLabel
                }.padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint, label) =
            when (kind) {
                AddressKind.LOCAL -> Triple(Icons.Outlined.Dns, MaterialTheme.colorScheme.primary, R.string.guest_browser_security_local)
                AddressKind.SECURE ->
                    Triple(
                        Icons.Default.Lock,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        R.string.guest_browser_security_secure,
                    )
                AddressKind.INSECURE ->
                    Triple(
                        Icons.Outlined.Info,
                        MaterialTheme.colorScheme.error,
                        R.string.guest_browser_security_insecure,
                    )
                AddressKind.NONE -> Triple(Icons.Default.Search, MaterialTheme.colorScheme.onSurfaceVariant, null)
            }
        Icon(icon, contentDescription = label?.let { stringResource(it) }, tint = tint, modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
            if (url.isBlank()) {
                Text(
                    stringResource(R.string.guest_browser_url_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    displayHost(url),
                    style = if (title.isBlank()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                    color = if (title.isBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddressField(
    initial: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // The whole address selected, so typing replaces it and a tap places the cursor instead.
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier = Modifier.fillMaxSize().padding(start = Spacing.lg, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.text.isEmpty()) {
                Text(
                    stringResource(R.string.guest_browser_url_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                keyboardActions = KeyboardActions(onGo = { if (value.text.isNotBlank()) onSubmit(value.text) }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { state ->
                            // Tapping away from the field puts the page's address back.
                            if (hadFocus && !state.isFocused && initial.isNotBlank()) onCancel()
                            hadFocus = state.isFocused
                        },
            )
        }
        if (value.text.isNotEmpty()) {
            IconButton(onClick = { value = TextFieldValue("") }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.guest_browser_clear_address),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal class BrowserMenuActions(
    val onFind: () -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onHardReload: () -> Unit,
    val onCopyLink: () -> Unit,
    val onShare: () -> Unit,
    val onOpenExternal: () -> Unit,
    /** Whether agents may drive this browser; see the Guest Browser's DevTools socket. */
    val agentControl: Boolean,
    val onToggleAgentControl: () -> Unit,
)

/** The controls along the bottom, where a thumb reaches them. */
@Composable
internal fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    desktopMode: Boolean,
    devToolsOpen: Boolean,
    errorCount: Int,
    warningCount: Int,
    onHistoryBack: () -> Unit,
    onForward: () -> Unit,
    onToggleDevTools: () -> Unit,
    onToggleDesktop: () -> Unit,
    actions: BrowserMenuActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(enabled = canGoBack, onClick = onHistoryBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.guest_browser_back))
                }
                IconButton(enabled = canGoForward, onClick = onForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.guest_browser_forward))
                }
                IconToggleButton(checked = devToolsOpen, onCheckedChange = { onToggleDevTools() }) {
                    BadgedBox(
                        badge = {
                            if (errorCount + warningCount > 0) {
                                Badge(
                                    containerColor = if (errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                ) { Text((if (errorCount > 0) errorCount else warningCount).coerceAtMost(99).toString()) }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Code,
                            contentDescription = stringResource(R.string.guest_browser_devtools),
                            tint = if (devToolsOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconToggleButton(checked = desktopMode, onCheckedChange = { onToggleDesktop() }) {
                    Icon(
                        if (desktopMode) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                        contentDescription = stringResource(R.string.guest_browser_desktop_site),
                        tint = if (desktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.guest_browser_more))
                    }
                    BrowserMenu(expanded = menuOpen, actions = actions, onDismiss = { menuOpen = false })
                }
            }
        }
    }
}

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    actions: BrowserMenuActions,
    onDismiss: () -> Unit,
) {
    @Composable
    fun item(
        label: Int,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        onClick: () -> Unit,
    ) = DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            onDismiss()
            onClick()
        },
    )

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        item(R.string.guest_browser_find, Icons.Default.Search, actions.onFind)
        item(R.string.guest_browser_zoom_in, Icons.Default.ZoomIn, actions.onZoomIn)
        item(R.string.guest_browser_zoom_out, Icons.Default.ZoomOut, actions.onZoomOut)
        HorizontalDivider()
        item(R.string.guest_browser_hard_reload, Icons.Default.Refresh, actions.onHardReload)
        item(R.string.guest_browser_copy_link, Icons.Default.ContentCopy, actions.onCopyLink)
        item(R.string.guest_browser_share, Icons.Default.Share, actions.onShare)
        item(R.string.guest_browser_open_external, Icons.Default.OpenInBrowser, actions.onOpenExternal)
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.guest_browser_agent_control)) },
            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            trailingIcon = { Checkbox(checked = actions.agentControl, onCheckedChange = null) },
            onClick = actions.onToggleAgentControl,
            // The checkbox only draws the state; the item carries it for TalkBack.
            modifier =
                Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(actions.agentControl)
                },
        )
    }
}

@Composable
internal fun FindInPageBar(
    query: String,
    match: FindMatch,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(50),
            placeholder = { Text(stringResource(R.string.guest_browser_find_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
        )
        if (query.isNotEmpty()) {
            Text(
                text = stringResource(R.string.guest_browser_find_count, if (match.count == 0) 0 else match.active + 1, match.count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        IconButton(enabled = match.count > 0, onClick = onPrevious) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.guest_browser_find_previous))
        }
        IconButton(enabled = match.count > 0, onClick = onNext) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.guest_browser_find_next))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.guest_browser_find_close))
        }
    }
}

/** In place of a page that could not be loaded: what happened and a way to try again. */
@Composable
internal fun PageErrorState(
    error: PageLoadError,
    onRetry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.guest_browser_error_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.lg),
            )
            Text(
                displayHost(error.url),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Text(
                stringResource(
                    if (addressKindOf(
                            error.url,
                        ) == AddressKind.LOCAL
                    ) {
                        R.string.guest_browser_error_hint_local
                    } else {
                        R.string.guest_browser_error_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.md).widthIn(max = 360.dp),
            )
            if (error.description.isNotBlank()) {
                Text(
                    error.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            Button(onClick = onRetry, modifier = Modifier.padding(top = Spacing.xl)) { Text(stringResource(R.string.guest_browser_retry)) }
        }
    }
}

// The ports dev servers start on by default: Vite, Next/React, Python/Django, Angular, generic.
private val COMMON_PORTS = listOf(5173, 3000, 8000, 4200, 8080)

/** Before any page is open: a hint, and the local dev-server addresses a tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserStartState(onOpen: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.guest_browser_empty_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = Spacing.lg),
            )
            Text(
                stringResource(R.string.guest_browser_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.sm).widthIn(max = 360.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                modifier = Modifier.padding(top = Spacing.lg),
            ) {
                COMMON_PORTS.forEach { port ->
                    AssistChip(
                        onClick = { onOpen("http://localhost:$port") },
                        label = { Text("localhost:$port", style = TextStyle(fontSize = 13.sp)) },
                        leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}
