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
    fun cancellingQuickConnectKeepsTheWorkspaceVisible() {
        composeRule.onNodeWithContentDescription("Start a new SSH connection").performClick()
        composeRule.onNodeWithText("New SSH session").assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("New SSH session").assertDoesNotExist()
        composeRule.onNodeWithText("Active sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Renderer lab").assertDoesNotExist()
    }
}
