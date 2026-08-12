package com.yanjiyu.terminalspike

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.core.security.applock.AndroidAppLockAuthenticator
import com.yanjiyu.terminalspike.core.security.applock.AppLockAvailability
import com.yanjiyu.terminalspike.core.security.applock.AppLockUiState
import com.yanjiyu.terminalspike.ui.AppLockGate
import com.yanjiyu.terminalspike.ui.AppLockGateTestTag
import com.yanjiyu.terminalspike.ui.AppLockReviewResetTestTag
import com.yanjiyu.terminalspike.ui.AppLockRetryTestTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockRuntimeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun biometricPromptPermissionIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(Manifest.permission.USE_BIOMETRIC, context.packageName),
        )
    }

    @Test
    fun platformAvailabilityRequiresAConfiguredDeviceCredential() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val expected = when {
            keyguard == null -> AppLockAvailability.UNSUPPORTED
            keyguard.isDeviceSecure -> AppLockAvailability.AVAILABLE
            else -> AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED
        }

        assertEquals(expected, AndroidAppLockAuthenticator.availabilityFor(context))
    }

    @Test
    fun settingsReadFailureExplainsFailClosedBehaviorAndCanRetry() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                AppLockGate(
                    state = AppLockUiState.SettingsUnavailable,
                    onAuthenticate = {},
                    onRetrySettings = { retried = true },
                    onOpenSecuritySettings = {},
                    onResetConfirmed = { false },
                )
            }
        }

        composeRule.onNodeWithTag(AppLockGateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("No app data was deleted.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AppLockRetryTestTag).performClick()
        assertEquals(true, retried)
    }

    @Test
    fun missingDeviceCredentialHasNoContentBypass() {
        composeRule.setContent {
            MaterialTheme {
                AppLockGate(
                    state = AppLockUiState.Unavailable(
                        AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED,
                    ),
                    onAuthenticate = {},
                    onRetrySettings = {},
                    onOpenSecuritySettings = {},
                    onResetConfirmed = { false },
                )
            }
        }

        composeRule.onNodeWithTag(AppLockGateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("App lock unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
    }

    @Test
    fun unreadableStartupSettingsExposeTwoStepFullResetWithCancelAndFailureFeedback() {
        var resetRequests = 0
        composeRule.setContent {
            MaterialTheme {
                AppLockGate(
                    state = AppLockUiState.SettingsUnavailable,
                    onAuthenticate = {},
                    onRetrySettings = {},
                    onOpenSecuritySettings = {},
                    onResetConfirmed = {
                        resetRequests += 1
                        false
                    },
                )
            }
        }

        composeRule.onNodeWithTag(AppLockReviewResetTestTag).performClick()
        composeRule.onNodeWithText("Permanently reset local data?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep encrypted data").performClick()
        composeRule.onNodeWithText("Settings unavailable").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, resetRequests) }

        composeRule.onNodeWithTag(AppLockReviewResetTestTag).performClick()
        composeRule.onNodeWithText("Reset local data").performClick()
        composeRule.onNodeWithText(
            "Android could not erase local app data. Clear storage in the app’s system settings.",
        ).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, resetRequests) }
    }
}
