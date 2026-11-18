package com.yanjiyu.terminalspike

import androidx.annotation.StringRes
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
import org.junit.Rule
import org.junit.Test

class ThirdPartyNoticesUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(@StringRes id: Int): String = composeRule.activity.getString(id)

    @Test
    fun packagedThirdPartyNoticesAreAccessibleFromAbout() {
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(string(R.string.settings_category_about)))
        composeRule.onNodeWithText(string(R.string.settings_category_about)).assertIsDisplayed().performClick()

        composeRule.onNodeWithText(string(R.string.about_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.about_third_party_notices)).assertIsDisplayed()
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
