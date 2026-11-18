package com.yanjiyu.terminalspike

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import com.yanjiyu.terminalspike.ui.settings.AdvancedSettingsListTestTag
import org.junit.Rule
import org.junit.Test

class DeveloperSettingsNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(@StringRes id: Int): String = composeRule.activity.getString(id)

    @Test
    fun rendererLabLivesUnderDebugDeveloperSettingsAndBackReturnsThere() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.developer_renderer_lab)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(string(R.string.settings_category_advanced)))
        composeRule.onNodeWithText(string(R.string.settings_category_advanced)).performClick()
        composeRule.onNodeWithTag(AdvancedSettingsListTestTag).performScrollToNode(hasText(string(R.string.settings_category_developer)))
        composeRule.onNodeWithText(string(R.string.settings_category_developer)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.developer_open_renderer_lab))
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithContentDescription(string(R.string.settings_screen_description)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.developer_open_renderer_lab)).assertIsDisplayed()
    }
}
