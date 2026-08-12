package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import com.yanjiyu.terminalspike.ui.settings.SettingsCategoryListContentDescription
import org.junit.Rule
import org.junit.Test

class DeveloperSettingsNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rendererLabLivesUnderDebugDeveloperSettingsAndBackReturnsThere() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Renderer lab").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Developer"))
        composeRule.onNodeWithText("Developer").performClick()
        composeRule.onNodeWithContentDescription("Open renderer lab")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithContentDescription("Settings screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open renderer lab").assertIsDisplayed()
    }
}
