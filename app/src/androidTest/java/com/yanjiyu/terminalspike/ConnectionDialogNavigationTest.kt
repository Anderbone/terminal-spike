package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ConnectionDialogNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cancellingAddHostKeepsConnectionsVisible() {
        composeRule.onNodeWithContentDescription("Add host").performClick()
        composeRule.onNodeWithText("Add host").assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithContentDescription("Connections screen").assertIsDisplayed()
        composeRule.onNodeWithText("Renderer lab").assertDoesNotExist()
    }
}
