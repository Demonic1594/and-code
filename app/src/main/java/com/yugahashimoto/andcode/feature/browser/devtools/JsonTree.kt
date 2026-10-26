package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Colours for values, close to what browser consoles use, readable on both light and dark panels. */
internal data class ValueColors(
    val string: Color,
    val number: Color,
    val keyword: Color,
    val key: Color,
    val muted: Color,
    val function: Color,
)

@Composable
internal fun valueColors(): ValueColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        ValueColors(
            string = Color(0xFFF28B54),
            number = Color(0xFF9980FF),
            keyword = Color(0xFF7CACF8),
            key = Color(0xFF5DB0D7),
            muted = Color(0xFF9AA0A6),
            function = Color(0xFFD7A1F5),
        )
    } else {
        ValueColors(
            string = Color(0xFFC41A16),
            number = Color(0xFF1C00CF),
            keyword = Color(0xFF0D22AA),
            key = Color(0xFF881391),
            muted = Color(0xFF5F6368),
            function = Color(0xFF7B2EAD),
        )
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** One value, coloured by kind, on one line: the collapsed form of [JsonTree]. */
internal fun styledPreview(
    value: JsonElement,
    colors: ValueColors,
    topLevel: Boolean = true,
): AnnotatedString =
    buildAnnotatedString {
        when (value) {
            is JsonNull -> withStyle(SpanStyle(color = colors.keyword)) { append("null") }
            is JsonPrimitive ->
                when {
                    value.isString ->
                        if (topLevel) {
                            append(
                                value.content,
                            )
                        } else {
                            withStyle(SpanStyle(color = colors.string)) { append("\"${value.content}\"") }
                        }
                    value.content == "true" || value.content == "false" ->
                        withStyle(
                            SpanStyle(color = colors.keyword),
                        ) { append(value.content) }
                    else -> withStyle(SpanStyle(color = colors.number)) { append(value.content) }
                }
            is JsonObject -> {
                val special = specialPreview(value)
                val marker = (value["__t"] as? JsonPrimitive)?.contentOrNull
                if (special != null) {
                    val color =
                        when (marker) {
                            "undefined" -> colors.muted
                            "function" -> colors.function
                            "number", "bigint" -> colors.number
                            "error" -> Color.Unspecified
                            else -> colors.muted
                        }
                    withStyle(SpanStyle(color = color, fontStyle = if (marker == "function") FontStyle.Italic else FontStyle.Normal)) {
                        append(special)
                    }
                } else {
                    withStyle(SpanStyle(color = if (topLevel) Color.Unspecified else colors.muted)) { append(previewOf(value, !topLevel)) }
                }
            }
            is JsonArray ->
                withStyle(
                    SpanStyle(color = if (topLevel) Color.Unspecified else colors.muted),
                ) { append(previewOf(value, !topLevel)) }
        }
    }

private fun JsonElement.isExpandable(): Boolean =
    when (this) {
        is JsonArray -> isNotEmpty()
        is JsonObject -> {
            val marker = (this["__t"] as? JsonPrimitive)?.contentOrNull
            marker == null && isNotEmpty() || marker == "map" || marker == "set" || marker == "error" && this["stack"] != null
        }
        else -> false
    }

private fun JsonElement.childEntries(): List<Pair<String, JsonElement>> =
    when (this) {
        is JsonArray -> mapIndexed { index, element -> index.toString() to element }
        is JsonObject ->
            when ((this["__t"] as? JsonPrimitive)?.contentOrNull) {
                "map" -> (this["entries"] as? JsonObject)?.entries?.map { it.key to it.value }.orEmpty()
                "set" -> (this["items"] as? JsonArray)?.mapIndexed { index, element -> index.toString() to element }.orEmpty()
                "error" -> listOf("stack" to (this["stack"] ?: JsonNull))
                else -> entries.filter { it.key != "__class" }.map { it.key to it.value }
            }
        else -> emptyList()
    }

/**
 * A value the page logged or returned, expandable level by level like a console's object viewer.
 * [label] is the property name it sits under, or null at the top.
 */
@Composable
internal fun JsonTree(
    value: JsonElement,
    modifier: Modifier = Modifier,
    label: String? = null,
    initiallyExpanded: Boolean = false,
) {
    val colors = valueColors()
    var expanded by remember(value) { mutableStateOf(initiallyExpanded) }
    val expandable = value.isExpandable()
    Column(modifier = modifier) {
        Row(
            modifier = if (expandable) Modifier.clickable { expanded = !expanded } else Modifier,
            verticalAlignment = Alignment.Top,
        ) {
            if (expandable) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(16.dp),
                )
            } else if (label != null) {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text =
                    buildAnnotatedString {
                        if (label != null) {
                            withStyle(SpanStyle(color = colors.key)) { append(label) }
                            append(": ")
                        }
                        append(styledPreview(value, colors, topLevel = label == null))
                    },
                fontFamily = CodeFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        if (expanded && expandable) {
            Column(modifier = Modifier.padding(start = 14.dp)) {
                if (value is JsonObject && (value["__t"] as? JsonPrimitive)?.contentOrNull == "error") {
                    Text(
                        text = (value["stack"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        fontFamily = CodeFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.muted,
                    )
                } else {
                    value.childEntries().forEach { (key, child) -> JsonTree(child, label = key) }
                }
            }
        }
    }
}
