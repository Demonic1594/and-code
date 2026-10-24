package com.yugahashimoto.andcode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelAndRuntimePickerSheetInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipingDownWhileModelListIsScrolledDoesNotDismissPicker() {
        var dismissed by mutableStateOf(false)

        composeRule.setContent {
            ModelAndRuntimePickerSheet(
                runtimeTargets = emptyList(),
                selectedRuntimeId = null,
                onSelectRuntime = {},
                providers = listOf(testProvider),
                selectedProviderId = testProvider.id,
                selectedModelId = null,
                onSelectModel = { _, _ -> },
                favoriteModelKeys = emptySet(),
                onToggleFavorite = { _, _ -> },
                onDismiss = { dismissed = true },
            )
        }

        val list = composeRule.onNode(hasScrollToIndexAction())
        list.assertIsDisplayed()
        list.performScrollToIndex(25)
        composeRule.onNode(hasScrollAction()).performTouchInput {
            swipe(Offset(200f, 240f), Offset(200f, 290f))
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(dismissed) }
    }

    @Test
    fun tappingStarTogglesFavoriteWithoutSelectingModel() {
        val toggled = mutableListOf<Pair<String, String>>()
        val selected = mutableListOf<Pair<String, String>>()

        composeRule.setContent {
            ModelAndRuntimePickerSheet(
                runtimeTargets = emptyList(),
                selectedRuntimeId = null,
                onSelectRuntime = {},
                providers = listOf(testProvider),
                selectedProviderId = testProvider.id,
                selectedModelId = null,
                onSelectModel = { providerId, modelId -> selected += providerId to modelId },
                favoriteModelKeys = emptySet(),
                onToggleFavorite = { providerId, modelId -> toggled += providerId to modelId },
                onDismiss = {},
            )
        }

        val star = composeRule.activity.getString(R.string.cd_favorite)
        composeRule.onAllNodesWithContentDescription(star)[0].performClick()

        composeRule.runOnIdle {
            assertEquals(1, toggled.size)
            assertTrue(selected.isEmpty())
        }
    }

    private companion object {
        val testProvider =
            OpenCodeProvider(
                id = "test-provider",
                name = "Test provider",
                models =
                    (0..60).associate { index ->
                        val id = "model-$index"
                        id to OpenCodeModel(id = id, name = "Model $index")
                    },
            )
    }
}
