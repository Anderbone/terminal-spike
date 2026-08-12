package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionError
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionVersion
import com.yanjiyu.terminalspike.connection.mosh.MoshNegotiatedProtocol
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.ui.MoshExtensionRefreshTestTag
import com.yanjiyu.terminalspike.ui.MoshExtensionInstallationHelpCloseTestTag
import com.yanjiyu.terminalspike.ui.MoshExtensionInstallationHelpDialogTestTag
import com.yanjiyu.terminalspike.ui.MoshExtensionInstallationHelpTestTag
import com.yanjiyu.terminalspike.ui.MoshExtensionSection
import com.yanjiyu.terminalspike.ui.MoshExtensionStatusTestTag
import com.yanjiyu.terminalspike.ui.toUiState
import com.yanjiyu.terminalspike.ui.resolve
import com.yanjiyu.terminalspike.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MoshExtensionSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun availableStateShowsNegotiationTrustAndLicenceFactsAndRefreshes() {
        var refreshCount = 0
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val state = MoshExtensionStatus.Available(
            version = MoshExtensionVersion(7, "1.0.0-mosh-1.4.0"),
            protocol = MoshNegotiatedProtocol(
                extensionApiVersion = 1,
                negotiatedApiVersion = 1,
                capabilityFlags = MoshCapability.IPV4 or
                    MoshCapability.IPV6 or
                    MoshCapability.NETWORK_ROAMING or
                    MoshCapability.MULTIPLE_SESSIONS,
                maximumConcurrentSessions = 4,
            ),
        ).toUiState()
        val capabilities = state.details.single {
            it.label == uiText(R.string.mosh_detail_capabilities)
        }.value.resolve(resources)
        composeRule.setContent {
            MaterialTheme {
                MoshExtensionSection(
                    state = state,
                    onRefresh = { refreshCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(MoshExtensionStatusTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Available").assertIsDisplayed()
        composeRule.onNodeWithText("1.0.0-mosh-1.4.0 (code 7)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(capabilities)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("4").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Package signature verified against this app before binding.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Free software and source").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("GPL-3.0-or-later", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("not affiliated with or endorsed by the Mosh project", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(MoshExtensionInstallationHelpTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        composeRule.onNodeWithText("Install extension").assertDoesNotExist()

        composeRule.onNodeWithTag(MoshExtensionRefreshTestTag).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, refreshCount) }
    }

    @Test
    fun absentStateOffersARealRetryAndLocalInstallationHelp() {
        var retryCount = 0
        composeRule.setContent {
            MaterialTheme {
                MoshExtensionSection(
                    state = MoshExtensionStatus.Absent.toUiState(),
                    onRefresh = { retryCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Not installed").assertIsDisplayed()
        composeRule.onNodeWithText("separately installed, matching companion APK", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("SSH remains available without it", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }

        composeRule.onNodeWithTag(MoshExtensionInstallationHelpTestTag)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(MoshExtensionInstallationHelpDialogTestTag).assertIsDisplayed()
        composeRule.onNodeWithText(":mosh-extension:assembleDebug", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("complete corresponding source", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Retry only asks Android to check again", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        composeRule.onNodeWithText("Install extension").assertDoesNotExist()
        composeRule.onNodeWithTag(MoshExtensionInstallationHelpCloseTestTag).performClick()
        composeRule.onNodeWithTag(MoshExtensionInstallationHelpDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun checkingDoesNotShowANonfunctionalControl() {
        composeRule.setContent {
            MaterialTheme {
                MoshExtensionSection(
                    state = MoshExtensionStatus.Checking.toUiState(),
                    onRefresh = { error("Checking must not expose refresh") },
                )
            }
        }

        composeRule.onNodeWithText("Checking…").assertIsDisplayed()
        composeRule.onNodeWithTag(MoshExtensionRefreshTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(MoshExtensionInstallationHelpTestTag).assertDoesNotExist()
    }

    @Test
    fun closedClientDoesNotShowANonfunctionalControl() {
        composeRule.setContent {
            MaterialTheme {
                MoshExtensionSection(
                    state = MoshExtensionStatus.Error(
                        version = null,
                        reason = MoshExtensionError.CLIENT_CLOSED,
                    ).toUiState(),
                    onRefresh = { error("Closed client must not expose retry") },
                )
            }
        }

        composeRule.onNodeWithText("Extension check failed").assertIsDisplayed()
        composeRule.onNodeWithTag(MoshExtensionRefreshTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(MoshExtensionInstallationHelpTestTag).assertDoesNotExist()
    }
}
