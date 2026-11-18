package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SavedHostConnectionCompatibility
import com.yanjiyu.terminalspike.connection.DEFAULT_MOSH_LOCALE
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
    val composeRule = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()

    @Test
    fun savedMoshDefaultsStillUseSavedPasswordForAnExplicitDefaultSeed() {
        val profile = SavedSshProfile(7, "Roaming", "mobile.example", 22, "operator",
            hasSavedPassword = true, protocol = ConnectionProtocol.MOSH,
            connectionCompatibility = SavedHostConnectionCompatibility.MOSH_PASSWORD)
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("mobile.example", 22, "operator", sourceProfileId = 7,
                connectionOptions = RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH,
                    moshLocale = DEFAULT_MOSH_LOCALE, moshServerCommand = "mosh-server")),
            purpose = SshConnectPurpose.DUPLICATE,
            profile = profile,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Password saved on device").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Duplicate").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(7L, submission?.selectedProfileId)
            assertEquals("", submission?.password)
            assertEquals(RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH), submission?.connectionOptions)
        }
    }

    @Test
    fun savedMoshChipRetainsCustomOptionsAndChangingScopeNeedsFreshPassword() {
        val profile = SavedSshProfile(7, "Roaming", "mobile.example", 22, "operator",
            hasSavedPassword = true, protocol = ConnectionProtocol.MOSH,
            connectionCompatibility = SavedHostConnectionCompatibility.MOSH_PASSWORD,
            moshLocale = "en_GB.UTF-8", moshFallbackPolicy = MoshFallbackPolicy.ASK)
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("other.example", 22, "operator"),
            purpose = SshConnectPurpose.NEW,
            profile = profile,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Roaming").performClick()
        composeRule.onNodeWithText("Password saved on device").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Connect").assertIsEnabled()
        setTextWithoutOpeningIme("UDP port or range (optional)", "60011")
        composeRule.onNodeWithText("Password saved on device").assertDoesNotExist()
        composeRule.onNodeWithText("Connect").assertIsNotEnabled()
        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag))).perform(replaceText("fresh credential"))
        closeSoftKeyboard()
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("mobile.example", submission?.host)
            assertNull(submission?.selectedProfileId)
            assertEquals(RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH,
                moshPort = 60_011, moshLocale = "en_GB.UTF-8", moshFallbackPolicy = MoshFallbackPolicy.ASK),
                submission?.connectionOptions)
        }
    }

    @Test
    fun switchingProtocolsKeepsMoshOptionsButSubmitsNoMoshPolicyToSsh() {
        val options = RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH,
            moshPort = 60_011, moshServerCommand = "/opt/mosh-server",
            moshLocale = "en_GB.UTF-8", moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC)
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("mobile.example", 22, "operator", connectionOptions = options),
            purpose = SshConnectPurpose.NEW,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )
        composeRule.onNodeWithText("SSH").performClick()
        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag))).perform(replaceText("fresh SSH credential"))
        closeSoftKeyboard()
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(RemoteConnectionOptions.SSH, submission?.connectionOptions) }

        composeRule.onNodeWithText("Mosh").performScrollTo().performClick()
        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag))).perform(replaceText("fresh Mosh credential"))
        closeSoftKeyboard()
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(options, submission?.connectionOptions) }
    }

    @Test
    fun reconnectRetainsCustomMoshLocaleWhenRequestingFreshAuthentication() {
        val options = RemoteConnectionOptions(
            protocol = ConnectionProtocol.MOSH,
            moshPortRange = MoshPortRange(60_010, 60_020),
            moshServerCommand = "/usr/local/bin/mosh-server",
            moshLocale = "en_GB.UTF-8",
        )
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("mobile.example", 22, "operator", connectionOptions = options),
            purpose = SshConnectPurpose.RECONNECT,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )
        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag))).perform(replaceText("fresh credential"))
        closeSoftKeyboard()
        composeRule.onNodeWithText("Reconnect").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(options, submission?.connectionOptions) }
    }

    @Test
    fun duplicateMoshRetainsFallbackPolicyAfterAdvancedFieldEdits() {
        val options = RemoteConnectionOptions(
            protocol = ConnectionProtocol.MOSH,
            moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
        )
        var submission: DialogSubmission? = null
        setDialog(
            seed = SshConnectionSeed("mobile.example", 22, "operator", connectionOptions = options),
            purpose = SshConnectPurpose.DUPLICATE,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )
        setTextWithoutOpeningIme("UDP port or range (optional)", "60011")
        setTextWithoutOpeningIme("Server executable", "/opt/mosh-server")
        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag))).perform(replaceText("fresh credential"))
        closeSoftKeyboard()
        composeRule.onNodeWithText("Duplicate").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(options.copy(moshPort = 60_011, moshServerCommand = "/opt/mosh-server"),
                submission?.connectionOptions)
        }
    }

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
        val seed = SshConnectionSeed(
            host = "mobile.example",
            port = 22,
            username = "operator",
            connectionOptions = RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH),
        )
        var submission: DialogSubmission? = null
        setDialog(
            seed = seed,
            purpose = SshConnectPurpose.NEW,
            moshExtension = availableMoshUiState(),
            onConnect = { submission = it },
        )

        composeRule.onNodeWithText("Mosh").assertIsEnabled()
        setTextWithoutOpeningIme("UDP port or range (optional)", "60000:60010")
        setTextWithoutOpeningIme("Server executable", "/usr/local/bin/mosh-server")
        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag)))
            .perform(replaceText("fresh credential"))
        closeSoftKeyboard()
        composeRule.onNodeWithText("Connect").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            val options = submission?.connectionOptions
            assertEquals(ConnectionProtocol.MOSH, options?.protocol)
            assertEquals(60_000, options?.moshPortRange?.first)
            assertEquals(60_010, options?.moshPortRange?.last)
            assertEquals("/usr/local/bin/mosh-server", options?.moshServerCommand)
        }
    }

    private fun setTextWithoutOpeningIme(label: String, text: String) {
        val action = composeRule.onNodeWithText(label)
            .fetchSemanticsNode()
            .config[SemanticsActions.SetText]
        composeRule.runOnIdle {
            check(action.action?.invoke(AnnotatedString(text)) == true)
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

        composeRule.onNodeWithTag(SshConnectPasswordTestTag).performScrollTo()
        onView(withTagValue(equalTo(SshConnectPasswordTestTag)))
            .perform(replaceText("saved credential"))
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save password on this device")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
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
