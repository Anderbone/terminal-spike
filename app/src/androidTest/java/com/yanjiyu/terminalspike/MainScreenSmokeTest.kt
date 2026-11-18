package com.yanjiyu.terminalspike

import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
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
import com.yanjiyu.terminalspike.ui.NewTerminalSessionTestTag
import com.yanjiyu.terminalspike.ui.NewTerminalSessionVisualTestTag
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertHeightIsAtLeast
import com.yanjiyu.terminalspike.ui.BufferedInputDraftStore
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.TerminalChromeTitleTestTag
import com.yanjiyu.terminalspike.ui.TerminalEmptyStateTestTag
import com.yanjiyu.terminalspike.ui.settings.AdvancedSettingsListTestTag
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.Assert.assertFalse

class MainScreenSmokeTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()
    private val draftFixture = object : ExternalResource() {
        private lateinit var store: BufferedInputDraftStore
        private var original = ""

        override fun before() {
            store = BufferedInputDraftStore(InstrumentationRegistry.getInstrumentation().targetContext)
            original = store.load()
            store.save("")
        }

        override fun after() {
            store.save(original)
        }
    }

    // The ViewModel loads its persisted draft during construction. Restore it only after the
    // Activity and its input callbacks have closed, including when a test assertion fails.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(draftFixture).around(composeRule)

    @Test
    fun connectionsAreTheLaunchDestination() {
        composeRule.onNodeWithContentDescription(string(R.string.connections_screen_description)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.connections_add_host)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_category_ssh_keys)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.settings_category_snippets)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.developer_renderer_lab)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.settings_known_hosts_heading)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_connections)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_terminal)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).assertIsDisplayed()
    }

    @Test
    fun terminalDestinationIsCleanAndRendererLabRemainsDeveloperOnly() {
        openTerminal()

        composeRule.onNodeWithTag(TerminalChromeTitleTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            string(R.string.terminal_predictive_back_destination, string(R.string.navigation_connections)),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalEmptyStateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.terminal_no_session_open)).assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.terminal_no_session_detail),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(NewTerminalSessionTestTag)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(NewTerminalSessionVisualTestTag, useUnmergedTree = true)
            .assertWidthIsEqualTo(30.dp)
            .assertHeightIsEqualTo(30.dp)
        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_connections)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_terminal)).assertIsSelected()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_container").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_terminal)).performClick()
        composeRule.onNodeWithTag(TerminalEmptyStateTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_connections)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.connections_add_host)).assertIsDisplayed()

        openRendererLab()
        composeRule.onNodeWithTag("terminal_container").assertIsDisplayed()
        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertDoesNotExist()
    }

    @Test
    fun addHostDialogIsReachable() {
        composeRule.onNodeWithContentDescription(string(R.string.connections_add_host)).performClick()

        composeRule.onNodeWithText(string(R.string.host_editor_add_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.host_editor_connection_name_optional))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.host_editor_hostname))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun encryptedConnectionsAreReachable() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.connections_screen_description)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.workspace_active_sessions)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.connections_add_host)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_category_ssh_keys)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.settings_category_snippets)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.connections_back)).assertDoesNotExist()
    }

    @Test
    fun knownHostsAreManagedOnlyUnderSettingsSecurity() {
        composeRule.onNodeWithText(string(R.string.settings_known_hosts_heading)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(composeRule.activity.getString(R.string.settings_category_advanced)))
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_category_advanced)).performClick()
        composeRule.onNodeWithTag(AdvancedSettingsListTestTag)
            .performScrollToNode(hasText(string(R.string.settings_category_security)))
        composeRule.onNodeWithText(string(R.string.settings_category_security)).performClick()

        composeRule.onNodeWithTag(SecuritySettingsTestTag)
            .performScrollToNode(hasText(string(R.string.settings_known_hosts_heading)))
        composeRule.onNodeWithText(string(R.string.settings_known_hosts_heading)).assertIsDisplayed()
    }

    @Test
    fun sshKeysAndSnippetsAreManagedFromSettings() {
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(string(R.string.settings_category_ssh_keys)))
        composeRule.onNodeWithText(string(R.string.settings_category_ssh_keys)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(string(R.string.settings_category_snippets)))
        composeRule.onNodeWithText(string(R.string.settings_category_snippets)).assertIsDisplayed()
    }

    @Test
    fun extraKeyEditorOpensFromSettings() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(string(R.string.settings_category_keyboard)))
        composeRule.onNodeWithText(string(R.string.settings_category_keyboard)).performClick()

        composeRule.onNodeWithContentDescription(string(R.string.settings_screen_description)).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(KeyboardPreviewTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(KeyboardSettingsTestTag)
            .performScrollToNode(hasText(string(R.string.settings_edit_keyboard_keys)))
        composeRule.onNodeWithText(string(R.string.settings_edit_keyboard_keys)).performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.settings_edit_keyboard_keys_summary),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.save)).assertIsDisplayed()
    }

    @Test
    fun shortcutPageShowsDirectControlChords() {
        openRendererLab()
        composeRule.onNodeWithContentDescription(string(R.string.terminal_key_control_c)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.terminal_key_control_w)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.terminal_more_keys_description)).assertIsDisplayed()
    }

    @Test
    fun rendererLabBufferedInputStagesTextAndPreservesTerminalFocusRouting() {
        openRendererLab()
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        val bufferedInput = composeRule.onNodeWithContentDescription(string(R.string.terminal_buffered_input_description))
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
        composeRule.onNodeWithContentDescription(string(R.string.terminal_key_description, "ESC")).assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription(string(R.string.settings_screen_description)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.developer_open_renderer_lab)).assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription(string(R.string.settings_screen_description)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.developer_open_renderer_lab)).assertIsDisplayed()
    }

    private fun string(@StringRes resource: Int, vararg arguments: Any): String =
        composeRule.activity.getString(resource, *arguments)

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
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_terminal)).performClick()
    }

    private fun openRendererLab() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_open_settings)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_category_list_description))
            .performScrollToNode(hasText(string(R.string.settings_category_advanced)))
        composeRule.onNodeWithText(string(R.string.settings_category_advanced)).performClick()
        composeRule.onNodeWithTag(AdvancedSettingsListTestTag).performScrollToNode(hasText(string(R.string.settings_category_developer)))
        composeRule.onNodeWithText(string(R.string.settings_category_developer)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.developer_open_renderer_lab)).performClick()
    }
}
