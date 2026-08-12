package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.ui.settings.KeyboardPreviewTestTag
import com.yanjiyu.terminalspike.ui.settings.KeyboardSettingsTestTag
import com.yanjiyu.terminalspike.ui.settings.SecuritySettingsTestTag
import com.yanjiyu.terminalspike.ui.settings.SettingsCategoryListContentDescription
import org.junit.Rule
import org.junit.Test

class MainScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun localWorkspaceIsLaunchDestination() {
        composeRule.onNodeWithTag("workspace-app-bar").assertIsDisplayed()
        composeRule.onNodeWithText("Active sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Saved hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Recent connections").assertIsDisplayed()
        composeRule.onNodeWithText("Renderer lab").assertDoesNotExist()
        composeRule.onNodeWithText("Known hosts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open terminal").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open connections").assertDoesNotExist()
    }

    @Test
    fun terminalDestinationIsCleanAndRendererLabRemainsDeveloperOnly() {
        openTerminal()

        composeRule.onNodeWithText("No terminal session is open.").assertIsDisplayed()
        composeRule.onNodeWithTag("new-terminal-session").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_container").assertDoesNotExist()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("workspace-app-bar").assertIsDisplayed()

        openRendererLab()
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
    }

    @Test
    fun sshConnectionDialogIsReachable() {
        composeRule.onNodeWithContentDescription("Start a new SSH connection").performClick()

        composeRule.onNodeWithText("New SSH session").assertIsDisplayed()
        composeRule.onNodeWithText("Host").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        composeRule.onNodeWithText("Save password on this device").assertIsDisplayed()
    }

    @Test
    fun encryptedConnectionsAreReachable() {
        composeRule.waitForIdle()
        openConnectionsCatalog()

        composeRule.onNodeWithContentDescription("Connections screen").assertIsDisplayed()
        composeRule.onNodeWithText("Active sessions").assertDoesNotExist()
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Keys").assertIsDisplayed()
        composeRule.onNodeWithText("Snippets").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back from connections").performClick()
        composeRule.onNodeWithTag("workspace-app-bar").assertIsDisplayed()
    }

    @Test
    fun knownHostsAreManagedOnlyUnderSettingsSecurity() {
        composeRule.onNodeWithText("Known hosts").assertDoesNotExist()
        openConnectionsCatalog()
        composeRule.onNodeWithText("Known hosts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Security"))
        composeRule.onNodeWithText("Security").performClick()

        composeRule.onNodeWithTag(SecuritySettingsTestTag)
            .performScrollToNode(hasText("Known hosts"))
        composeRule.onNodeWithText("Known hosts").assertIsDisplayed()
    }

    @Test
    fun extraKeyEditorOpensFromSettings() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Keyboard"))
        composeRule.onNodeWithText("Keyboard").performClick()

        composeRule.onNodeWithContentDescription("Settings screen").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(KeyboardPreviewTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(KeyboardSettingsTestTag)
            .performScrollToNode(hasText("Edit accessory keys"))
        composeRule.onNodeWithText("Edit accessory keys").performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.settings_edit_keyboard_keys_summary),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun shortcutPageShowsDirectControlChords() {
        openRendererLab()
        composeRule.onNodeWithContentDescription("Control C").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Control W").assertIsDisplayed()
    }

    @Test
    fun bufferedInputPageStagesTextAndClearsOnlyAfterSend() {
        openRendererLab()
        val pager = composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()

        pager.performTouchInput { swipeRight() }
        val bufferedInput = composeRule.onNodeWithContentDescription("Buffered terminal input")
            .assertIsDisplayed()
        bufferedInput.performTextInput("git status --short")
        composeRule.onNodeWithText("git status --short").assertIsDisplayed()

        composeRule.onNodeWithTag("terminal_container").performTouchInput { click() }
        bufferedInput.assertIsFocused()

        composeRule.onNodeWithText("Send").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.terminal_buffered_input_placeholder),
        ).assertIsDisplayed()

        pager.performTouchInput { swipeLeft() }
        composeRule.onNodeWithContentDescription("Terminal key ESC").assertIsDisplayed()
        onView(isAssignableFrom(FastTerminalView::class.java)).check(matches(hasFocus()))
    }

    private fun openTerminal() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
    }

    private fun openRendererLab() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Developer"))
        composeRule.onNodeWithText("Developer").performClick()
        composeRule.onNodeWithContentDescription("Open renderer lab").performClick()
    }

    private fun openConnectionsCatalog() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Workspace destinations").performClick()
        composeRule.onNodeWithContentDescription("Open Connections from Workspace menu").performClick()
    }
}
