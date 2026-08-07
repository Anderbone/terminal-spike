package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun terminalContainerIsPresent() {
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
    }

    @Test
    fun sshConnectionDialogIsReachable() {
        composeRule.onNodeWithText("+ SSH").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("New SSH session").assertIsDisplayed()
        composeRule.onNodeWithText("Host").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        composeRule.onNodeWithText("Save password on this device").assertIsDisplayed()
    }

    @Test
    fun encryptedLocalToolsAreReachable() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TOOLS").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Local tools").assertIsDisplayed()
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Security").assertIsDisplayed()
        composeRule.onNodeWithText("Keys").assertIsDisplayed()
    }
}
