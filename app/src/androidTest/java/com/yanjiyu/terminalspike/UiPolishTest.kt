package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.TmuxAvailability
import com.yanjiyu.terminalspike.connection.TmuxSession
import com.yanjiyu.terminalspike.connection.TmuxSessionCatalog
import com.yanjiyu.terminalspike.ui.ActiveTmuxSessionSwitcherDialog
import com.yanjiyu.terminalspike.ui.theme.AppEditorDialog
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class UiPolishTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun darkEditorKeepsSystemBarIconsLegible() {
        var dialogView: android.view.View? = null
        var dialogWindow: android.view.Window? = null
        composeRule.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.DARK, updateSystemBarIcons = false) {
                AppEditorDialog(
                    onDismissRequest = {}, title = { Text("Edit connection") },
                    text = {
                        val view = LocalView.current
                        val window = (view.parent as DialogWindowProvider).window
                        SideEffect { dialogView = view; dialogWindow = window }
                        Text("Connection settings")
                    },
                    confirmButton = { Button(onClick = {}) { Text("Save") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                )
            }
        }
        composeRule.runOnIdle {
            val controller = WindowCompat.getInsetsController(checkNotNull(dialogWindow), checkNotNull(dialogView))
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun longEditorKeepsItsTitleAndActionsVisibleAfterScrolling() {
        var saved = false
        composeRule.setContent {
            TerminalSpikeTheme(updateSystemBarIcons = false) {
                AppEditorDialog(
                    onDismissRequest = {},
                    title = { Text("Edit connection") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            repeat(30) { index ->
                                Text("Field $index", Modifier.fillMaxWidth().padding(16.dp))
                            }
                        }
                    },
                    confirmButton = { Button(onClick = { saved = true }) { Text("Save connection") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                )
            }
        }

        composeRule.onNodeWithText("Field 29").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Edit connection").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Save connection").assertIsDisplayed().performClick()
        composeRule.runOnIdle { check(saved) }
    }

    @Test
    fun closingRemoteSessionRequiresConfirmationAndCancelPreservesIt() {
        var deleted: String? = null
        composeRule.setContent {
            TerminalSpikeTheme(updateSystemBarIcons = false) {
                ActiveTmuxSessionSwitcherDialog(
                    catalog = TmuxSessionCatalog(
                        sessions = listOf(TmuxSession("session-id", "Production", 1, 0, 1)),
                        availability = TmuxAvailability.AVAILABLE,
                    ),
                    onDismiss = {}, onRefresh = {}, onSelect = {}, onDelete = { deleted = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Close tmux session Production").performClick()
        composeRule.runOnIdle { assertNull(deleted) }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertNull(deleted) }
        composeRule.onNodeWithContentDescription("Close tmux session Production").performClick()
        composeRule.onNodeWithText("Delete session").performClick()
        composeRule.runOnIdle { assertEquals("session-id", deleted) }
    }
}
