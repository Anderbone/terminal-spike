package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.TMUX_NEW_SESSION_SELECTION
import com.yanjiyu.terminalspike.connection.TmuxSession
import com.yanjiyu.terminalspike.connection.TmuxSessionPrompt
import com.yanjiyu.terminalspike.ui.NewTerminalSessionTestTag
import com.yanjiyu.terminalspike.ui.SessionChrome
import com.yanjiyu.terminalspike.ui.SessionTabUi
import com.yanjiyu.terminalspike.ui.TerminalSessionTabTestTagPrefix
import com.yanjiyu.terminalspike.ui.TerminalChromeTitleTestTag
import com.yanjiyu.terminalspike.ui.TmuxSessionDialogTestTag
import com.yanjiyu.terminalspike.ui.terminal.TerminalSessionActions
import com.yanjiyu.terminalspike.ui.terminal.TerminalSessionActionsTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalSessionActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tmuxChooserMakesStartingANewProtectedSessionExplicit() {
        val target = session(11L, "Server").copy(
            connectionState = ConnectionState.AwaitingApproval(
                TmuxSessionPrompt(promptToken = 71L, sessions = emptyList()),
            ),
        )
        var answer: Triple<Long, Long, String?>? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(target),
                    activeSessionId = target.id,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = { _, _, _ -> },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                    onTmuxSessionAnswer = { sessionId, promptToken, selection ->
                        answer = Triple(sessionId, promptToken, selection)
                    },
                )
            }
        }

        composeRule.onNodeWithTag(TmuxSessionDialogTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Start new session").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(Triple(11L, 71L, TMUX_NEW_SESSION_SELECTION), answer)
        }
    }

    @Test
    fun tappingANamedTmuxSessionAttachesToItsOpaqueId() {
        val target = session(11L, "Server").copy(
            connectionState = ConnectionState.AwaitingApproval(
                TmuxSessionPrompt(
                    promptToken = 72L,
                    sessions = listOf(
                        TmuxSession(
                            id = "\$4",
                            name = "codex1",
                            windowCount = 2,
                            attachedClientCount = 0,
                            createdAtEpochSeconds = 1_725_000_000L,
                        ),
                    ),
                ),
            ),
        )
        var answer: Triple<Long, Long, String?>? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(target),
                    activeSessionId = target.id,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = { _, _, _ -> },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                    onTmuxSessionAnswer = { sessionId, promptToken, selection ->
                        answer = Triple(sessionId, promptToken, selection)
                    },
                )
            }
        }

        assertTrue(
            composeRule.onAllNodesWithText("Tap session to attach").fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("codex1").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(Triple(11L, 72L, "\$4"), answer) }
    }

    @Test
    fun hiddenLocalSessionShowsOrientationAndDispatchesChromeActions() {
        var backCount = 0
        var newSessionCount = 0
        val localSession = session(1L, "Local").copy(isLocalTerminal = true)

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(localSession),
                    activeSessionId = localSession.id,
                    notice = "Ready on this device",
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = { _, _, _ -> },
                    onNavigateBack = { backCount += 1 },
                    backDestinationLabel = "Connections",
                    onNewSession = { newSessionCount += 1 },
                    onOpenConnections = {},
                    showLocalTerminalSession = false,
                )
            }
        }

        composeRule.onNodeWithTag(TerminalChromeTitleTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Ready on this device").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to Connections").performClick()
        composeRule.onNodeWithTag(NewTerminalSessionTestTag)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(1, newSessionCount)
        }
    }

    @Test
    fun longPressCapturesExactTabAndSelectionChangeCannotRetargetAction() {
        var sessions by mutableStateOf(listOf(session(11L, "Alpha"), session(22L, "Beta")))
        var activeId by mutableStateOf(11L)
        var targetId by mutableStateOf<Long?>(null)
        var duplicatedId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = sessions,
                    activeSessionId = activeId,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = { activeId = it },
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onSessionActions = { targetId = it },
                    onHostIdentityAnswer = { _, _, _ -> },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                )
                targetId?.let { captured ->
                    TerminalSessionActions(
                        session = sessions.firstOrNull { it.id == captured },
                        endpoint = null,
                        canAddSession = true,
                        onDismiss = { targetId = null },
                        onReconnect = {},
                        onDuplicate = { duplicatedId = it },
                        onDisconnect = {},
                        onClose = {},
                        onFind = {},
                        onClearLocalScrollback = {},
                        onExportTranscript = {},
                        onEnterFocusMode = {},
                        onOpenSnippets = {},
                        onOpenTerminalSettings = {},
                        onOpenKeyboardSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("$TerminalSessionTabTestTagPrefix${11L}")
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(TerminalSessionActionsTestTag).assertIsDisplayed()
        composeRule.runOnIdle { activeId = 22L }
        composeRule.onNodeWithText("Duplicate").performClick()
        composeRule.runOnIdle { assertEquals(11L, duplicatedId) }
        composeRule.onNodeWithTag(NewTerminalSessionTestTag).assertIsDisplayed()
    }

    @Test
    fun closedCapturedTabDismissesWithoutCallingAnotherSession() {
        var sessions by mutableStateOf(listOf(session(11L, "Alpha"), session(22L, "Beta")))
        var activeId by mutableStateOf(11L)
        var targetId by mutableStateOf<Long?>(null)
        var disconnectedId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                SessionChrome(
                    sessions = sessions,
                    activeSessionId = activeId,
                    notice = null,
                    canAddSession = true,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onSessionActions = { targetId = it },
                    onHostIdentityAnswer = { _, _, _ -> },
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                )
                targetId?.let { captured ->
                    TerminalSessionActions(
                        session = sessions.firstOrNull { it.id == captured },
                        endpoint = null,
                        canAddSession = true,
                        onDismiss = { targetId = null },
                        onReconnect = {},
                        onDuplicate = {},
                        onDisconnect = { disconnectedId = it },
                        onClose = {},
                        onFind = {},
                        onClearLocalScrollback = {},
                        onExportTranscript = {},
                        onEnterFocusMode = {},
                        onOpenSnippets = {},
                        onOpenTerminalSettings = {},
                        onOpenKeyboardSettings = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("$TerminalSessionTabTestTagPrefix${11L}")
            .performTouchInput { longClick() }
        composeRule.runOnIdle {
            sessions = listOf(session(22L, "Beta"))
            activeId = 22L
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TerminalSessionActionsTestTag).assertDoesNotExist()
        composeRule.runOnIdle { assertNull(disconnectedId) }
    }

    @Test
    fun closeActionImmediatelyClosesTheCapturedSession() {
        val target = session(11L, "Alpha")
        var dismissed = false
        var closedId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                TerminalSessionActions(
                    session = target,
                    endpoint = null,
                    canAddSession = true,
                    onDismiss = { dismissed = true },
                    onReconnect = {},
                    onDuplicate = {},
                    onDisconnect = {},
                    onClose = { closedId = it },
                    onFind = {},
                    onClearLocalScrollback = {},
                    onExportTranscript = {},
                    onEnterFocusMode = {},
                    onOpenSnippets = {},
                    onOpenTerminalSettings = {},
                    onOpenKeyboardSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Close tab").performClick()
        composeRule.onNodeWithText("Close Alpha?").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(11L, closedId)
            assertEquals(true, dismissed)
        }
    }

    private fun session(id: Long, title: String) = SessionTabUi(
        id = id,
        title = title,
        connectionState = ConnectionState.Connected,
    )
}
