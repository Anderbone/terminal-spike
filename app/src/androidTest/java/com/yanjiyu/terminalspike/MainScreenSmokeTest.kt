package com.yanjiyu.terminalspike

import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.ui.settings.KeyboardPreviewTestTag
import com.yanjiyu.terminalspike.ui.settings.KeyboardSettingsTestTag
import com.yanjiyu.terminalspike.ui.settings.SecuritySettingsTestTag
import com.yanjiyu.terminalspike.ui.settings.SettingsCategoryListContentDescription
import com.yanjiyu.terminalspike.ui.NewTerminalSessionTestTag
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.TerminalChromeTitleTestTag
import com.yanjiyu.terminalspike.ui.TerminalEmptyStateTestTag
import com.yanjiyu.terminalspike.ui.settings.AdvancedSettingsListTestTag
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

class MainScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectionsAreTheLaunchDestination() {
        composeRule.onNodeWithContentDescription("Connections screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add host").assertIsDisplayed()
        composeRule.onNodeWithText("SSH keys").assertDoesNotExist()
        composeRule.onNodeWithText("Snippets").assertDoesNotExist()
        composeRule.onNodeWithText("Renderer lab").assertDoesNotExist()
        composeRule.onNodeWithText("Known hosts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open connections").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open terminal").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
    }

    @Test
    fun terminalDestinationIsCleanAndRendererLabRemainsDeveloperOnly() {
        openTerminal()

        composeRule.onNodeWithTag(TerminalChromeTitleTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to Connections").assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalEmptyStateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("No terminal session is open.").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Choose a saved connection to start a secure terminal session.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(NewTerminalSessionTestTag)
            .assertWidthIsEqualTo(30.dp)
            .assertHeightIsEqualTo(30.dp)
        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open connections").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open terminal").assertIsSelected()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_container").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
        composeRule.onNodeWithTag(TerminalEmptyStateTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open connections").performClick()
        composeRule.onNodeWithContentDescription("Add host").assertIsDisplayed()

        openRendererLab()
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertDoesNotExist()
    }

    @Test
    fun addHostDialogIsReachable() {
        composeRule.onNodeWithContentDescription("Add host").performClick()

        composeRule.onNodeWithText("Add host").assertIsDisplayed()
        composeRule.onNodeWithText("Connection name (optional)")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Hostname or IP")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun encryptedConnectionsAreReachable() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Connections screen").assertIsDisplayed()
        composeRule.onNodeWithText("Active sessions").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Add host").assertIsDisplayed()
        composeRule.onNodeWithText("SSH keys").assertDoesNotExist()
        composeRule.onNodeWithText("Snippets").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back from connections").assertDoesNotExist()
    }

    @Test
    fun knownHostsAreManagedOnlyUnderSettingsSecurity() {
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
    fun sshKeysAndSnippetsAreManagedFromSettings() {
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("SSH keys"))
        composeRule.onNodeWithText("SSH keys").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Snippets"))
        composeRule.onNodeWithText("Snippets").assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription("Control W").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open expanded terminal keyboard").assertIsDisplayed()
    }

    @Test
    fun rendererLabBufferedInputStagesTextAndPreservesTerminalFocusRouting() {
        openRendererLab()
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        val bufferedInput = composeRule.onNodeWithContentDescription("Buffered terminal input")
            .assertIsDisplayed()
        bufferedInput.performTextInput("git status --short")
        bufferedInput.assertIsFocused()
        closeSoftKeyboard()
        composeRule.onNodeWithText("git status --short").assertIsDisplayed()

        // Hidden IME restores native navigation even while the draft remains visible.
        composeRule.onNodeWithTag("terminal_container").performTouchInput { click() }
        composeRule.onNodeWithText("git status --short").assertIsDisplayed()
        // Re-enter typing explicitly; a terminal tap may stay in navigation on older APIs.
        bufferedInput.performClick().assertIsFocused()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithContentDescription("Terminal key ESC").assertIsDisplayed()
        onView(isAssignableFrom(FastTerminalView::class.java)).check(matches(hasFocus()))
    }

    @Test
    fun systemBackHidesDirectKeyboardBeforeLeavingTerminal() = withSoftwareKeyboard {
        openRendererLab()
        composeRule.onNodeWithTag("terminal_container").performTouchInput { click() }
        awaitKeyboard(visible = true)

        // Exercise the legacy pre-IME path even when this test runs on a newer Android.
        onView(isAssignableFrom(FastTerminalView::class.java)).check { view, _ ->
            assertFalse(view.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertFalse(view.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        }
        pressSystemBack()
        awaitKeyboard(visible = false)
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()

        pressSystemBack()
        composeRule.onNodeWithContentDescription("Settings screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open renderer lab").assertIsDisplayed()
    }

    @Test
    fun systemBackHidesBufferedKeyboardAndPreservesDraftBeforeLeavingTerminal() = withSoftwareKeyboard {
        openRendererLab()
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        val input = composeRule.onNodeWithTag("buffered_terminal_input")
        input.performClick().performTextInput("keep my unsent command")
        awaitKeyboard(visible = true)

        pressSystemBack()
        awaitKeyboard(visible = false)
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
        input.assert(hasText("keep my unsent command"))

        pressSystemBack()
        composeRule.onNodeWithContentDescription("Settings screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open renderer lab").assertIsDisplayed()
    }

    private fun pressSystemBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    private fun awaitKeyboard(visible: Boolean) {
        composeRule.waitUntil(5_000) {
            var matches = false
            composeRule.runOnUiThread {
                matches = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            matches
        }
    }

    private fun withSoftwareKeyboard(block: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        val setting = "show_ime_with_hard_keyboard"
        val previous = shell("settings get secure $setting")
        check(previous in listOf("null", "0", "1"))
        try {
            shell("settings put secure $setting 1")
            block()
        } finally {
            shell(if (previous == "null") "settings delete secure $setting"
                else "settings put secure $setting $previous")
        }
    }

    private fun openTerminal() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
    }

    private fun openRendererLab() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Advanced options"))
        composeRule.onNodeWithText("Advanced options").performClick()
        composeRule.onNodeWithTag(AdvancedSettingsListTestTag).performScrollToNode(hasText("Developer"))
        composeRule.onNodeWithText("Developer").performClick()
        composeRule.onNodeWithContentDescription("Open renderer lab").performClick()
    }
}
