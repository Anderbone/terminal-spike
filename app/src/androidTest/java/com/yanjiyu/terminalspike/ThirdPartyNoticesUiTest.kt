package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yanjiyu.terminalspike.ui.settings.SettingsCategoryListContentDescription
import org.junit.Rule
import org.junit.Test

class ThirdPartyNoticesUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun packagedThirdPartyNoticesAreAccessibleFromAbout() {
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("About Terminal Spike").assertIsDisplayed()
        composeRule.onNodeWithText("Third-party notices").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("third_party_notices_body")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("third_party_notices_body")
            .assertTextContains("# Third-party notices", substring = true)
            .assertTextContains("## JSch", substring = true)
            .assertTextContains("## JZlib portion", substring = true)
            .assertTextContains("## jBCrypt portion", substring = true)
            .assertTextContains("## Apache License 2.0", substring = true)
    }
}
