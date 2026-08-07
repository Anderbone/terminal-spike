package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class MainScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun terminalContainerIsPresent() {
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
    }
}
