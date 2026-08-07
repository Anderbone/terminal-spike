package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Rule
import org.junit.Test

class TerminalInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun terminalAcceptsTapAndSwipeWithoutCrashing() {
        val terminal = composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()

        terminal.performTouchInput { click() }
        composeRule.waitForIdle()
        terminal.performTouchInput { swipeUp() }

        terminal.assertIsDisplayed()
    }
}
