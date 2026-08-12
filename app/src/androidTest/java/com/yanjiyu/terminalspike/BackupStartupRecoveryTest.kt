package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataState
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeRecoveryFailure
import com.yanjiyu.terminalspike.ui.StartupRecoveryGate
import com.yanjiyu.terminalspike.ui.StartupRecoveryGateTestTag
import com.yanjiyu.terminalspike.ui.StartupRecoveryReviewResetTestTag
import com.yanjiyu.terminalspike.ui.StartupRecoveryRetryTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupStartupRecoveryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun checkingAndRecoveryUseAnOpaqueRootWithoutWorkspace() {
        var state by mutableStateOf<AuthoritativeDataState>(AuthoritativeDataState.Checking)
        composeRule.setContent {
            MaterialTheme {
                StartupRecoveryGate(
                    state = state,
                    onRetry = {},
                    onResetConfirmed = { false },
                )
            }
        }

        composeRule.onNodeWithTag(StartupRecoveryGateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Checking app data").assertIsDisplayed()
        composeRule.onNodeWithText("Active sessions").assertDoesNotExist()
        composeRule.onNodeWithText("Saved hosts").assertDoesNotExist()

        composeRule.runOnIdle { state = AuthoritativeDataState.RecoveryRequired }

        composeRule.onNodeWithText("Recovering app data").assertIsDisplayed()
        composeRule.onNodeWithText("Active sessions").assertDoesNotExist()
    }

    @Test
    fun failureIsGenericAndRetryableWithoutComposingWorkspace() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                StartupRecoveryGate(
                    state = AuthoritativeDataState.RecoveryFailed(
                        AuthoritativeRecoveryFailure.RECOVERY_UNAVAILABLE,
                    ),
                    onRetry = { retried = true },
                    onResetConfirmed = { false },
                )
            }
        }

        composeRule.onNodeWithTag(StartupRecoveryGateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("App data recovery needed").assertIsDisplayed()
        composeRule.onNodeWithText("Active sessions").assertDoesNotExist()
        composeRule.onNodeWithTag(StartupRecoveryRetryTestTag).performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun failedRecoveryResetRequiresReviewSupportsCancelAndReportsPlatformFailure() {
        var resetRequests = 0
        composeRule.setContent {
            MaterialTheme {
                StartupRecoveryGate(
                    state = AuthoritativeDataState.RecoveryFailed(
                        AuthoritativeRecoveryFailure.RECOVERY_UNAVAILABLE,
                    ),
                    onRetry = {},
                    onResetConfirmed = {
                        resetRequests += 1
                        false
                    },
                )
            }
        }

        composeRule.onNodeWithTag(StartupRecoveryReviewResetTestTag).performClick()
        composeRule.onNodeWithText("Permanently reset local data?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, resetRequests) }

        composeRule.onNodeWithText("Keep encrypted data").performClick()
        composeRule.onNodeWithText("App data recovery needed").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, resetRequests) }

        composeRule.onNodeWithTag(StartupRecoveryReviewResetTestTag).performClick()
        composeRule.onNodeWithText("Reset local data").performClick()
        composeRule.onNodeWithText(
            "Android could not erase local app data. Clear storage in the app’s system settings.",
        ).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, resetRequests) }
    }

    @Test
    fun acceptedPlatformResetInvokesCallbackOnceAndDisablesRepeatSubmission() {
        var resetRequests = 0
        composeRule.setContent {
            MaterialTheme {
                StartupRecoveryGate(
                    state = AuthoritativeDataState.RecoveryFailed(
                        AuthoritativeRecoveryFailure.RECOVERY_UNAVAILABLE,
                    ),
                    onRetry = {},
                    onResetConfirmed = {
                        resetRequests += 1
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithTag(StartupRecoveryReviewResetTestTag).performClick()
        composeRule.onNodeWithText("Reset local data").performClick()

        composeRule.onNodeWithText("Resetting…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, resetRequests) }
    }
}
