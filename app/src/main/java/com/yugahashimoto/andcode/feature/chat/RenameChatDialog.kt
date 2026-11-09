package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.yugahashimoto.andcode.R

/**
 * The name to save for a chat from what was typed, or null when there is nothing to save: a blank
 * name (no runtime reads an empty title as "name it for me") or the name it already has.
 */
internal fun renamedChatTitle(
    typed: String,
    current: String,
): String? = typed.trim().takeIf { it.isNotEmpty() && it != current.trim() }

/**
 * Asks for a chat's new name. The field opens on the current name, all of it selected, so typing
 * replaces it and a tap places the cursor to edit it; the keyboard's Done saves, as Save does.
 */
@Composable
internal fun RenameChatDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var field by rememberSaveable(currentTitle, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentTitle, selection = TextRange(0, currentTitle.length)))
    }
    val newTitle = renamedChatTitle(field.text, currentTitle)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text(stringResource(R.string.chat_rename_label)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { newTitle?.let(onRename) }),
                modifier = Modifier.focusRequester(focusRequester).testTag("rename-chat-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { newTitle?.let(onRename) },
                enabled = newTitle != null,
                modifier = Modifier.testTag("rename-chat-save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
