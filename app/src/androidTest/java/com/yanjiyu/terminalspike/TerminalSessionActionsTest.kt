package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.ui.NewTerminalSessionTestTag
import com.yanjiyu.terminalspike.ui.SessionChrome
import com.yanjiyu.terminalspike.ui.SessionTabUi
import com.yanjiyu.terminalspike.ui.TerminalSessionTabTestTagPrefix
import com.yanjiyu.terminalspike.ui.terminal.TerminalSessionActions
import com.yanjiyu.terminalspike.ui.terminal.TerminalSessionActionsTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TerminalSessionActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

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
