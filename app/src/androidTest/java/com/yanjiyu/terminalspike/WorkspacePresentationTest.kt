package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.ui.LocalWorkspaceScreen
import com.yanjiyu.terminalspike.ui.SessionTabUi
import com.yanjiyu.terminalspike.ui.TerminalSpikeUiState
import com.yanjiyu.terminalspike.ui.WorkspaceActiveSessionUi
import com.yanjiyu.terminalspike.ui.WorkspaceActiveSessionsTestTag
import com.yanjiyu.terminalspike.ui.WorkspaceAppBarTestTag
import com.yanjiyu.terminalspike.ui.WorkspaceEmptyNewConnectionTestTag
import com.yanjiyu.terminalspike.ui.WorkspaceNewConnectionTestTag
import com.yanjiyu.terminalspike.ui.WorkspacePinnedHostUi
import com.yanjiyu.terminalspike.ui.WorkspacePinnedHostsTestTag
import com.yanjiyu.terminalspike.ui.WorkspaceRecentConnectionsTestTag
import com.yanjiyu.terminalspike.ui.WorkspaceRecentSessionUi
import com.yanjiyu.terminalspike.ui.WorkspaceSessionStatus
import com.yanjiyu.terminalspike.ui.WorkspaceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspacePresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWorkspaceHeaderUsesAppNameResource() {
        setWorkspaceContent(
            workspace = populatedWorkspace(),
            modifier = Modifier.requiredSize(width = 599.dp, height = 700.dp),
        )

        assertAppNameIsInWorkspaceHeader()
    }

    @Test
    fun expandedWorkspaceHeaderUsesAppNameResource() {
        setWorkspaceContent(
            workspace = populatedWorkspace(),
            modifier = Modifier.requiredSize(width = 600.dp, height = 700.dp),
        )

        assertAppNameIsInWorkspaceHeader()
    }

    @Test
    fun sectionsKeepRequiredOrderAndCardsDoNotExposeEndpoints() {
        setWorkspaceContent(populatedWorkspace())

        val orderedTops = listOf(
            WorkspaceAppBarTestTag,
            WorkspaceActiveSessionsTestTag,
            WorkspacePinnedHostsTestTag,
            WorkspaceRecentConnectionsTestTag,
            WorkspaceNewConnectionTestTag,
        ).map { tag -> composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }

        assertEquals(orderedTops.sorted(), orderedTops)
        composeRule.onNodeWithText("Terminal Spike").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Workspace screen").assertExists()
        composeRule.onAllNodesWithText("Production").assertCountEquals(2)
        composeRule.onNodeWithText("deploy shell").assertIsDisplayed()
        composeRule.onNodeWithText("alice@secret.example").assertDoesNotExist()
        composeRule.onNodeWithText("Known Hosts").assertDoesNotExist()
        composeRule.onNodeWithText("Renderer lab").assertDoesNotExist()
        composeRule.onNodeWithText("Restore backup").assertDoesNotExist()
    }

    @Test
    fun cardOverflowAndTerminalDestinationDispatchRealCallbacksWithoutHeaderMenu() {
        var reopened: Long? = null
        var duplicated: Long? = null
        var openedTerminal = false
        setWorkspaceContent(
            workspace = populatedWorkspace(),
            onReopen = { reopened = it },
            onDuplicate = { duplicated = it },
            onOpenTerminal = { openedTerminal = true },
        )

        composeRule.onNodeWithTag("workspace-active-session-11").performClick()
        composeRule.onNodeWithContentDescription("Session actions for Production").performClick()
        composeRule.onNodeWithText("Duplicate").performClick()
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
        composeRule.onNodeWithContentDescription("Workspace destinations").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(11L, reopened)
            assertEquals(11L, duplicated)
            assertTrue(openedTerminal)
        }
    }

    @Test
    fun pinnedAndRecentConnectionRowsDispatchWithOneTap() {
        var pinnedProfileId: Long? = null
        var recentSessionId: String? = null
        val workspace = populatedWorkspace()
        setWorkspaceContent(
            workspace = workspace,
            onConnectPinned = { pinnedProfileId = it },
            onReconnectRecent = { recentSessionId = it },
        )

        composeRule.onNodeWithTag("workspace-pinned-host-7").performClick()
        composeRule.onNodeWithTag(
            "workspace-recent-session-10000000-0000-4000-8000-000000000001",
        ).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(7L, pinnedProfileId)
            assertEquals("10000000-0000-4000-8000-000000000001", recentSessionId)
        }
    }

    @Test
    fun reconnectIsOfferedWhenDuplicateIsCapacityBlocked() {
        var reconnected: Long? = null
        setWorkspaceContent(
            workspace = WorkspaceUiState(
                activeSessions = listOf(
                    WorkspaceActiveSessionUi(
                        id = 41,
                        friendlyName = "Offline host",
                        protocol = ConnectionProtocol.SSH,
                        status = WorkspaceSessionStatus.FAILED,
                        terminalTitle = null,
                        lastActivityAtEpochMillis = 1,
                        canReconnect = true,
                        canDisconnect = false,
                        canDuplicate = false,
                    ),
                ),
            ),
            onReconnect = { reconnected = it },
        )

        composeRule.onNodeWithContentDescription("Session actions for Offline host").performClick()
        composeRule.onNodeWithText("Reconnect").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Duplicate").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(41L, reconnected) }
    }

    @Test
    fun endpointDerivedActiveAndRecentNamesNeverReachWorkspaceText() {
        val username = "operator"
        val host = "203.0.113.42"
        val profileId = "10000000-0000-4000-8000-000000000008"
        val profile = SavedSshProfile(
            id = 8,
            label = "$username@$host",
            host = host,
            port = 22,
            username = username,
            persistentId = profileId,
        )
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(
                    id = 1,
                    title = "$username@$host",
                    connectionState = ConnectionState.Connected,
                    workspaceName = "$username@$host",
                    recentSessionId = "10000000-0000-4000-8000-000000000009",
                    sourceProfileId = profile.id,
                ),
            ),
            profiles = listOf(profile),
            recentSessions = listOf(
                RecentSession(
                    id = "10000000-0000-4000-8000-000000000010",
                    hostProfileId = profileId,
                    hostDisplayName = host,
                    protocol = ConnectionProtocol.SSH,
                    state = SessionState.DISCONNECTED,
                    startedAtEpochMillis = 10,
                    lastActivityAtEpochMillis = 20,
                    endedAtEpochMillis = 20,
                ),
            ),
        )

        setWorkspaceContent(state.workspace)

        // The open endpoint suppresses its equivalent ended-history row, while the saved host
        // remains available as its own one-tap catalogue entry.
        composeRule.onAllNodes(
            hasText("SSH session") and
                hasAnyAncestor(hasTestTag(WorkspaceActiveSessionsTestTag)),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onAllNodes(
            hasText("SSH session") and
                hasAnyAncestor(hasTestTag(WorkspacePinnedHostsTestTag)),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onAllNodes(
            hasText("SSH session") and
                hasAnyAncestor(hasTestTag(WorkspaceRecentConnectionsTestTag)),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        composeRule.onNodeWithText(username, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(host, substring = true).assertDoesNotExist()
    }

    @Test
    fun noSessionStateOffersAnImmediateWorkingNewConnectionAction() {
        var newConnectionCount = 0
        setWorkspaceContent(
            workspace = WorkspaceUiState(),
            onNewConnection = { newConnectionCount += 1 },
        )
        composeRule.onNodeWithText("No active sessions").assertIsDisplayed()
        composeRule.onNodeWithTag(WorkspaceEmptyNewConnectionTestTag).performClick()
        composeRule.onNodeWithContentDescription("Start a new SSH connection").assertExists()
        composeRule.runOnIdle { assertEquals(1, newConnectionCount) }
    }

    @Test
    fun visibleWorkspaceRefreshesRelativeActivityLabels() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val oneMinuteAgo = resources.getQuantityString(
            R.plurals.workspace_last_activity_minutes,
            1,
            1,
        )
        val activityAt = 100_000L
        var now = activityAt + 30_000L
        val activeSession = populatedWorkspace().activeSessions.single().copy(
            lastActivityAtEpochMillis = activityAt,
        )
        setWorkspaceContent(
            workspace = WorkspaceUiState(activeSessions = listOf(activeSession)),
            activityClock = { now },
            activityRefreshIntervalMillis = 25L,
        )

        composeRule.onNodeWithText(resources.getString(R.string.workspace_last_activity_just_now))
            .assertIsDisplayed()
        composeRule.runOnIdle { now = activityAt + 60_000L }
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithText(oneMinuteAgo)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(oneMinuteAgo).assertIsDisplayed()
    }

    private fun setWorkspaceContent(
        workspace: WorkspaceUiState,
        onReopen: (Long) -> Unit = {},
        onReconnect: (Long) -> Unit = {},
        onDuplicate: (Long) -> Unit = {},
        onConnectPinned: (Long) -> Unit = {},
        onReconnectRecent: (String) -> Unit = {},
        onOpenTerminal: () -> Unit = {},
        onOpenConnections: () -> Unit = {},
        onNewConnection: () -> Unit = {},
        activityClock: () -> Long = System::currentTimeMillis,
        activityRefreshIntervalMillis: Long = 60_000L,
        modifier: Modifier = Modifier,
    ) {
        composeRule.setContent {
            MaterialTheme {
                LocalWorkspaceScreen(
                    workspace = workspace,
                    settingsReady = true,
                    onReopenSession = onReopen,
                    onReconnectSession = onReconnect,
                    onDisconnectSession = {},
                    onDuplicateSession = onDuplicate,
                    onConnectPinnedHost = onConnectPinned,
                    onReconnectRecent = onReconnectRecent,
                    onQuickConnect = onNewConnection,
                    onOpenTerminal = onOpenTerminal,
                    onOpenConnections = onOpenConnections,
                    onOpenSettings = {},
                    modifier = modifier,
                    activityClock = activityClock,
                    activityRefreshIntervalMillis = activityRefreshIntervalMillis,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertAppNameIsInWorkspaceHeader() {
        composeRule.onNode(
            hasText("Terminal Spike") and
                hasAnyAncestor(hasTestTag(WorkspaceAppBarTestTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private fun populatedWorkspace() = WorkspaceUiState(
        activeSessions = listOf(
            WorkspaceActiveSessionUi(
                id = 11,
                friendlyName = "Production",
                protocol = ConnectionProtocol.SSH,
                status = WorkspaceSessionStatus.CONNECTED,
                terminalTitle = "deploy shell",
                lastActivityAtEpochMillis = 1,
                canReconnect = false,
                canDisconnect = true,
                canDuplicate = true,
            ),
        ),
        pinnedHosts = listOf(
            WorkspacePinnedHostUi(
                profileId = 7,
                friendlyName = "Production",
                protocol = ConnectionProtocol.SSH,
                canConnect = true,
            ),
        ),
        recentConnections = listOf(
            WorkspaceRecentSessionUi(
                id = "10000000-0000-4000-8000-000000000001",
                friendlyName = "Archive",
                protocol = ConnectionProtocol.SSH,
                status = WorkspaceSessionStatus.DISCONNECTED,
                terminalTitle = null,
                lastActivityAtEpochMillis = 1,
                sourceProfileId = 8,
            ),
        ),
    )
}
