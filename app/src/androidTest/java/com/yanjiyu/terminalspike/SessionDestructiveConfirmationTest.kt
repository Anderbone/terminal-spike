package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.ui.NewTerminalSessionTestTag
import com.yanjiyu.terminalspike.ui.SessionChrome
import com.yanjiyu.terminalspike.ui.SessionTabUi
import com.yanjiyu.terminalspike.ui.SshConnectDialog
import com.yanjiyu.terminalspike.ui.TerminalSessionStripTestTag
import com.yanjiyu.terminalspike.ui.TerminalSessionTabTestTagPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionDestructiveConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalChromeShowsOnlyBoundedTabsAndTrailingNewTerminalAction() {
        val sessions = listOf(
            SessionTabUi(12L, "production-host-with-a-long-name", ConnectionState.Connected),
            SessionTabUi(13L, "staging-host-with-a-long-name", ConnectionState.Disconnected),
            SessionTabUi(14L, "logs-host-with-a-long-name", ConnectionState.Connecting),
        )
        var selectedSessionId: Long? = null
        var newSessionRequested = false

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 180.dp)) {
                        SessionChrome(
                            sessions = sessions,
                            activeSessionId = 12L,
                            notice = "Connected · xterm-256color",
                            canAddSession = true,
                            settingsReady = true,
                            onSelectSession = { selectedSessionId = it },
                            onDuplicateSession = {},
                            onCloseSession = {},
                            onDisconnect = {},
                            onHostIdentityAnswer = { _, _, _ -> },
                            onNavigateBack = {},
                            backDestinationLabel = "Workspace",
                            onNewSession = { newSessionRequested = true },
                            onOpenConnections = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Connections").assertDoesNotExist()
        composeRule.onNodeWithText("Disconnect").assertDoesNotExist()
        composeRule.onNodeWithText("Connected · xterm-256color").assertDoesNotExist()
        composeRule.onNodeWithText("×").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back to Workspace").assertDoesNotExist()

        val firstTab = composeRule.onNodeWithTag("$TerminalSessionTabTestTagPrefix${sessions.first().id}")
        val thirdTab = composeRule.onNodeWithTag("$TerminalSessionTabTestTagPrefix${sessions.last().id}")
        val firstBounds = firstTab.assertHeightIsAtLeast(28.dp).fetchSemanticsNode().boundsInRoot
        val thirdBounds = thirdTab.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.width, thirdBounds.width, 1f)
        thirdTab.performClick()
        // combinedClickable waits through the double-tap window before dispatching a single tap.
        composeRule.waitUntil(timeoutMillis = 1_000) { selectedSessionId == 14L }

        val stripBounds = composeRule.onNodeWithTag(TerminalSessionStripTestTag).fetchSemanticsNode().boundsInRoot
        assertEquals(stripBounds.left, firstBounds.left, 1f)
        assertEquals(stripBounds.right, thirdBounds.right, 1f)
        val add = composeRule.onNodeWithTag(NewTerminalSessionTestTag)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(28.dp)
            .assertHeightIsAtLeast(28.dp)
        val addBounds = add.fetchSemanticsNode().boundsInRoot
        assertTrue(addBounds.left >= stripBounds.right)
        add.performClick()

        composeRule.runOnIdle {
            assertEquals(14L, selectedSessionId)
            assertTrue(newSessionRequested)
        }
    }

    @Test
    fun closeButtonImmediatelyClosesAConnectedSession() {
        val session = SessionTabUi(
            id = 12L,
            title = "deploy@server",
            connectionState = ConnectionState.Connected,
        )
        var closedSessionId: Long? = null
        var disconnectedSessionId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SessionChrome(
                        sessions = listOf(session),
                        activeSessionId = session.id,
                        notice = null,
                        canAddSession = true,
                        settingsReady = true,
                        onSelectSession = {},
                        onDuplicateSession = {},
                        onCloseSession = { closedSessionId = it },
                        onDisconnect = { disconnectedSessionId = it },
                        onHostIdentityAnswer = { _, _, _ -> },
                        onNavigateBack = {},
                        onNewSession = {},
                        onOpenConnections = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Close deploy@server terminal tab")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Close deploy@server?").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(12L, closedSessionId)
            assertNull(disconnectedSessionId)
        }
    }

    @Test
    fun hostTrustApprovalRemainsAvailableOutsideTheCompactTabStrip() {
        val prompt = HostIdentityPrompt.FirstContact(
            endpoint = "server.example",
            algorithm = "ssh-ed25519",
            newFingerprint = "SHA256:test-fingerprint",
            promptToken = 41L,
        )
        val session = SessionTabUi(
            id = 19L,
            title = "server",
            connectionState = ConnectionState.AwaitingApproval(prompt),
        )
        var answer: Triple<Long, Long, HostIdentityDecision>? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(session),
                    activeSessionId = session.id,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = { id, token, decision ->
                        answer = Triple(id, token, decision)
                    },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                )
            }
        }

        composeRule.onNodeWithText("Trust this SSH host?").assertIsDisplayed()
        composeRule.onNodeWithText("Trust and save").performClick()
        composeRule.runOnIdle {
            assertEquals(
                Triple(19L, 41L, HostIdentityDecision.TrustAndSave),
                answer,
            )
        }
    }

    @Test
    fun changedHostKeyRequiresASeparateReplacementConfirmation() {
        val prompt = HostIdentityPrompt.Changed(
            endpoint = "[server.example]:2222",
            algorithm = "ssh-ed25519",
            previousFingerprint = "SHA256:previous-full-fingerprint",
            newFingerprint = "SHA256:new-full-fingerprint",
            promptToken = 42L,
        )
        val session = SessionTabUi(
            id = 20L,
            title = "server",
            connectionState = ConnectionState.AwaitingApproval(prompt),
        )
        var answer: Triple<Long, Long, HostIdentityDecision>? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(session),
                    activeSessionId = session.id,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = { id, token, decision ->
                        answer = Triple(id, token, decision)
                    },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                )
            }
        }

        composeRule.onNodeWithText("Warning: SSH host key changed").assertIsDisplayed()
        composeRule.onNodeWithText("Previous fingerprint: SHA256:previous-full-fingerprint", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("New fingerprint: SHA256:new-full-fingerprint", substring = true)
            .assertIsDisplayed()
        composeRule.runOnIdle { assertNull(answer) }

        composeRule.onNodeWithText("Review replacement").performClick()
        composeRule.onNodeWithText("Replace the saved SSH host key?").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(answer) }

        composeRule.onNodeWithText("Replace saved key").performClick()
        composeRule.runOnIdle {
            assertEquals(
                Triple(20L, 42L, HostIdentityDecision.ReplaceSavedKey),
                answer,
            )
        }
    }

    @Test
    fun doubleTapConnectedTabRequestsDuplicateSession() {
        val session = SessionTabUi(
            id = 23L,
            title = "production",
            connectionState = ConnectionState.Connected,
        )
        var duplicatedSessionId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(session),
                    activeSessionId = session.id,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = { duplicatedSessionId = it },
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = { _, _, _ -> },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("$TerminalSessionTabTestTagPrefix${session.id}")
            .performTouchInput { doubleClick() }

        composeRule.runOnIdle { assertEquals(session.id, duplicatedSessionId) }
    }

    @Test
    fun forgettingASavedPasswordRequiresConfirmation() {
        val profile = SavedSshProfile(
            id = 4L,
            label = "Production",
            host = "server.example",
            port = 22,
            username = "deploy",
            hasSavedPassword = true,
        )
        var forgottenProfileId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                SshConnectDialog(
                    profiles = listOf(profile),
                    identities = emptyList(),
                    initialProfile = profile,
                    settingsReady = true,
                    onDismiss = {},
                    onConnect = { _, _, _, _, _, _, _, _, _, _, _ -> },
                    onForgetSavedPassword = { forgottenProfileId = it },
                )
            }
        }

        val forget = composeRule.onNodeWithText("Forget")
        forget.performClick()
        composeRule.onNodeWithText("Forget saved password?").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(forgottenProfileId) }
        composeRule.onNodeWithText("Cancel").performClick()

        forget.performClick()
        composeRule.onNodeWithText("Forget password").performClick()
        composeRule.runOnIdle { assertEquals(4L, forgottenProfileId) }
    }
}
