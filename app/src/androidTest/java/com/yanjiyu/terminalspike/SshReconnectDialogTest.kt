package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.ui.MoshExtensionUiKind
import com.yanjiyu.terminalspike.ui.MoshExtensionUiState
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.SshConnectDialog
import com.yanjiyu.terminalspike.ui.SshConnectPurpose
import com.yanjiyu.terminalspike.ui.SshConnectionSeed
import com.yanjiyu.terminalspike.ui.SshConnectPasswordTestTag
import com.yanjiyu.terminalspike.ui.RemoteConnectionOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.hamcrest.Matchers.equalTo

class SshReconnectDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unsavedReconnectPrefillsEndpointButRequiresFreshAuthentication() {
        val seed = SshConnectionSeed(
            host = "adhoc.example",
            port = 2222,
            username = "operator",
        )
        var submission: DialogSubmission? = null
        setDialog(
            seed = seed,
            purpose = SshConnectPurpose.RECONNECT,
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Reconnect SSH session").assertIsDisplayed()
        composeRule.onNodeWithText("adhoc.example").assertIsDisplayed()
        composeRule.onNodeWithText("operator").assertIsDisplayed()
        composeRule.onNodeWithText("2222").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnect").assertIsNotEnabled()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag)))
            .perform(replaceText("fresh credential"))
        composeRule.onNodeWithText("Reconnect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("adhoc.example", submission?.host)
            assertEquals("2222", submission?.port)
            assertEquals("operator", submission?.username)
            assertEquals("fresh credential", submission?.password)
            assertNull(submission?.selectedProfileId)
        }
        composeRule.onNodeWithText("fresh credential").assertDoesNotExist()
        composeRule.onNodeWithText("Reconnect").assertIsNotEnabled()
    }

    @Test
    fun duplicateCanResolveASavedPasswordOnlyWhenTheSeedStillMatchesItsProfile() {
        val profile = SavedSshProfile(
            id = 7L,
            label = "Production",
            host = "prod.example",
            port = 22,
            username = "deploy",
            hasSavedPassword = true,
        )
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("prod.example", 22, "deploy", sourceProfileId = 7L),
            purpose = SshConnectPurpose.DUPLICATE,
            profile = profile,
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Duplicate SSH session").assertIsDisplayed()
        composeRule.onNodeWithText("Password saved on device").assertIsDisplayed()
        composeRule.onNodeWithText("Duplicate").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals("", submission?.password)
            assertEquals(7L, submission?.selectedProfileId)
        }
    }

    @Test
    fun changedSavedProfileCannotDonateCredentialsToAnOlderSessionEndpoint() {
        val changedProfile = SavedSshProfile(
            id = 7L,
            label = "Production",
            host = "new.example",
            port = 22,
            username = "deploy",
            hasSavedPassword = true,
        )
        setDialog(
            seed = SshConnectionSeed("old.example", 22, "deploy", sourceProfileId = 7L),
            purpose = SshConnectPurpose.RECONNECT,
            profile = changedProfile,
        )

        composeRule.onNodeWithText("old.example").assertIsDisplayed()
        composeRule.onNodeWithText("Password saved on device").assertDoesNotExist()
        composeRule.onNodeWithText("Reconnect").assertIsNotEnabled()
    }

    @Test
    fun unavailableMoshCannotStartAndOffersAnExplicitSshFallback() {
        val seed = SshConnectionSeed(
            host = "mobile.example",
            port = 22,
            username = "operator",
            connectionOptions = RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH),
        )
        var submission: DialogSubmission? = null
        setDialog(
            seed = seed,
            purpose = SshConnectPurpose.RECONNECT,
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Reconnect Mosh session").assertIsDisplayed()
        composeRule.onNodeWithText("Mosh").assertIsNotEnabled()
        composeRule.onNodeWithText("Reconnect").assertIsNotEnabled()
        composeRule.onNodeWithText("SSH").performClick()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag)))
            .perform(replaceText("fresh credential"))
        composeRule.onNodeWithText("Reconnect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(ConnectionProtocol.SSH, submission?.connectionOptions?.protocol)
        }
    }

    @Test
    fun verifiedMoshSubmitsTypedRangeAndExecutableOptions() {
        val seed = SshConnectionSeed("mobile.example", 22, "operator")
        var submission: DialogSubmission? = null
        setDialog(
            seed = seed,
            purpose = SshConnectPurpose.NEW,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Mosh").assertIsEnabled().performClick()
        composeRule.onNodeWithText("UDP port or range (optional)")
            .performTextInput("60000:60010")
        composeRule.onNodeWithText("Server executable")
            .performTextReplacement("/usr/local/bin/mosh-server")
        onView(withTagValue(equalTo(SshConnectPasswordTestTag)))
            .perform(replaceText("fresh credential"))
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            val options = submission?.connectionOptions
            assertEquals(ConnectionProtocol.MOSH, options?.protocol)
            assertEquals(60_000, options?.moshPortRange?.first)
            assertEquals(60_010, options?.moshPortRange?.last)
            assertEquals("/usr/local/bin/mosh-server", options?.moshServerCommand)
        }
    }

    @Test
    fun newConnectionPreservesCheckedSavePasswordWhileTransferringTheSecret() {
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("saved.example", 22, "operator"),
            purpose = SshConnectPurpose.NEW,
            onConnect = { submission = it },
        )

        onView(withTagValue(equalTo(SshConnectPasswordTestTag)))
            .perform(replaceText("saved credential"))
        composeRule.onNodeWithText("Save password on this device").performClick()
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("saved credential", submission?.password)
            assertEquals(true, submission?.savePassword)
            assertEquals(true, submission?.saveProfile)
        }
    }

    private fun setDialog(
        seed: SshConnectionSeed,
        purpose: SshConnectPurpose,
        profile: SavedSshProfile? = null,
        moshExtension: MoshExtensionUiState = MoshExtensionUiState(
            kind = MoshExtensionUiKind.ABSENT,
            statusLabel = UiText.Dynamic("Not installed"),
            summary = UiText.Dynamic("Unavailable"),
            verificationMessage = UiText.Dynamic("Not verified"),
        ),
        onConnect: (DialogSubmission) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                SshConnectDialog(
                    profiles = listOfNotNull(profile),
                    identities = emptyList(),
                    initialProfile = profile,
                    settingsReady = true,
                    onDismiss = {},
                    onConnect = {
                            host, port, username, password, identityId, passphrase,
                            saveProfile, selectedProfileId, savePassword,
                            connectionOptions, _,
                        ->
                        onConnect(
                            DialogSubmission(
                                host = host,
                                port = port,
                                username = username,
                                password = password.concatToString().also {
                                    password.fill('\u0000')
                                },
                                identityId = identityId,
                                passphrase = passphrase.concatToString().also {
                                    passphrase.fill('\u0000')
                                },
                                saveProfile = saveProfile,
                                selectedProfileId = selectedProfileId,
                                savePassword = savePassword,
                                connectionOptions = connectionOptions,
                            ),
                        )
                    },
                    onForgetSavedPassword = {},
                    initialSeed = seed,
                    purpose = purpose,
                    moshExtension = moshExtension,
                )
            }
        }
        composeRule.waitForIdle()
    }
}

private fun availableMoshUiState(): MoshExtensionUiState = MoshExtensionUiState(
    kind = MoshExtensionUiKind.AVAILABLE,
    statusLabel = UiText.Dynamic("Available"),
    summary = UiText.Dynamic("Ready"),
    verificationMessage = UiText.Dynamic("Verified"),
)

private data class DialogSubmission(
    val host: String,
    val port: String,
    val username: String,
    val password: String,
    val identityId: Long?,
    val passphrase: String,
    val saveProfile: Boolean,
    val selectedProfileId: Long?,
    val savePassword: Boolean,
    val connectionOptions: RemoteConnectionOptions,
)
