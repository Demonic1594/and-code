package com.yugahashimoto.andcode.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A long message can be edited full screen: the expand button appears once the composer is full,
 * edits made there come back to the composer, and the editor stays open across a recreation.
 */
@RunWith(AndroidJUnit4::class)
class ExpandedComposerInstrumentedTest {
    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    /** A clean install starts on onboarding, where there is no composer: skip it before launch. */
    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(
                object : ExternalResource() {
                    private val settings by lazy {
                        SecureSettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
                    }
                    private var onboardingWasCompleted = false

                    override fun before() {
                        onboardingWasCompleted = settings.onboardingCompleted
                        settings.onboardingCompleted = true
                    }

                    // After the activity is gone, so the device keeps the onboarding state it had.
                    override fun after() {
                        settings.onboardingCompleted = onboardingWasCompleted
                    }
                },
            ).around(composeTestRule)

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
