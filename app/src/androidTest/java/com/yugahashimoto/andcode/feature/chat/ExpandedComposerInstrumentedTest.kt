package com.yugahashimoto.andcode.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A long message can be edited full screen: the expand button appears once the composer is full,
 * edits made there come back to the composer, and the editor stays open across a recreation.
 */
@RunWith(AndroidJUnit4::class)
class ExpandedComposerInstrumentedTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val longMessage = (1..6).joinToString("\n") { "line $it" }

    @Test
    fun aFullComposerOpensFullScreenAndKeepsTheEdits() {
        composeTestRule.onNodeWithTag("chat-message-input").performTextInput(longMessage)

        composeTestRule.onNodeWithTag("chat-expand-composer").performClick()
        composeTestRule.onNodeWithTag("chat-expanded-input").performTextInput(" and more")
        composeTestRule.onNodeWithTag("chat-collapse-composer").performClick()

        composeTestRule.onNodeWithTag("chat-message-input").assertTextContains("and more", substring = true)
    }

    @Test
    fun theFullScreenEditorSurvivesActivityRecreation() {
        composeTestRule.onNodeWithTag("chat-message-input").performTextInput(longMessage)
        composeTestRule.onNodeWithTag("chat-expand-composer").performClick()

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chat-expanded-input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat-expanded-input").assertTextContains("line 6", substring = true)
    }
}
