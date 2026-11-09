package com.yugahashimoto.andcode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The user's own messages are drawn as Markdown, the way the agent reads them. */
@RunWith(AndroidJUnit4::class)
class ChatUserMarkdownInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun userBubbleRendersEmphasisListsAndCode() {
        val text = "Please **check** the build:\n\n- run `npm test`\n- then lint\n\n```sh\nnpm run lint\n```"
        composeRule.setContent {
            MessageBubble(ChatMessage(isUser = true, parts = listOf(ChatPart.Text("m1", text))))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Please check the build:").assertExists()
        composeRule.onNodeWithText("run npm test").assertExists()
        composeRule.onNodeWithText("npm run lint").assertExists()
        // The markers are read, not shown.
        composeRule.onNodeWithText("**", substring = true).assertDoesNotExist()
    }
}
