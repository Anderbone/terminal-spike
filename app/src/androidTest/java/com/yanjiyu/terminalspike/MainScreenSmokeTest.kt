package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun localWorkspaceIsLaunchDestination() {
        composeRule.onNodeWithText("Local workspace").assertIsDisplayed()
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Renderer lab").assertIsDisplayed()
        composeRule.onNodeWithText("Workspace").assertIsDisplayed()
    }

    @Test
    fun terminalContainerIsReachable() {
        composeRule.onNodeWithContentDescription("Open terminal").performClick()

        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
    }

    @Test
    fun sshConnectionDialogIsReachable() {
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
        composeRule.onNodeWithText("+ SSH").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("New SSH session").assertIsDisplayed()
        composeRule.onNodeWithText("Host").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        composeRule.onNodeWithText("Save password on this device").assertIsDisplayed()
    }

    @Test
    fun encryptedLocalToolsAreReachable() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open local tools").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Local tools").assertIsDisplayed()
        composeRule.onNodeWithText("Local workspace").assertDoesNotExist()
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Security").assertIsDisplayed()
        composeRule.onNodeWithText("Keys").assertIsDisplayed()
    }

    @Test
    fun extraKeyEditorOpensDirectlyFromToolbar() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
        composeRule.onNodeWithContentDescription("Customize terminal keys")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Local tools").assertIsDisplayed()
        composeRule.onNodeWithText("Terminal keys").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Tap keys below to show or hide them. Tap the live deck to reorder. Changes apply immediately.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("LIVE DECK · 13 KEYS").assertIsDisplayed()
        composeRule.onNodeWithText("Modifiers").assertIsDisplayed()
    }

    @Test
    fun extraKeysUseTwoFixedRows() {
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
        val firstRowStart = composeRule.onNodeWithContentDescription("Terminal key ESC")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val firstRowEnd = composeRule.onNodeWithContentDescription("Terminal key ←")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val secondRowStart = composeRule.onNodeWithContentDescription("Terminal key →")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val secondRowEnd = composeRule.onNodeWithContentDescription("Customize terminal keys")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        assertEquals(firstRowStart.top, firstRowEnd.top, 1f)
        assertEquals(secondRowStart.top, secondRowEnd.top, 1f)
        assertTrue(secondRowStart.top > firstRowStart.bottom)
    }
}
