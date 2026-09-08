package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.TmuxAvailability
import com.yanjiyu.terminalspike.connection.TmuxSession
import com.yanjiyu.terminalspike.connection.TmuxSessionCatalog
import com.yanjiyu.terminalspike.connection.TmuxSessionPrompt
import com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
import com.yanjiyu.terminalspike.ui.ActiveTmuxSessionSwitcherDialog
import com.yanjiyu.terminalspike.ui.AppSessionSwitcherDialog
import com.yanjiyu.terminalspike.ui.SessionChrome
import com.yanjiyu.terminalspike.ui.SessionTabUi
import org.junit.Rule
import org.junit.Test

class TerminalTaskIndicatorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun topTabsReflectBackgroundTaskState() = verifySurface(0)
    @Test fun appSwitcherReflectsBackgroundTaskState() = verifySurface(1)
    @Test fun tmuxSwitcherReflectsInactiveRemoteTaskState() = verifySurface(2)
    @Test fun startupPickerShowsRemoteTaskState() = verifySurface(3)

    private fun verifySurface(surface: Int) {
        var status by mutableStateOf(TerminalTaskStatus(running = true))
        composeRule.setContent {
            MaterialTheme {
                val tmux = TmuxSession("$4", "Background Codex", 1, 1, 1, taskStatus = status)
                val active = SessionTabUi(11L, "Active shell", ConnectionState.Connected)
                val background = SessionTabUi(12L, "Background Codex", ConnectionState.Connected, taskStatus = status)
                when (surface) {
                    1 -> AppSessionSwitcherDialog(
                        sessions = listOf(active, background), previews = emptyMap(), activeSessionId = 11L,
                        canAddSession = true, onDismiss = {}, onSelect = {}, onClose = {}, onNewSession = {},
                    )
                    2 -> ActiveTmuxSessionSwitcherDialog(
                        catalog = TmuxSessionCatalog(listOf(tmux), TmuxAvailability.AVAILABLE),
                        onDismiss = {}, onRefresh = {}, onSelect = {}, onDelete = {},
                    )
                    else -> SessionChrome(
                        sessions = if (surface == 3) listOf(active.copy(connectionState = ConnectionState.AwaitingApproval(
                            TmuxSessionPrompt(71L, listOf(tmux)),
                        ))) else listOf(active, background),
                        activeSessionId = 11L, notice = null, canAddSession = true, settingsReady = true,
                        onSelectSession = {}, onDuplicateSession = {}, onCloseSession = {}, onDisconnect = {},
                        onHostIdentityAnswer = { _, _, _ -> }, onNavigateBack = {}, onNewSession = {},
                        onOpenConnections = {}, onTmuxSessionAnswer = { _, _, _ -> },
                    )
                }
            }
        }
        composeRule.onAllNodesWithContentDescription("Running", useUnmergedTree = true)[0].assertIsDisplayed()
        composeRule.runOnIdle { status = TerminalTaskStatus(finished = true) }
        composeRule.onAllNodesWithContentDescription("Finished / ready", useUnmergedTree = true)[0].assertIsDisplayed()
        composeRule.runOnIdle { status = TerminalTaskStatus(needsAttention = true) }
        composeRule.onAllNodesWithContentDescription("Needs attention", useUnmergedTree = true)[0].assertIsDisplayed()
    }
}
