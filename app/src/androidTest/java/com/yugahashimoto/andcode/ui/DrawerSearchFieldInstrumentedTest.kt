package com.yugahashimoto.andcode.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerSearchFieldInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyFieldIsLabelledByItsHint() {
        setField()

        composeRule.onNodeWithTag(FIELD).assert(hasText(HINT))
    }

    @Test
    fun typedTextKeepsTheLabelAndIsStillRead() {
        setField()

        composeRule.onNodeWithTag(FIELD).performTextInput("tests")

        composeRule.onNodeWithTag(FIELD).assert(hasText("tests")).assert(hasContentDescription(HINT))
        // A description on the field itself would make TalkBack read it in place of the typed text.
        composeRule
            .onNodeWithTag(FIELD, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    private fun setField() {
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            DrawerSearchField(query = query, onQueryChange = { query = it }, hint = HINT)
        }
    }

    private companion object {
        const val FIELD = "drawer-search-field"
        const val HINT = "Search chats"
    }
}
