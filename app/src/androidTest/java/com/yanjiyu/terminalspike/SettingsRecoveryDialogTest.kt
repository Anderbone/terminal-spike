package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.ui.SettingsRecoveryDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsRecoveryDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recoveryIsPersistentAndResetRequiresASecondExplicitAction() {
        var retryCount = 0
        var resetCount = 0
        composeRule.setContent {
            MaterialTheme {
                SettingsRecoveryDialog(
                    failure = SettingsLoadFailure.KEY_UNAVAILABLE,
                    inProgress = false,
                    onRetry = { retryCount += 1 },
                    onResetConfirmed = { resetCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Encrypted local data needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }

        composeRule.onNodeWithText("Review reset").performClick()
        composeRule.onNodeWithText("Permanently reset local data?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Android will permanently erase all Terminal Spike data on this device, including " +
                "saved hosts, snippets, imported keys, passwords, trusted-host records, settings, " +
                "and local terminal history. The app will close while Android completes the reset; " +
                "reopen it for a fresh setup. Nothing is retained for recovery, and this cannot " +
                "be undone.",
        ).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, resetCount) }
        composeRule.onNodeWithText("Reset local data").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
    }

    @Test
    fun unavailableRoomOrProtoDataExposesTheSameTwoStepFullReset() {
        var retryCount = 0
        var resetCount = 0
        composeRule.setContent {
            MaterialTheme {
                SettingsRecoveryDialog(
                    failure = SettingsLoadFailure.APP_DATA_UNAVAILABLE,
                    inProgress = false,
                    onRetry = { retryCount += 1 },
                    onResetConfirmed = { resetCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Local data needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }

        composeRule.onNodeWithText("Review reset").performClick()
        composeRule.onNodeWithText("Permanently reset local data?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep encrypted data").performClick()
        composeRule.onNodeWithText("Local data needs attention").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, resetCount) }

        composeRule.onNodeWithText("Review reset").performClick()
        composeRule.onNodeWithText("Reset local data").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
    }
}
