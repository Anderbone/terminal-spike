package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.SessionNotificationVisibility
import com.yanjiyu.terminalspike.connection.MoshFallbackFailure
import com.yanjiyu.terminalspike.core.data.repository.CatalogSecretAvailability
import com.yanjiyu.terminalspike.core.data.repository.CatalogHostProfile
import com.yanjiyu.terminalspike.core.data.repository.CatalogSshCredential
import com.yanjiyu.terminalspike.core.data.repository.CatalogSshKeyIdentity
import com.yanjiyu.terminalspike.core.data.repository.HostAuthenticationUpdate
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCatalog
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCatalogLoadResult
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.RecentHostActivity
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.core.model.SshAuthentication as StoredSshAuthentication
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedHostConnectionCompatibility
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.settings.SettingsLoadResult
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.ui.connections.ConnectionsLoadState
import com.yanjiyu.terminalspike.ui.connections.ConnectionsInteractionState
import com.yanjiyu.terminalspike.ui.connections.ConnectionsTab
import com.yanjiyu.terminalspike.ui.connections.HostActiveSessionStatus
import com.yanjiyu.terminalspike.ui.connections.HostFilters
import com.yanjiyu.terminalspike.ui.connections.HostSort
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSpikeUiStateTest {
    @Test
    fun primaryDestinationSwapsKeepTheSharedFooterStable() {
        assertTrue(keepsPrimaryNavigationStable(AppRoute.WORKSPACE, AppRoute.SETTINGS))
        assertTrue(keepsPrimaryNavigationStable(AppRoute.SETTINGS, AppRoute.CONNECTIONS))
        assertFalse(keepsPrimaryNavigationStable(AppRoute.WORKSPACE, AppRoute.TERMINAL_DETAIL))
        assertFalse(keepsPrimaryNavigationStable(AppRoute.TERMINAL_DETAIL, AppRoute.SETTINGS))
    }

    @Test
    fun remoteTerminalTitleReplacesFixedTabLabelAndCompactsStockTmuxTitle() {
        val session = SessionTabUi(
            id = 9,
            title = "mo",
            connectionState = ConnectionState.Connected,
            terminalTitle = "xxx2",
        )

        assertEquals("xxx2", session.displayedTerminalTabTitle())
        assertEquals(
            "xxx2",
            session.copy(terminalTitle = "xxx2:0:bash - \"mo\"").displayedTerminalTabTitle(),
        )
        assertEquals(
            "release shell",
            session.copy(terminalTitle = "release shell").displayedTerminalTabTitle(),
        )
    }

    @Test
    fun terminalTabTitleFallsBackForAbsentTitlesAndNeverRenamesLocalTerminal() {
        val session = SessionTabUi(
            id = 9,
            title = "mo",
            connectionState = ConnectionState.Connected,
        )

        assertEquals("mo", session.displayedTerminalTabTitle())
        assertEquals("mo", session.copy(terminalTitle = "   ").displayedTerminalTabTitle())
        assertEquals(
            "Bench",
            session.copy(
                title = "Bench",
                isLocalTerminal = true,
                terminalTitle = "ignored remote title",
            ).displayedTerminalTabTitle(),
        )
    }

    @Test
    fun freshSshFallbackIsOfferedOnlyForClassifiedOptInMoshFailures() {
        val failed = SessionTabUi(
            id = 9,
            title = "Mosh session",
            connectionState = ConnectionState.Failed(
                message = "Mosh unavailable",
                moshFallbackFailure = MoshFallbackFailure.EXTENSION,
            ),
            protocol = ConnectionProtocol.MOSH,
            moshFallbackPolicy = MoshFallbackPolicy.ASK,
        )

        assertTrue(shouldOfferFreshSshFallback(failed, dismissedSessionId = null))
        assertFalse(shouldOfferFreshSshFallback(failed, dismissedSessionId = failed.id))
        assertFalse(
            shouldOfferFreshSshFallback(
                failed.copy(moshFallbackPolicy = MoshFallbackPolicy.NEVER),
                dismissedSessionId = null,
            ),
        )
        assertFalse(
            shouldOfferFreshSshFallback(
                failed.copy(
                    connectionState = ConnectionState.Failed("Authentication failed"),
                ),
                dismissedSessionId = null,
            ),
        )
    }

    @Test
    fun freshSshFallbackStripsSavedMoshOwnershipAndOptions() {
        val fallback = SshConnectionSeed(
            host = "example.test",
            port = 22,
            username = "operator",
            sourceProfileId = 41,
            connectionOptions = RemoteConnectionOptions(
                protocol = ConnectionProtocol.MOSH,
                moshPort = 60001,
                moshFallbackPolicy = MoshFallbackPolicy.ASK,
            ),
        ).toFreshSshFallbackSeed()

        assertNull(fallback.sourceProfileId)
        assertEquals(RemoteConnectionOptions.SSH, fallback.connectionOptions)
    }

    @Test
    fun notificationPermissionEducationIsOneTimeAndOnlyForLimitedVisibility() {
        assertTrue(
            shouldShowNotificationPermissionEducation(
                SessionNotificationVisibility.LIMITED_BY_PERMISSION,
                alreadyConsumed = false,
            ),
        )
        assertFalse(
            shouldShowNotificationPermissionEducation(
                SessionNotificationVisibility.LIMITED_BY_PERMISSION,
                alreadyConsumed = true,
            ),
        )
        assertFalse(
            shouldShowNotificationPermissionEducation(
                SessionNotificationVisibility.VISIBLE,
                alreadyConsumed = false,
            ),
        )
    }

    @Test
    fun connectionsCatalogProjectionCombinesAuthoritativeRowsWithOpenAndEndedHistory() {
        val hostId = "10000000-0000-4000-8000-000000000030"
        val openHistoryId = "10000000-0000-4000-8000-000000000031"
        val endedHistoryId = "10000000-0000-4000-8000-000000000032"
        val catalog = TerminalDataCatalog(
            hosts = listOf(
                CatalogHostProfile(
                    presentationId = 41,
                    profile = HostProfile(
                        id = hostId,
                        displayName = "Production",
                        hostname = "production.example",
                        port = 22,
                        username = "operator",
                        protocol = ConnectionProtocol.SSH,
                        credentialId = null,
                        isFavorite = true,
                        group = "Operations",
                        tag = "primary",
                        createdAtEpochMillis = 10,
                        updatedAtEpochMillis = 20,
                    ),
                ),
            ),
            credentials = emptyList(),
            identities = emptyList(),
            snippets = emptyList(),
            terminalProfiles = emptyList(),
            keyboardProfiles = emptyList(),
            defaultTerminalProfileId = "10000000-0000-4000-8000-000000000033",
            defaultKeyboardProfileId = "10000000-0000-4000-8000-000000000034",
            unavailableSecretCount = 0,
            migrationWarningCodes = emptyList(),
        )
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(
                    id = 7,
                    title = "operator@production.example",
                    connectionState = ConnectionState.Connected,
                    workspaceName = "Production",
                    lastActivityAtEpochMillis = 90,
                    recentSessionId = openHistoryId,
                    sourceProfileId = 41,
                ),
            ),
            recentSessions = listOf(
                RecentSession(
                    id = endedHistoryId,
                    hostProfileId = hostId,
                    hostDisplayName = "Production",
                    protocol = ConnectionProtocol.SSH,
                    state = SessionState.DISCONNECTED,
                    startedAtEpochMillis = 10,
                    lastActivityAtEpochMillis = 80,
                    endedAtEpochMillis = 80,
                ),
            ),
        )

        val connections = state.toConnectionsUiState(
            loadResult = TerminalDataCatalogLoadResult(
                compatibility = SettingsLoadResult(UserSettings()),
                catalog = catalog,
            ),
            interaction = ConnectionsInteractionState(
                selectedTab = ConnectionsTab.KEYS,
                searchQuery = "production",
                hostSort = HostSort.RECENT,
                hostFilters = HostFilters(favouritesOnly = true),
            ),
            recentHostActivity = listOf(
                RecentHostActivity(hostId, lastActivityAtEpochMillis = 800),
            ),
        )
        val ready = connections.loadState as ConnectionsLoadState.Ready
        val host = ready.hosts.single()

        assertEquals(HostActiveSessionStatus.CONNECTED, host.activeSessionStatus)
        assertEquals(1, host.activeSessionCount)
        assertEquals(90L, host.lastSessionActivityAtEpochMillis)
        assertEquals(listOf("primary"), host.tags)
        assertEquals(ConnectionsTab.KEYS, connections.selectedTab)
        assertEquals("production", connections.searchQuery)
        assertEquals(HostSort.RECENT, connections.hostSort)
        assertEquals(HostFilters(favouritesOnly = true), connections.hostFilters)
    }

    @Test
    fun connectionsCatalogProjectionReportsRecoveryErrorWithoutFallbackRows() {
        val connections = TerminalSpikeUiState().toConnectionsUiState(
            TerminalDataCatalogLoadResult(
                compatibility = SettingsLoadResult(
                    settings = UserSettings(),
                    failure = SettingsLoadFailure.APP_DATA_UNAVAILABLE,
                ),
                catalog = null,
            ),
        )

        assertTrue(connections.loadState is ConnectionsLoadState.Error)
    }

    @Test
    fun workspaceFriendlyNamesRejectQuickAndManualEndpointDerivedLabels() {
        val username = "deploy"
        val host = "203.0.113.42"
        val sensitiveValues = listOf(username, host)

        listOf(
            "$username@$host",
            "$username@$host:2222",
            host,
            "Server $host",
            username,
        ).forEach { candidate ->
            val friendlyName = privacySafeWorkspaceFriendlyName(
                candidate = candidate,
                protocol = ConnectionProtocol.SSH,
                sensitiveValues = sensitiveValues,
            )
            assertEquals(WORKSPACE_SSH_SESSION_FALLBACK, friendlyName)
            assertFalse(friendlyName.contains(username, ignoreCase = true))
            assertFalse(friendlyName.contains(host, ignoreCase = true))
        }
        assertEquals(
            "Production",
            privacySafeWorkspaceFriendlyName(
                candidate = "Production",
                protocol = ConnectionProtocol.SSH,
                sensitiveValues = sensitiveValues,
            ),
        )
        assertEquals(
            WORKSPACE_MOSH_SESSION_FALLBACK,
            privacySafeWorkspaceFriendlyName(
                candidate = "ops@example.internal",
                protocol = ConnectionProtocol.MOSH,
            ),
        )
    }

    @Test
    fun workspaceProjectionNeverPublishesUsernameOrHostAsActivePinnedOrRecentFriendlyName() {
        val username = "operator"
        val host = "private.example"
        val profileId = "10000000-0000-4000-8000-000000000005"
        val profile = SavedSshProfile(
            id = 9,
            label = "$username@$host",
            host = host,
            port = 22,
            username = username,
            persistentId = profileId,
            isFavorite = true,
        )
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(
                    id = 1,
                    title = "$username@$host",
                    connectionState = ConnectionState.Connected,
                    workspaceName = "$username@$host",
                    recentSessionId = "10000000-0000-4000-8000-000000000006",
                    sourceProfileId = profile.id,
                ),
            ),
            profiles = listOf(profile),
            recentSessions = listOf(
                endedSession(
                    id = "10000000-0000-4000-8000-000000000007",
                    hostProfileId = profileId,
                    displayName = host,
                ),
            ),
        )

        val friendlyNames = state.workspace.activeSessions.map { it.friendlyName } +
            state.workspace.pinnedHosts.map { it.friendlyName } +
            state.workspace.recentConnections.map { it.friendlyName }

        // The ended row is the same saved endpoint as the open tab, so Recent de-duplication omits it.
        assertEquals(List(2) { WORKSPACE_SSH_SESSION_FALLBACK }, friendlyNames)
        assertTrue(friendlyNames.none { it.contains(username, ignoreCase = true) })
        assertTrue(friendlyNames.none { it.contains(host, ignoreCase = true) })
    }

    @Test
    fun workspaceProjectionUsesSafeNamesFavoritesAndOmitsOpenTabsFromHistory() {
        val openRecentId = "10000000-0000-4000-8000-000000000001"
        val endedRecentId = "10000000-0000-4000-8000-000000000002"
        val pinnedHostId = "10000000-0000-4000-8000-000000000003"
        val unpinnedHostId = "10000000-0000-4000-8000-000000000004"
        val pinned = SavedSshProfile(
            id = 7,
            label = "Production",
            host = "secret.example",
            port = 22,
            username = "alice",
            persistentId = pinnedHostId,
            isFavorite = true,
        )
        val unpinned = SavedSshProfile(
            id = 8,
            label = "Archive",
            host = "archive.internal",
            port = 22,
            username = "bob",
            persistentId = unpinnedHostId,
            connectionCompatibility =
                SavedHostConnectionCompatibility.PRIVATE_KEY_REQUIRES_FULL_UI,
        )
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(
                    id = 1,
                    title = "alice@secret.example",
                    connectionState = ConnectionState.Connected,
                    workspaceName = "Production",
                    terminalTitle = " deploy\u001b shell ",
                    lastActivityAtEpochMillis = 90,
                    recentSessionId = openRecentId,
                    sourceProfileId = pinned.id,
                ),
            ),
            profiles = listOf(unpinned, pinned),
            recentSessions = listOf(
                endedSession(openRecentId, pinnedHostId, "Production"),
                endedSession(endedRecentId, unpinnedHostId, "Archive"),
            ),
        )

        val workspace = state.workspace

        assertEquals("Production", workspace.activeSessions.single().friendlyName)
        assertFalse(workspace.activeSessions.single().friendlyName.contains("secret.example"))
        assertEquals("deploy shell", workspace.activeSessions.single().terminalTitle)
        assertEquals(ConnectionProtocol.SSH, workspace.activeSessions.single().protocol)
        assertEquals(WorkspaceSessionStatus.CONNECTED, workspace.activeSessions.single().status)
        assertTrue(workspace.activeSessions.single().canDisconnect)
        assertTrue(workspace.activeSessions.single().canDuplicate)
        assertEquals(listOf(pinned.id, unpinned.id), workspace.pinnedHosts.map { it.profileId })
        assertTrue(workspace.pinnedHosts.single { it.profileId == unpinned.id }.canConnect)
        assertEquals(listOf(endedRecentId), workspace.recentConnections.map { it.id })
        assertEquals(unpinned.id, workspace.recentConnections.single().sourceProfileId)
    }

    @Test
    fun workspaceRecentConnectionsCollapseEquivalentEndpointsToNewestActivity() {
        val firstHostId = "10000000-0000-4000-8000-000000000021"
        val duplicateHostId = "10000000-0000-4000-8000-000000000022"
        val differentLoginHostId = "10000000-0000-4000-8000-000000000023"
        val oldSessionId = "10000000-0000-4000-8000-000000000024"
        val newestSessionId = "10000000-0000-4000-8000-000000000025"
        val differentLoginSessionId = "10000000-0000-4000-8000-000000000026"
        val differentPortHostId = "10000000-0000-4000-8000-000000000027"
        val differentPortSessionId = "10000000-0000-4000-8000-000000000028"
        val first = SavedSshProfile(
            id = 21,
            label = "Primary",
            host = "203.0.113.7",
            port = 22,
            username = "alice",
            persistentId = firstHostId,
        )
        val duplicate = SavedSshProfile(
            id = 22,
            label = "Same endpoint",
            host = "203.0.113.7".uppercase(),
            port = 22,
            username = "alice",
            persistentId = duplicateHostId,
        )
        val differentLogin = SavedSshProfile(
            id = 23,
            label = "Different login",
            host = "203.0.113.7",
            port = 22,
            username = "bob",
            persistentId = differentLoginHostId,
        )
        val differentPort = SavedSshProfile(
            id = 24,
            label = "Different port",
            host = "203.0.113.7",
            port = 2222,
            username = "alice",
            persistentId = differentPortHostId,
        )
        val state = TerminalSpikeUiState(
            profiles = listOf(first, duplicate, differentLogin, differentPort),
            recentSessions = listOf(
                endedSession(oldSessionId, firstHostId, "Primary").copy(
                    lastActivityAtEpochMillis = 40,
                    endedAtEpochMillis = 40,
                ),
                endedSession(differentLoginSessionId, differentLoginHostId, "Different login").copy(
                    lastActivityAtEpochMillis = 70,
                    endedAtEpochMillis = 70,
                ),
                endedSession(newestSessionId, duplicateHostId, "Same endpoint").copy(
                    lastActivityAtEpochMillis = 90,
                    endedAtEpochMillis = 90,
                ),
                endedSession(differentPortSessionId, differentPortHostId, "Different port").copy(
                    lastActivityAtEpochMillis = 80,
                    endedAtEpochMillis = 80,
                ),
            ),
        )

        val recent = state.workspace.recentConnections

        assertEquals(
            listOf(newestSessionId, differentPortSessionId, differentLoginSessionId),
            recent.map { it.id },
        )
        assertEquals(
            listOf(duplicate.id, differentPort.id, differentLogin.id),
            recent.map { it.sourceProfileId },
        )
    }

    @Test
    fun workspaceTitleSanitizerRemovesControlsAndBoundsPresentation() {
        val sanitized = sanitizeWorkspaceTerminalTitle(
            "  first\u0000\nsecond\u200B\u202E  ${"x".repeat(200)}",
        )

        assertNotNull(sanitized)
        assertFalse(sanitized!!.any(Char::isISOControl))
        assertFalse(sanitized.any { Character.getType(it) == Character.FORMAT.toInt() })
        assertTrue(sanitized.startsWith("first second"))
        assertEquals(128, sanitized.length)
    }

    @Test
    fun oscTitlesCannotPublishOrPersistKnownEndpointFragmentsButSafeTitlesSurvive() {
        val username = "deploy"
        val host = "private.example"
        val engine = VtTerminalEngine(columns = 80, rows = 24)
        val endpointTitle = engine.accept(
            "\u001B]0;de\u200Bploy@private.\u202Eexample shell\u0007".toByteArray(),
        ).terminalTitle
        val initial = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "$username@$host", ConnectionState.Connected),
            ),
        )

        val suppressed = initial.withSessionTerminalTitle(
            sessionId = 1,
            rawTitle = endpointTitle,
            sensitiveValues = listOf(username, host),
        )
        val suppressedRecord = buildRecentSessionRecord(
            id = "10000000-0000-4000-8000-000000000011",
            hostProfileId = "10000000-0000-4000-8000-000000000012",
            hostDisplayName = "Production",
            protocol = ConnectionProtocol.SSH,
            connectionState = ConnectionState.Connected,
            startedAtEpochMillis = 10,
            lastActivityAtEpochMillis = 20,
            rawTerminalTitle = endpointTitle,
            sensitiveValues = listOf(username, host),
        )

        assertNull(suppressed.sessions.first { it.id == 1L }.terminalTitle)
        assertNull(suppressedRecord.terminalTitle)

        val safeTitle = engine.accept("\u001B]2;release shell 🚀\u0007".toByteArray()).terminalTitle
        val published = initial.withSessionTerminalTitle(
            sessionId = 1,
            rawTitle = safeTitle,
            sensitiveValues = listOf(username, host),
        )
        val safeRecord = buildRecentSessionRecord(
            id = "10000000-0000-4000-8000-000000000013",
            hostProfileId = null,
            hostDisplayName = "Production",
            protocol = ConnectionProtocol.SSH,
            connectionState = ConnectionState.Connected,
            startedAtEpochMillis = 10,
            lastActivityAtEpochMillis = 20,
            rawTerminalTitle = safeTitle,
            sensitiveValues = listOf(username, host),
        )

        assertEquals("release shell 🚀", published.sessions.first { it.id == 1L }.terminalTitle)
        assertEquals("release shell 🚀", safeRecord.terminalTitle)
    }

    @Test
    fun workspaceProjectionSuppressesEndpointTitlesUsingTabAndSourceProfileContext() {
        val username = "operator"
        val host = "private.example"
        val profileId = "10000000-0000-4000-8000-000000000014"
        val profile = SavedSshProfile(
            id = 14,
            label = "Production",
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
                    workspaceName = "Production",
                    terminalTitle = "op\u200Berator console",
                ),
            ),
            profiles = listOf(profile),
            recentSessions = listOf(
                endedSession(
                    id = "10000000-0000-4000-8000-000000000015",
                    hostProfileId = profileId,
                    displayName = "Production",
                ).copy(terminalTitle = "private.\u202Eexample shell"),
            ),
        )

        assertNull(state.workspace.activeSessions.single().terminalTitle)
        assertNull(state.workspace.recentConnections.single().terminalTitle)
    }

    @Test
    fun terminalTitlePublicationSanitizesOnlyTheTargetSession() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "one", ConnectionState.Connected, terminalTitle = "old"),
                SessionTabUi(2, "two", ConnectionState.Connected, terminalTitle = "untouched"),
            ),
        )

        val updated = state.withSessionTerminalTitle(1, "  deploy\u001B\n shell  ")

        assertEquals("deploy shell", updated.sessions.first { it.id == 1L }.terminalTitle)
        assertEquals("untouched", updated.sessions.first { it.id == 2L }.terminalTitle)
        assertEquals(state, state.withSessionTerminalTitle(99, "ignored"))
    }

    @Test
    fun recentSessionRecordPersistsOnlyBoundedSanitizedTerminalTitle() {
        val record = buildRecentSessionRecord(
            id = "10000000-0000-4000-8000-000000000008",
            hostProfileId = "10000000-0000-4000-8000-000000000009",
            hostDisplayName = "Production",
            protocol = ConnectionProtocol.SSH,
            connectionState = ConnectionState.Connected,
            startedAtEpochMillis = 10,
            lastActivityAtEpochMillis = 20,
            rawTerminalTitle = "  release\u0000 shell ${"x".repeat(200)}  ",
        )

        assertEquals(SessionState.CONNECTED, record.state)
        assertEquals(null, record.endedAtEpochMillis)
        val persistedTitle = requireNotNull(record.terminalTitle)
        assertFalse(persistedTitle.any(Char::isISOControl))
        assertEquals(128, persistedTitle.length)
    }

    @Test
    fun workspaceLastActivityUsesStableHumanScaleBuckets() {
        val now = 10L * 86_400_000L

        assertEquals(
            uiText(R.string.workspace_last_activity_unavailable),
            formatWorkspaceLastActivity(0, now),
        )
        assertEquals(
            uiText(R.string.workspace_last_activity_just_now),
            formatWorkspaceLastActivity(now - 30_000, now),
        )
        assertEquals(
            quantityText(R.plurals.workspace_last_activity_minutes, 3),
            formatWorkspaceLastActivity(now - 180_000, now),
        )
        assertEquals(
            quantityText(R.plurals.workspace_last_activity_hours, 2),
            formatWorkspaceLastActivity(now - 7_200_000, now),
        )
        assertEquals(
            quantityText(R.plurals.workspace_last_activity_days, 2),
            formatWorkspaceLastActivity(now - 172_800_000, now),
        )
    }

    @Test
    fun runtimeCredentialFailureExposesRecoveryWithoutDroppingMetadata() {
        val profile = SavedSshProfile(
            id = 4,
            label = "Server",
            host = "server.example",
            port = 22,
            username = "alice",
            hasSavedPassword = true,
        )
        val identity = SavedSshIdentity(
            id = 5,
            label = "Key",
            keyType = "ssh-ed25519",
            fingerprint = "SHA256:key",
            passphraseRequired = false,
            recoveryToken = "6dd39adc-c76f-4de1-92b9-137d90af58e2",
        )
        val initial = TerminalSpikeUiState(profiles = listOf(profile), identities = listOf(identity))

        val passwordFailure = initial.afterStoredCredentialUnavailable(profileId = profile.id)
        assertEquals(profile.copy(hasSavedPassword = false), passwordFailure.profiles.single())
        assertEquals(identity, passwordFailure.identities.single())

        val keyFailure = initial.afterStoredCredentialUnavailable(identityId = identity.id)
        assertEquals(profile, keyFailure.profiles.single())
        assertEquals(identity.copy(isAvailable = false), keyFailure.identities.single())
        assertEquals(identity.recoveryToken, keyFailure.identities.single().recoveryToken)
    }

    @Test
    fun multilineSnippetConfirmationCannotFollowFocusToAnotherHost() {
        val queuedForSessionOne = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "one", ConnectionState.Connected),
                SessionTabUi(2, "two", ConnectionState.Connected),
            ),
            activeSessionId = 1,
            pendingSnippetSendId = 9,
            pendingSnippetTargetSessionId = 1,
        )

        assertTrue(queuedForSessionOne.canConfirmSnippetOnActiveSession(9))
        assertFalse(
            queuedForSessionOne.copy(activeSessionId = 2).canConfirmSnippetOnActiveSession(9),
        )
    }

    @Test
    fun insertSnippetHonoursConfiguredAppendEnter() {
        val snippet = CommandSnippet(
            id = 8,
            label = "Draft",
            command = "printf one\nprintf two",
            appendEnter = true,
            confirmMultilineExecution = false,
            sendsImmediately = false,
        )
        var enqueueCalls = 0
        var appendedEnter: Boolean? = null

        val outcome = dispatchSnippet(snippet, confirmed = true) { appendEnter ->
            enqueueCalls += 1
            appendedEnter = appendEnter
            true
        }

        assertEquals(SnippetDispatchOutcome.INSERTED, outcome)
        assertEquals(1, enqueueCalls)
        assertEquals(true, appendedEnter)
    }

    @Test
    fun committedKnownHostForgetUsesNonResurrectingFallbackWhenReloadFails() {
        val forgotten = KnownHostSummary(
            host = "example.com",
            algorithm = "ssh-ed25519",
            sha256Fingerprint = "SHA256:forgotten",
        )
        val retained = KnownHostSummary(
            host = "other.example",
            algorithm = "ssh-ed25519",
            sha256Fingerprint = "SHA256:retained",
        )
        val fallback = listOf(forgotten, retained).filterNot { it.host == forgotten.host }

        val refresh = refreshKnownHostsOrFallback(fallback) {
            error("Injected post-commit read failure")
        }

        assertFalse(refresh.refreshed)
        assertEquals(listOf(retained), refresh.knownHosts)
    }

    @Test
    fun postConnectKnownHostRefreshFailurePreservesExistingTrustPresentation() {
        val current = listOf(
            KnownHostSummary(
                host = "example.com",
                algorithm = "ssh-ed25519",
                sha256Fingerprint = "SHA256:existing",
            ),
        )

        val refresh = refreshKnownHostsOrFallback(current) {
            error("Injected list failure")
        }

        assertFalse(refresh.refreshed)
        assertEquals(current, refresh.knownHosts)
    }

    @Test
    fun identityRecoveryUsesStableTokenAfterProcessLocalIdsAreRebuilt() {
        val intendedToken = "83a714cb-846f-4bdc-a794-f3bc6597a592"
        val decoy = SavedSshIdentity(
            id = 7,
            label = "Different key",
            keyType = "ssh-ed25519",
            fingerprint = "SHA256:decoy",
            passphraseRequired = false,
            recoveryToken = "235c9151-6e9b-434c-bbba-ac1aa3c1f022",
        )
        val rebuiltTarget = SavedSshIdentity(
            id = 19,
            label = "Recovered key",
            keyType = "ssh-ed25519",
            fingerprint = "SHA256:target",
            passphraseRequired = false,
            isAvailable = false,
            recoveryToken = intendedToken,
        )

        assertEquals(
            rebuiltTarget,
            resolveIdentityRecoveryTarget(listOf(decoy, rebuiltTarget), intendedToken),
        )
        assertEquals(
            decoy,
            resolveIdentityRecoveryTarget(listOf(decoy, rebuiltTarget), decoy.recoveryToken!!),
        )
        assertNull(resolveIdentityRecoveryTarget(listOf(decoy), intendedToken))
    }

    @Test
    fun catalogPublicationSerializesAuthoritativeReloadThroughUiPublication() = runTest {
        var authoritative = "older"
        var loadCount = 0
        val olderPublicationEntered = CompletableDeferred<Unit>()
        val releaseOlderPublication = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val gate = LatestValuePublicationGate {
            loadCount += 1
            authoritative
        }

        val older = launch {
            gate.publish { value ->
                olderPublicationEntered.complete(Unit)
                releaseOlderPublication.await()
                published += value
            }
        }
        olderPublicationEntered.await()
        authoritative = "newer"
        val newer = launch {
            gate.publish { value -> published += value }
        }
        yield()

        assertEquals(1, loadCount)
        assertTrue(published.isEmpty())

        releaseOlderPublication.complete(Unit)
        older.join()
        newer.join()

        assertEquals(2, loadCount)
        assertEquals(listOf("older", "newer"), published)
    }

    @Test
    fun multilineSnippetQueuesNothingUntilExplicitConfirmation() {
        val snippet = CommandSnippet(
            id = 7,
            label = "Deploy",
            command = "printf one\nprintf two",
            appendEnter = true,
            confirmMultilineExecution = true,
        )
        var enqueueCalls = 0
        var appendedEnter: Boolean? = null

        assertEquals(
            SnippetDispatchOutcome.REQUIRES_CONFIRMATION,
            dispatchSnippet(snippet, confirmed = false) { appendEnter ->
                enqueueCalls += 1
                appendedEnter = appendEnter
                true
            },
        )
        assertEquals(0, enqueueCalls)
        assertEquals(
            SnippetDispatchOutcome.SENT,
            dispatchSnippet(snippet, confirmed = true) { appendEnter ->
                enqueueCalls += 1
                appendedEnter = appendEnter
                true
            },
        )
        assertEquals(1, enqueueCalls)
        assertEquals(true, appendedEnter)
    }

    @Test
    fun activeSshTabSelectsSshWorkspace() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "dev@example", ConnectionState.Connected),
            ),
            activeSessionId = 1,
        )

        assertTrue(state.sshMode)
        assertTrue(state.connectionState is ConnectionState.Connected)
        assertTrue(state.canAddSshSession)
        assertTrue(state.canSendTerminalInput)
    }

    @Test
    fun workspaceOneTapStartsOnlyProfilesWithUsableStoredPasswords() {
        val storedPassword = SavedSshProfile(
            id = 7,
            label = "Production",
            host = "production.example",
            port = 22,
            username = "operator",
            hasSavedPassword = true,
        )
        val state = TerminalSpikeUiState(
            profiles = listOf(storedPassword),
            settingsReady = true,
        )

        assertEquals(
            WorkspaceProfileLaunchDecision.START_WITH_SAVED_PASSWORD,
            state.workspaceProfileLaunchDecision(storedPassword.id),
        )
        assertEquals(
            WorkspaceProfileLaunchDecision.REQUEST_AUTHENTICATION,
            state.copy(profiles = listOf(storedPassword.copy(hasSavedPassword = false)))
                .workspaceProfileLaunchDecision(storedPassword.id),
        )
    }

    @Test
    fun workspaceCatalogStartsStoredPasswordsAndPromptsForUnsavedPasswords() {
        val storedPassword = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.Password(WORKSPACE_SECRET_ID),
            savedSecretAvailability = CatalogSecretAvailability.AVAILABLE,
        )
        val promptOnly = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.Password(),
        )
        val unavailableStoredPassword = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.Password(WORKSPACE_SECRET_ID),
            savedSecretAvailability = CatalogSecretAvailability.UNAVAILABLE,
        )
        val missingCredential = workspaceAuthenticationCatalog(authentication = null)

        assertEquals(
            WorkspaceStoredAuthenticationDecision.START_DIRECTLY,
            storedPassword.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
        assertEquals(
            WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION,
            promptOnly.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
        assertEquals(
            WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION,
            unavailableStoredPassword.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
        assertEquals(
            WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION,
            missingCredential.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
    }

    @Test
    fun workspaceCatalogUsesAvailablePrivateKeysAndPromptsOnlyForMissingPassphrases() {
        val unprotected = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.PrivateKey(WORKSPACE_KEY_ID),
            identityPassphraseProtected = false,
        )
        val protectedWithSavedPassphrase = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.PrivateKey(
                keyIdentityId = WORKSPACE_KEY_ID,
                passphraseSecretReferenceId = WORKSPACE_SECRET_ID,
            ),
            savedSecretAvailability = CatalogSecretAvailability.AVAILABLE,
            identityPassphraseProtected = true,
        )
        val protectedPromptOnly = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.PrivateKey(WORKSPACE_KEY_ID),
            identityPassphraseProtected = true,
        )

        assertEquals(
            WorkspaceStoredAuthenticationDecision.START_DIRECTLY,
            unprotected.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
        assertEquals(
            WorkspaceStoredAuthenticationDecision.START_DIRECTLY,
            protectedWithSavedPassphrase.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
        assertEquals(
            WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION,
            protectedPromptOnly.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
    }

    @Test
    fun workspaceCatalogStartsInteractiveAuthButRejectsAnUnavailablePrivateKey() {
        val interactive = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.KeyboardInteractive(),
        )
        val unavailableKey = workspaceAuthenticationCatalog(
            authentication = StoredSshAuthentication.PrivateKey(WORKSPACE_KEY_ID),
            identityPassphraseProtected = false,
            privateKeyAvailability = CatalogSecretAvailability.UNAVAILABLE,
        )

        assertEquals(
            WorkspaceStoredAuthenticationDecision.START_DIRECTLY,
            interactive.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
        assertEquals(
            WorkspaceStoredAuthenticationDecision.UNAVAILABLE,
            unavailableKey.workspaceStoredAuthenticationDecision(WORKSPACE_HOST_ID),
        )
    }

    @Test
    fun quickConnectSaveMapsAuthenticationWithoutRetainingTransientKeyPassphrases() {
        val privateKey = quickConnectAuthenticationUpdate(
            persistentIdentityId = WORKSPACE_KEY_ID,
            savePassword = false,
            password = byteArrayOf(),
            retainSavedPassword = false,
        )
        assertEquals(HostAuthenticationUpdate.PrivateKey(WORKSPACE_KEY_ID), privateKey)

        assertEquals(
            HostAuthenticationUpdate.PromptPassword,
            quickConnectAuthenticationUpdate(
                persistentIdentityId = null,
                savePassword = false,
                password = byteArrayOf(1),
                retainSavedPassword = false,
            ),
        )
        assertEquals(
            HostAuthenticationUpdate.RetainSavedPassword,
            quickConnectAuthenticationUpdate(
                persistentIdentityId = null,
                savePassword = false,
                password = byteArrayOf(),
                retainSavedPassword = true,
            ),
        )

        val transientPassword = byteArrayOf(4, 5, 6)
        val savedPassword = quickConnectAuthenticationUpdate(
            persistentIdentityId = null,
            savePassword = true,
            password = transientPassword,
            retainSavedPassword = false,
        ) as HostAuthenticationUpdate.SavePassword
        transientPassword.fill(9)
        assertArrayEquals(byteArrayOf(4, 5, 6), savedPassword.secret)
        savedPassword.secret.fill(0)
    }

    @Test
    fun committedQuickConnectPasswordUsesEncryptedReloadInsteadOfOneShotAuthentication() {
        val password = byteArrayOf(7, 8, 9)
        val profile = SavedSshProfile(
            id = 7,
            label = "Production",
            host = "production.example",
            port = 22,
            username = "operator",
            hasSavedPassword = true,
        )

        val start = resolveQuickConnectPasswordStart(
            savedPasswordCommitted = true,
            password = password,
            persistedProfile = profile,
            previouslySavedProfile = null,
        )

        assertNull(start.password)
        assertEquals(profile, start.savedPasswordProfile)
        assertArrayEquals(byteArrayOf(0, 0, 0), password)
    }

    @Test
    fun quickConnectSavedHostRetainsCompleteProfileOptions() {
        val existing = HostProfile(
            id = WORKSPACE_HOST_ID,
            displayName = "Old name",
            hostname = "old.example",
            port = 22,
            username = "old-user",
            protocol = ConnectionProtocol.MOSH,
            credentialId = WORKSPACE_CREDENTIAL_ID,
            terminalProfileId = WORKSPACE_TERMINAL_PROFILE_ID,
            keyboardProfileId = WORKSPACE_KEYBOARD_PROFILE_ID,
            isFavorite = true,
            group = "Operations",
            tag = "Primary",
            startupCommand = "tmux attach",
            keepaliveIntervalSeconds = 45,
            reconnectPolicy = ReconnectPolicy.AUTOMATIC,
            moshPort = 60_001,
            moshServerCommand = "/usr/bin/mosh-server",
            moshLocale = "en_GB.UTF-8",
            moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val compatibilityProfile = SavedSshProfile(
            id = 7,
            label = "Production",
            host = "production.example",
            port = 2222,
            username = "operator",
            persistentId = WORKSPACE_HOST_ID,
            protocol = ConnectionProtocol.MOSH,
        )
        val options = RemoteConnectionOptions(
            protocol = ConnectionProtocol.MOSH,
            moshPortRange = MoshPortRange(60_010, 60_020),
            moshServerCommand = "/usr/local/bin/mosh-server",
            moshLocale = "en_GB.UTF-8",
            moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
        )

        val saved = compatibilityProfile.toAuthoritativeQuickConnectProfile(
            catalog = workspaceAuthenticationCatalog(authentication = null),
            existing = existing,
            connectionOptions = options,
            nowEpochMillis = 30,
        )

        assertEquals("Production", saved.displayName)
        assertEquals("production.example", saved.hostname)
        assertEquals(2222, saved.port)
        assertEquals("operator", saved.username)
        assertEquals(WORKSPACE_TERMINAL_PROFILE_ID, saved.terminalProfileId)
        assertEquals(WORKSPACE_KEYBOARD_PROFILE_ID, saved.keyboardProfileId)
        assertTrue(saved.isFavorite)
        assertEquals("Operations", saved.group)
        assertEquals("Primary", saved.tag)
        assertEquals("tmux attach", saved.startupCommand)
        assertEquals(45, saved.keepaliveIntervalSeconds)
        assertEquals(ReconnectPolicy.AUTOMATIC, saved.reconnectPolicy)
        assertEquals(MoshPortRange(60_010, 60_020), saved.moshPortRange)
        assertEquals("/usr/local/bin/mosh-server", saved.moshServerCommand)
        assertEquals("en_GB.UTF-8", saved.moshLocale)
        assertEquals(MoshFallbackPolicy.AUTOMATIC, saved.moshFallbackPolicy)
        assertEquals(10L, saved.createdAtEpochMillis)
        assertEquals(30L, saved.updatedAtEpochMillis)
    }

    @Test
    fun workspaceMoshLaunchRequiresAVerifiedAvailableExtension() {
        val mosh = SavedSshProfile(
            id = 8,
            label = "Roaming shell",
            host = "mobile.example",
            port = 22,
            username = "operator",
            hasSavedPassword = true,
            connectionCompatibility = SavedHostConnectionCompatibility.MOSH_PASSWORD,
            protocol = ConnectionProtocol.MOSH,
            isFavorite = true,
        )
        val absent = TerminalSpikeUiState(
            profiles = listOf(mosh),
            settingsReady = true,
            moshExtension = MoshExtensionUiState(
                kind = MoshExtensionUiKind.ABSENT,
                statusLabel = UiText.Dynamic("Not installed"),
                summary = UiText.Dynamic("Unavailable"),
                verificationMessage = UiText.Dynamic("Not verified"),
            ),
        )

        assertEquals(
            WorkspaceProfileLaunchDecision.MOSH_EXTENSION_UNAVAILABLE,
            absent.workspaceProfileLaunchDecision(mosh.id),
        )
        assertFalse(absent.workspace.pinnedHosts.any { it.canConnect })

        val available = absent.copy(
            moshExtension = MoshExtensionUiState(
                kind = MoshExtensionUiKind.AVAILABLE,
                statusLabel = UiText.Dynamic("Available"),
                summary = UiText.Dynamic("Ready"),
                verificationMessage = UiText.Dynamic("Verified"),
            ),
            profiles = listOf(mosh),
        )
        assertEquals(
            WorkspaceProfileLaunchDecision.START_WITH_SAVED_PASSWORD,
            available.workspaceProfileLaunchDecision(mosh.id),
        )
        assertTrue(available.workspace.pinnedHosts.single().canConnect)
    }

    @Test
    fun moshInputParsingAndBootstrapMappingKeepOnlyTypedOptions() {
        val parsed = parseMoshConnectionOptions(
            udpPortOrRange = "60000:60010",
            serverExecutable = "/usr/local/bin/mosh-server",
        )
        assertNull(parsed.error)
        assertEquals(
            RemoteConnectionOptions(
                protocol = ConnectionProtocol.MOSH,
                moshPortRange = MoshPortRange(60_000, 60_010),
                moshServerCommand = "/usr/local/bin/mosh-server",
            ),
            parsed.options,
        )

        val authentication = SshAuthentication.Password(byteArrayOf(1, 2, 3))
        val ssh = SshConnectionConfig("mobile.example", 2222, "operator", authentication)
        val request = requireNotNull(parsed.options).toMoshBootstrapRequest(ssh)
        assertTrue(request.ssh === ssh)
        assertTrue(request.ssh.authentication === authentication)
        assertEquals(MoshPortRange(60_000, 60_010), request.udpPortRange)
        assertEquals("/usr/local/bin/mosh-server", request.serverCommand)
        assertNull(request.udpPort)

        val storedPassword = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) }
        val storedRequest = RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH).toMoshBootstrapRequest(
            SshConnectionConfig("mobile.example", 22, "operator", storedPassword),
        )
        assertTrue(storedRequest.ssh.authentication === storedPassword)
        val privateKey = SshAuthentication.PrivateKey(
            identityName = "Device key",
            loadKey = { byteArrayOf(7, 8, 9) },
            passphrase = byteArrayOf(10),
        )
        val keyRequest = RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH).toMoshBootstrapRequest(
            SshConnectionConfig("mobile.example", 22, "operator", privateKey),
        )
        assertTrue(keyRequest.ssh.authentication === privateKey)

        assertNotNull(parseMoshConnectionOptions("70000", "mosh-server").error)
        assertNotNull(parseMoshConnectionOptions("60000:59999", "mosh-server").error)
        assertNotNull(parseMoshConnectionOptions("", "mosh-server new -s").error)
        val defaults = requireNotNull(parseMoshConnectionOptions("", "mosh-server").options)
        assertEquals(ConnectionProtocol.MOSH, defaults.protocol)
        assertNull(defaults.moshServerCommand)
    }

    @Test
    fun workspaceOneTapPreservesCompatibilityAndCapacityGates() {
        val compatible = SavedSshProfile(
            id = 7,
            label = "Production",
            host = "production.example",
            port = 22,
            username = "operator",
            hasSavedPassword = true,
        )
        val ready = TerminalSpikeUiState(
            profiles = listOf(compatible),
            settingsReady = true,
        )

        listOf(
            SavedHostConnectionCompatibility.MOSH_PASSWORD,
            SavedHostConnectionCompatibility.MOSH_UNAVAILABLE,
            SavedHostConnectionCompatibility.PRIVATE_KEY_REQUIRES_FULL_UI,
            SavedHostConnectionCompatibility.KEYBOARD_INTERACTIVE_UNAVAILABLE,
            SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI,
        ).forEach { compatibility ->
            assertEquals(
                WorkspaceProfileLaunchDecision.PROFILE_UNAVAILABLE,
                ready.copy(
                    profiles = listOf(compatible.copy(connectionCompatibility = compatibility)),
                ).workspaceProfileLaunchDecision(compatible.id),
            )
        }
        assertEquals(
            WorkspaceProfileLaunchDecision.SETTINGS_UNAVAILABLE,
            ready.copy(settingsReady = false).workspaceProfileLaunchDecision(compatible.id),
        )
        assertEquals(
            WorkspaceProfileLaunchDecision.KEYBOARD_RUNTIME_UNAVAILABLE,
            ready.copy(keyboardRuntimeCompatible = false)
                .workspaceProfileLaunchDecision(compatible.id),
        )
        assertEquals(
            WorkspaceProfileLaunchDecision.PROFILE_MISSING,
            ready.workspaceProfileLaunchDecision(profileId = 99),
        )

        val full = ready.copy(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "one", ConnectionState.Connected),
                SessionTabUi(2, "two", ConnectionState.Connected),
                SessionTabUi(3, "three", ConnectionState.Connected),
                SessionTabUi(4, "four", ConnectionState.Connected),
            ),
        )
        assertEquals(
            WorkspaceProfileLaunchDecision.START_WITH_SAVED_PASSWORD,
            full.workspaceProfileLaunchDecision(compatible.id),
        )
    }

    @Test
    fun workspaceOneTapNewSessionRetainsEveryOpenTab() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "Existing", ConnectionState.Connected),
            ),
            activeSessionId = 1,
        )
        val started = SessionTabUi(2, "New", ConnectionState.Connecting)

        val updated = state.withStartedSshSession(started, replacementSessionId = null)

        assertEquals(listOf(0L, 1L, 2L), updated.sessions.map(SessionTabUi::id))
        assertEquals(2L, updated.activeSessionId)
        assertTrue(updated.sessions[1].connectionState is ConnectionState.Connected)
    }

    @Test
    fun moreSshTabsCanBeAddedAfterFourAreOpen() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "one", ConnectionState.Connected),
                SessionTabUi(2, "two", ConnectionState.Connecting),
                SessionTabUi(3, "three", ConnectionState.Disconnected),
                SessionTabUi(4, "four", ConnectionState.Failed("failed")),
            ),
        )

        assertTrue(state.canAddSshSession)
    }

    @Test
    fun reconnectAndDuplicateRemainAvailableAfterFourTabs() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "one", ConnectionState.Connected),
                SessionTabUi(2, "two", ConnectionState.Connecting),
                SessionTabUi(3, "three", ConnectionState.Failed("failed")),
                SessionTabUi(4, "four", ConnectionState.Disconnected),
            ),
        )

        val failedWorkspaceSession = state.workspace.activeSessions.first { it.id == 3L }
        val disconnectedWorkspaceSession = state.workspace.activeSessions.first { it.id == 4L }
        assertTrue(failedWorkspaceSession.canReconnect)
        assertTrue(disconnectedWorkspaceSession.canReconnect)
        assertTrue(failedWorkspaceSession.canDuplicate)
        assertTrue(disconnectedWorkspaceSession.canDuplicate)
        assertTrue(state.canStartSshSession(replacementSessionId = 3L))
        assertTrue(state.canStartSshSession(replacementSessionId = 4L))
        assertFalse(state.canStartSshSession(replacementSessionId = 1L))
        assertTrue(state.canStartSshSession(replacementSessionId = null))

        val replacement = SessionTabUi(3, "three", ConnectionState.Connecting)
        val reconnected = state.withStartedSshSession(replacement, replacementSessionId = 3L)

        assertEquals(state.sessions.size, reconnected.sessions.size)
        assertEquals(state.sessions.map { it.id }, reconnected.sessions.map { it.id })
        assertEquals(3L, reconnected.activeSessionId)
        assertTrue(reconnected.sessions.single { it.id == 3L }.connectionState is ConnectionState.Connecting)
    }

    @Test
    fun duplicateUsesANewTabForUnsavedSessions() {
        val unsaved = SessionTabUi(
            id = 1,
            title = "ad-hoc",
            connectionState = ConnectionState.Disconnected,
            sourceProfileId = null,
        )
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                unsaved,
            ),
        )

        val workspaceSession = state.workspace.activeSessions.single()
        assertTrue(workspaceSession.canReconnect)
        assertTrue(workspaceSession.canDuplicate)
        assertTrue(state.canStartSshSession(replacementSessionId = null))

        val duplicated = state.withStartedSshSession(
            session = unsaved.copy(id = 2, connectionState = ConnectionState.Connecting),
            replacementSessionId = null,
        )
        assertEquals(3, duplicated.sessions.size)
        assertEquals(listOf(0L, 1L, 2L), duplicated.sessions.map { it.id })
        assertEquals(2L, duplicated.activeSessionId)
    }

    @Test
    fun newSessionNamesUseTheFirstAvailableProtocolSuffix() {
        val sessions = listOf(
            SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
            SessionTabUi(1, "ssh-1", ConnectionState.Connected, workspaceName = "ssh-1"),
            SessionTabUi(
                2,
                "Mosh custom",
                ConnectionState.Connected,
                workspaceName = "mosh-2",
                protocol = ConnectionProtocol.MOSH,
            ),
        )

        assertEquals("ssh-2", nextDefaultSessionName(ConnectionProtocol.SSH, sessions))
        assertEquals("mosh-1", nextDefaultSessionName(ConnectionProtocol.MOSH, sessions))
    }

    @Test
    fun authoritativePasswordHostWithProfileOptionsRemainsQuickConnectable() {
        val profile = SavedSshProfile(
            id = 7,
            label = "Production",
            host = "internal.example",
            port = 22,
            username = "operator",
            hasSavedPassword = true,
            persistentId = "10000000-0000-4000-8000-000000000007",
            connectionCompatibility =
                SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI,
        )

        assertTrue(profile.canConnectFromQuickUi)
        assertTrue(profile.connectionSeed().matches(profile))
    }

    @Test
    fun reconnectSeedContainsOnlyEndpointAndMatchesOnlyTheOriginalCredentialScope() {
        val seed = SshConnectionSeed(
            host = "internal.example",
            port = 2222,
            username = "operator",
            sourceProfileId = 7L,
        )
        val matching = SavedSshProfile(
            id = 7L,
            label = "Production",
            host = "internal.example",
            port = 2222,
            username = "operator",
            hasSavedPassword = true,
        )

        assertTrue(seed.matches(matching))
        assertFalse(seed.matches(matching.copy(host = "replacement.example")))
        assertFalse(seed.matches(matching.copy(username = "different")))
        assertFalse(seed.matches(matching.copy(id = 8L)))
        val fieldNames = SshConnectionSeed::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { "password" in it || "passphrase" in it || "secret" in it })
    }

    @Test
    fun reconnectSeedPreservesMoshProtocolAndNonSecretServerOptions() {
        val profile = SavedSshProfile(
            id = 11L,
            label = "Roaming",
            host = "mobile.example",
            port = 2222,
            username = "operator",
            connectionCompatibility = SavedHostConnectionCompatibility.MOSH_PASSWORD,
            protocol = ConnectionProtocol.MOSH,
            moshPort = 60_001,
            moshServerCommand = "/usr/bin/mosh-server",
        )

        val seed = profile.connectionSeed()

        assertEquals(ConnectionProtocol.MOSH, seed.connectionOptions.protocol)
        assertEquals(60_001, seed.connectionOptions.moshPort)
        assertEquals("/usr/bin/mosh-server", seed.connectionOptions.moshServerCommand)
        assertTrue(seed.matches(profile))
        assertFalse(seed.matches(profile.copy(moshPort = 60_002)))
    }

    @Test
    fun disconnectedSshCannotSendBufferedInput() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "offline", ConnectionState.Disconnected),
            ),
            activeSessionId = 1,
        )

        assertFalse(state.canSendTerminalInput)
    }

    @Test
    fun benchmarkAcceptsBufferedInputThroughItsLocalSink() {
        assertTrue(TerminalSpikeUiState().canSendTerminalInput)
    }

    @Test
    fun bufferedInputAllowsAnyConnectedSessionTarget() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isLocalTerminal = true),
                SessionTabUi(1, "one", ConnectionState.Connected),
                SessionTabUi(2, "two", ConnectionState.Connected),
            ),
            activeSessionId = 2,
        )

        assertNull(state.bufferedInputValidationError(sessionId = 1, text = "pwd"))
        assertNull(state.bufferedInputValidationError(sessionId = 2, text = "pwd"))
    }

    @Test
    fun bufferedInputAllowsNewlinesButRejectsHiddenControlCharacters() {
        val state = TerminalSpikeUiState()

        assertNull(state.bufferedInputValidationError(sessionId = 0, text = "first\nsecond"))
        assertEquals(
            uiText(R.string.notice_buffer_unsupported_control),
            state.bufferedInputValidationError(sessionId = 0, text = "first\u001bsecond"),
        )
    }

    @Test
    fun bufferedInputRejectsMalformedUnicodeInsteadOfSubstitutingBytes() {
        val state = TerminalSpikeUiState()

        assertEquals(
            uiText(R.string.notice_buffer_invalid_unicode),
            state.bufferedInputValidationError(sessionId = 0, text = "broken \uD83D"),
        )
        assertNull(state.bufferedInputValidationError(sessionId = 0, text = "paired 😀"))
    }

    @Test
    fun presentedNoticeIsConsumedOnlyWhenItIsStillCurrent() {
        val currentNotice = uiText(R.string.notice_terminal_keys_saved)
        val state = TerminalSpikeUiState(notice = currentNotice)

        assertEquals(
            state,
            state.afterNoticePresented(UiText.Dynamic("An older notice")),
        )
        assertNull(
            state.afterNoticePresented(currentNotice).notice,
        )
    }

    @Test
    fun settingsRecoveryNeverPresentsEmptyFallbackDataAsReady() {
        val recovered = TerminalSpikeUiState(settingsReady = true).afterSettingsLoad(
            loaded = SettingsLoadResult(
                settings = UserSettings(),
                warning = "Encrypted data was preserved.",
                failure = SettingsLoadFailure.KEY_UNAVAILABLE,
            ),
            knownHosts = emptyList(),
            retry = false,
        )

        assertFalse(recovered.settingsReady)
        assertEquals(SettingsLoadFailure.KEY_UNAVAILABLE, recovered.settingsRecoveryFailure)
        assertEquals(UiText.Dynamic("Encrypted data was preserved."), recovered.notice)
    }

    @Test
    fun successfulRecoveryRetryReturnsToReadyState() {
        val recovered = TerminalSpikeUiState(
            settingsReady = false,
            settingsRecoveryFailure = SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED,
            settingsRecoveryInProgress = true,
        ).afterSettingsLoad(
            loaded = SettingsLoadResult(UserSettings()),
            knownHosts = emptyList(),
            retry = true,
        )

        assertTrue(recovered.settingsReady)
        assertNull(recovered.settingsRecoveryFailure)
        assertFalse(recovered.settingsRecoveryInProgress)
        assertEquals(uiText(R.string.notice_settings_available_again), recovered.notice)
    }

    @Test
    fun failedRecoveryRetryNeverClaimsThatDataIsAvailable() {
        val recovered = TerminalSpikeUiState(
            settingsReady = false,
            settingsRecoveryFailure = SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED,
            settingsRecoveryInProgress = true,
            notice = UiText.Dynamic("Data is still unavailable."),
        ).afterSettingsLoad(
            loaded = SettingsLoadResult(
                settings = UserSettings(),
                failure = SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED,
            ),
            knownHosts = emptyList(),
            retry = true,
        )

        assertFalse(recovered.settingsReady)
        assertEquals(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED, recovered.settingsRecoveryFailure)
        assertFalse(recovered.settingsRecoveryInProgress)
        assertEquals(UiText.Dynamic("Data is still unavailable."), recovered.notice)
    }

    private fun workspaceAuthenticationCatalog(
        authentication: StoredSshAuthentication?,
        savedSecretAvailability: CatalogSecretAvailability =
            CatalogSecretAvailability.NOT_CONFIGURED,
        identityPassphraseProtected: Boolean? = null,
        privateKeyAvailability: CatalogSecretAvailability = CatalogSecretAvailability.AVAILABLE,
    ) = TerminalDataCatalog(
        hosts = listOf(
            CatalogHostProfile(
                presentationId = 7,
                profile = HostProfile(
                    id = WORKSPACE_HOST_ID,
                    displayName = "Production",
                    hostname = "production.example",
                    port = 22,
                    username = "operator",
                    protocol = ConnectionProtocol.SSH,
                    credentialId = authentication?.let { WORKSPACE_CREDENTIAL_ID },
                    createdAtEpochMillis = 10,
                    updatedAtEpochMillis = 20,
                ),
            ),
        ),
        credentials = authentication?.let {
            listOf(
                CatalogSshCredential(
                    id = WORKSPACE_CREDENTIAL_ID,
                    displayName = "Production login",
                    authentication = it,
                    savedSecretAvailability = savedSecretAvailability,
                    createdAtEpochMillis = 10,
                    updatedAtEpochMillis = 20,
                ),
            )
        }.orEmpty(),
        identities = identityPassphraseProtected?.let { passphraseProtected ->
            listOf(
                CatalogSshKeyIdentity(
                    presentationId = 8,
                    id = WORKSPACE_KEY_ID,
                    name = "Production key",
                    algorithm = "ssh-ed25519",
                    publicKeyFingerprint = "SHA256:test",
                    publicKey = "ssh-ed25519 AAAA test",
                    origin = SshKeyOrigin.IMPORTED,
                    isPassphraseProtected = passphraseProtected,
                    privateKeyAvailability = privateKeyAvailability,
                    createdAtEpochMillis = 10,
                    updatedAtEpochMillis = 20,
                    comment = null,
                ),
            )
        }.orEmpty(),
        snippets = emptyList(),
        terminalProfiles = emptyList(),
        keyboardProfiles = emptyList(),
        defaultTerminalProfileId = WORKSPACE_TERMINAL_PROFILE_ID,
        defaultKeyboardProfileId = WORKSPACE_KEYBOARD_PROFILE_ID,
        unavailableSecretCount = 0,
        migrationWarningCodes = emptyList(),
    )

    private fun endedSession(
        id: String,
        hostProfileId: String,
        displayName: String,
    ) = RecentSession(
        id = id,
        hostProfileId = hostProfileId,
        hostDisplayName = displayName,
        protocol = ConnectionProtocol.SSH,
        state = SessionState.DISCONNECTED,
        startedAtEpochMillis = 10,
        lastActivityAtEpochMillis = 20,
        endedAtEpochMillis = 20,
        terminalTitle = null,
    )

    private companion object {
        const val WORKSPACE_HOST_ID = "10000000-0000-4000-8000-000000000101"
        const val WORKSPACE_CREDENTIAL_ID = "10000000-0000-4000-8000-000000000102"
        const val WORKSPACE_KEY_ID = "10000000-0000-4000-8000-000000000103"
        const val WORKSPACE_SECRET_ID = "10000000-0000-4000-8000-000000000104"
        const val WORKSPACE_TERMINAL_PROFILE_ID = "10000000-0000-4000-8000-000000000105"
        const val WORKSPACE_KEYBOARD_PROFILE_ID = "10000000-0000-4000-8000-000000000106"
    }
}
