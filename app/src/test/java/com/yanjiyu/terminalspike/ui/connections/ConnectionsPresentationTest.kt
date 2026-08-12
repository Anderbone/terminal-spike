package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.core.data.repository.CatalogHostProfile
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCatalog
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.RecentHostActivity
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.ui.UiText
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsPresentationTest {
    @Test
    fun interactionReducerRetainsControlsAcrossCatalogPublications() {
        val edited = ConnectionsInteractionState()
            .reduce(ConnectionsInteractionAction.SelectTab(ConnectionsTab.SNIPPETS))
            .reduce(ConnectionsInteractionAction.SetSearchQuery("release"))
            .reduce(ConnectionsInteractionAction.SetHostSort(HostSort.RECENT))
            .reduce(ConnectionsInteractionAction.SetFavouritesOnly(true))
            .reduce(ConnectionsInteractionAction.SetHostGroup(" Production "))

        val loading = edited.withLoadState(ConnectionsLoadState.Loading)
        val ready = edited.withLoadState(
            buildConnectionsReadyState(
                hosts = emptyList(),
                keys = emptyList(),
                snippets = emptyList(),
                sessions = emptyList(),
            ),
        )

        listOf(loading, ready).forEach { state ->
            assertEquals(ConnectionsTab.SNIPPETS, state.selectedTab)
            assertEquals("release", state.searchQuery)
            assertEquals(HostSort.RECENT, state.hostSort)
            assertEquals(HostFilters(favouritesOnly = true, group = "Production"), state.hostFilters)
        }

        val cleared = edited.reduce(ConnectionsInteractionAction.ClearHostFilters)
        assertEquals(ConnectionsTab.SNIPPETS, cleared.selectedTab)
        assertEquals("release", cleared.searchQuery)
        assertEquals(HostSort.RECENT, cleared.hostSort)
        assertEquals(HostFilters(), cleared.hostFilters)
    }

    @Test
    fun interactionReducerBoundsSearchAndTreatsBlankGroupAsNoFilter() {
        val reduced = ConnectionsInteractionState()
            .reduce(ConnectionsInteractionAction.SetSearchQuery("x".repeat(400)))
            .reduce(ConnectionsInteractionAction.SetHostGroup("  "))

        assertEquals(256, reduced.searchQuery.length)
        assertNull(reduced.hostFilters.group)

        val unicodeBoundary = ConnectionsInteractionState().reduce(
            ConnectionsInteractionAction.SetSearchQuery("x".repeat(255) + "🚀tail"),
        )
        assertEquals(255, unicodeBoundary.searchQuery.length)
        assertFalse(unicodeBoundary.searchQuery.last().isHighSurrogate())
    }

    @Test
    fun loadingAndErrorStatesPreserveTheSelectedTab() {
        val loading = ConnectionsUiState(selectedTab = ConnectionsTab.KEYS).presentation()
            as ConnectionsPresentationUi.Loading
        val error = ConnectionsUiState(
            selectedTab = ConnectionsTab.SNIPPETS,
            loadState = ConnectionsLoadState.Error("Local catalog unavailable."),
        ).presentation() as ConnectionsPresentationUi.Error

        assertEquals(ConnectionsTab.KEYS, loading.selectedTab)
        assertEquals(ConnectionsTab.SNIPPETS, error.selectedTab)
        assertEquals(UiText.Dynamic("Local catalog unavailable."), error.message)
    }

    @Test
    fun readyRowsRetainOnlyPresentationMetadata() {
        val credentialReference = uuid(901)
        val privateKeyReference = uuid(902)
        val publicKeyPayload = "ssh-ed25519 AAAAC3NzaPresentationMustNotRetain"
        val startupCommand = "export SHOULD_NOT_REACH_ROW=1"
        val host = host(
            number = 1,
            name = "Production",
            credentialId = credentialReference,
            startupCommand = startupCommand,
        )
        val key = key(
            number = 2,
            privateKeyReference = privateKeyReference,
            publicKey = publicKeyPayload,
        )
        val ready = buildConnectionsReadyState(
            hosts = listOf(host),
            keys = listOf(key),
            snippets = listOf(snippet(3, "Deploy")),
            sessions = emptyList(),
        )

        val hostRow = ready.hosts.single()
        val keyRow = ready.keys.single()
        assertEquals("deploy@server-1.example:22", hostRow.secondaryLine)
        assertEquals(key.publicKeyFingerprint, keyRow.fingerprint)
        assertFalse(ready.toString().contains(credentialReference))
        assertFalse(ready.toString().contains(privateKeyReference))
        assertFalse(ready.toString().contains(publicKeyPayload))
        assertFalse(ready.toString().contains(startupCommand))
        assertTrue(
            HostRowUi::class.java.declaredFields.none { field ->
                field.name.contains("credential", ignoreCase = true) ||
                    field.name.contains("authentication", ignoreCase = true) ||
                    field.name.contains("password", ignoreCase = true) ||
                    field.name.contains("secret", ignoreCase = true)
            },
        )
        assertTrue(
            KeyRowUi::class.java.declaredFields.none { field ->
                field.name.contains("privateKey", ignoreCase = true) ||
                    field.name.contains("publicKey", ignoreCase = true) ||
                    field.name.contains("secret", ignoreCase = true)
            },
        )
    }

    @Test
    fun hostSearchCoversEveryRequiredFieldAndUsesLocaleRoot() {
        val matchingHost = host(
            number = 1,
            name = "INDIGO Fleet",
            username = "deployer",
            hostname = "edge.internal",
            group = "Production",
            tag = "Blue",
        )
        val state = ConnectionsUiState(
            loadState = buildConnectionsReadyState(
                hosts = listOf(matchingHost, host(number = 2, name = "Archive")),
                keys = emptyList(),
                snippets = emptyList(),
                sessions = emptyList(),
            ),
        )
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            listOf(
                "indigo",
                "deployer",
                "edge.internal",
                "production",
                "blue",
                "deployer blue",
            ).forEach { query ->
                assertEquals(listOf(matchingHost.id), state.copy(searchQuery = query).hostIds())
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun keyAndSnippetSearchCoverMetadataAndCommandText() {
        val deployKey = key(
            number = 1,
            name = "Release signer",
            algorithm = "ssh-ed25519",
            fingerprint = "SHA256:release-key",
            comment = "Production deploy",
        )
        val otherKey = key(number = 2, name = "Archive key", comment = "Cold storage")
        val deploySnippet = snippet(
            number = 3,
            name = "Release service",
            group = "Deploy",
            command = "systemctl restart api",
        )
        val otherSnippet = snippet(number = 4, name = "Disk usage", command = "df -h")
        val state = ConnectionsUiState(
            loadState = buildConnectionsReadyState(
                hosts = emptyList(),
                keys = listOf(otherKey, deployKey),
                snippets = listOf(otherSnippet, deploySnippet),
                sessions = emptyList(),
            ),
        )

        listOf("release signer", "ED25519", "release-key", "production deploy").forEach { query ->
            assertEquals(
                listOf(deployKey.id),
                state.copy(selectedTab = ConnectionsTab.KEYS, searchQuery = query).keyIds(),
            )
        }
        listOf("release service", "deploy", "restart api").forEach { query ->
            assertEquals(
                listOf(deploySnippet.id),
                state.copy(selectedTab = ConnectionsTab.SNIPPETS, searchQuery = query).snippetIds(),
            )
        }
    }

    @Test
    fun hostSortModesHaveStableDocumentedTies() {
        val betaFavourite = host(number = 1, name = "Beta", favourite = true, group = "Zulu")
        val alphaUngrouped = host(number = 2, name = "Alpha", hostname = "shared.example")
        val alphaGrouped = host(
            number = 3,
            name = "Alpha",
            hostname = "shared.example",
            group = "Alpha",
        )
        val state = ConnectionsUiState(
            loadState = buildConnectionsReadyState(
                hosts = listOf(betaFavourite, alphaGrouped, alphaUngrouped),
                keys = emptyList(),
                snippets = emptyList(),
                sessions = listOf(
                    session(11, betaFavourite.id, SessionState.DISCONNECTED, activityAt = 300),
                    session(12, alphaUngrouped.id, SessionState.DISCONNECTED, activityAt = 100),
                    session(13, alphaGrouped.id, SessionState.DISCONNECTED, activityAt = 300),
                ),
            ),
        )

        assertEquals(
            listOf(alphaUngrouped.id, alphaGrouped.id, betaFavourite.id),
            state.copy(hostSort = HostSort.NAME).hostIds(),
        )
        assertEquals(
            listOf(betaFavourite.id, alphaUngrouped.id, alphaGrouped.id),
            state.copy(hostSort = HostSort.FAVOURITES).hostIds(),
        )
        assertEquals(
            listOf(alphaGrouped.id, betaFavourite.id, alphaUngrouped.id),
            state.copy(hostSort = HostSort.RECENT).hostIds(),
        )
        assertEquals(
            listOf(alphaGrouped.id, betaFavourite.id, alphaUngrouped.id),
            state.copy(hostSort = HostSort.GROUP).hostIds(),
        )
    }

    @Test
    fun hostGroupAndFavouriteFiltersIntersectAndGroupsAreStable() {
        val productionFavourite = host(
            number = 1,
            name = "Primary",
            favourite = true,
            group = "Production",
        )
        val productionOther = host(number = 2, name = "Secondary", group = "production")
        val archiveFavourite = host(
            number = 3,
            name = "Archive",
            favourite = true,
            group = "Archive",
        )
        val state = ConnectionsUiState(
            hostFilters = HostFilters(favouritesOnly = true, group = " PRODUCTION "),
            loadState = buildConnectionsReadyState(
                hosts = listOf(productionOther, archiveFavourite, productionFavourite),
                keys = emptyList(),
                snippets = emptyList(),
                sessions = emptyList(),
            ),
        )

        assertEquals(listOf(productionFavourite.id), state.hostIds())
        val rows = (state.presentation() as ConnectionsPresentationUi.Ready).rows
            as ConnectionsTabRowsUi.Hosts
        assertEquals(listOf("Archive", "Production"), rows.availableGroups)
    }

    @Test
    fun mostRecentlyActiveSessionDeterminesStatusAndAllHistoryDeterminesRecency() {
        val activeHost = host(number = 1, name = "Active")
        val endedHost = host(number = 2, name = "Ended")
        val ready = buildConnectionsReadyState(
            hosts = listOf(activeHost, endedHost),
            keys = emptyList(),
            snippets = emptyList(),
            sessions = listOf(
                session(1, activeHost.id, SessionState.CONNECTED, activityAt = 100),
                session(2, activeHost.id, SessionState.RECONNECTING, activityAt = 200),
                session(3, activeHost.id, SessionState.DISCONNECTED, activityAt = 400),
                session(4, endedHost.id, SessionState.FAILED, activityAt = 300),
            ),
        )

        val active = ready.hosts.single { it.id == activeHost.id }
        val ended = ready.hosts.single { it.id == endedHost.id }
        assertEquals(HostActiveSessionStatus.RECONNECTING, active.activeSessionStatus)
        assertEquals(2, active.activeSessionCount)
        assertEquals(400L, active.lastSessionActivityAtEpochMillis)
        assertNull(ended.activeSessionStatus)
        assertEquals(0, ended.activeSessionCount)
        assertEquals(300L, ended.lastSessionActivityAtEpochMillis)
    }

    @Test
    fun authoritativeHostActivityRanksCompleteHistoryAndLiveRuntimeOverridesIt() {
        val historicalHost = host(number = 1, name = "Historical")
        val liveHost = host(number = 2, name = "Live")
        val neverUsedHost = host(number = 3, name = "Never used")
        val liveSession = session(
            number = 9,
            hostId = liveHost.id,
            state = SessionState.CONNECTED,
            activityAt = 400,
        )
        val ready = buildConnectionsReadyState(
            catalog = catalog(historicalHost, liveHost, neverUsedHost),
            sessions = listOf(liveSession),
            recentHostActivity = listOf(
                RecentHostActivity(historicalHost.id, lastActivityAtEpochMillis = 900),
                // A stale/skewed aggregate cannot override a process-owned runtime.
                RecentHostActivity(liveHost.id, lastActivityAtEpochMillis = 1_000),
            ),
        )
        val state = ConnectionsUiState(
            hostSort = HostSort.RECENT,
            loadState = ready,
        )

        assertEquals(
            listOf(historicalHost.id, liveHost.id, neverUsedHost.id),
            state.hostIds(),
        )
        val liveRow = ready.hosts.single { it.id == liveHost.id }
        assertEquals(HostActiveSessionStatus.CONNECTED, liveRow.activeSessionStatus)
        assertEquals(400L, liveRow.lastSessionActivityAtEpochMillis)
    }

    @Test
    fun emptyCatalogAndNoResultsRemainDistinctForEveryTab() {
        val emptyState = ConnectionsUiState(
            loadState = buildConnectionsReadyState(
                hosts = emptyList(),
                keys = emptyList(),
                snippets = emptyList(),
                sessions = emptyList(),
            ),
        )
        ConnectionsTab.entries.forEach { tab ->
            assertEquals(
                ConnectionsEmptyState.EMPTY_CATALOG,
                emptyState.copy(selectedTab = tab).emptyState(),
            )
        }

        val populated = ConnectionsUiState(
            searchQuery = "nothing matches this",
            loadState = buildConnectionsReadyState(
                hosts = listOf(host(1, "Host")),
                keys = listOf(key(2, "Key")),
                snippets = listOf(snippet(3, "Snippet")),
                sessions = emptyList(),
            ),
        )
        ConnectionsTab.entries.forEach { tab ->
            assertEquals(
                ConnectionsEmptyState.NO_RESULTS,
                populated.copy(selectedTab = tab).emptyState(),
            )
        }
    }

    @Test
    fun keyAndSnippetNameTiesEndInStableIds() {
        val firstKey = key(1, "Shared")
        val secondKey = key(2, "Shared")
        val firstSnippet = snippet(3, "Shared")
        val secondSnippet = snippet(4, "Shared")
        val state = ConnectionsUiState(
            loadState = buildConnectionsReadyState(
                hosts = emptyList(),
                keys = listOf(secondKey, firstKey),
                snippets = listOf(secondSnippet, firstSnippet),
                sessions = emptyList(),
            ),
        )

        assertEquals(
            listOf(firstKey.id, secondKey.id),
            state.copy(selectedTab = ConnectionsTab.KEYS).keyIds(),
        )
        assertEquals(
            listOf(firstSnippet.id, secondSnippet.id),
            state.copy(selectedTab = ConnectionsTab.SNIPPETS).snippetIds(),
        )
    }

    private fun ConnectionsUiState.hostIds(): List<String> {
        val presentation = presentation() as ConnectionsPresentationUi.Ready
        return (presentation.rows as ConnectionsTabRowsUi.Hosts).content.rows.map(HostRowUi::id)
    }

    private fun ConnectionsUiState.keyIds(): List<String> {
        val presentation = presentation() as ConnectionsPresentationUi.Ready
        return (presentation.rows as ConnectionsTabRowsUi.Keys).content.rows.map(KeyRowUi::id)
    }

    private fun ConnectionsUiState.snippetIds(): List<String> {
        val presentation = presentation() as ConnectionsPresentationUi.Ready
        return (presentation.rows as ConnectionsTabRowsUi.Snippets).content.rows.map(SnippetRowUi::id)
    }

    private fun ConnectionsUiState.emptyState(): ConnectionsEmptyState? {
        val presentation = presentation() as ConnectionsPresentationUi.Ready
        return when (val rows = presentation.rows) {
            is ConnectionsTabRowsUi.Hosts -> rows.content.emptyState
            is ConnectionsTabRowsUi.Keys -> rows.content.emptyState
            is ConnectionsTabRowsUi.Snippets -> rows.content.emptyState
        }
    }

    private fun catalog(vararg hosts: HostProfile) = TerminalDataCatalog(
        hosts = hosts.mapIndexed { index, host ->
            CatalogHostProfile(presentationId = index.toLong() + 1, profile = host)
        },
        credentials = emptyList(),
        identities = emptyList(),
        snippets = emptyList(),
        terminalProfiles = emptyList(),
        keyboardProfiles = emptyList(),
        defaultTerminalProfileId = uuid(990),
        defaultKeyboardProfileId = uuid(991),
        unavailableSecretCount = 0,
        migrationWarningCodes = emptyList(),
    )

    private fun host(
        number: Int,
        name: String,
        username: String = "deploy",
        hostname: String = "server-$number.example",
        favourite: Boolean = false,
        group: String? = null,
        tag: String? = null,
        credentialId: String? = uuid(number + 100),
        startupCommand: String? = null,
    ) = HostProfile(
        id = uuid(number),
        displayName = name,
        hostname = hostname,
        port = 22,
        username = username,
        protocol = ConnectionProtocol.SSH,
        credentialId = credentialId,
        isFavorite = favourite,
        group = group,
        tag = tag,
        startupCommand = startupCommand,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun key(
        number: Int,
        name: String = "Key $number",
        algorithm: String = "ssh-rsa",
        fingerprint: String = "SHA256:key-$number",
        comment: String? = null,
        privateKeyReference: String = uuid(number + 200),
        publicKey: String? = "ssh-rsa AAAAkey$number",
    ) = SshKeyIdentity(
        id = uuid(number + 300),
        name = name,
        algorithm = algorithm,
        publicKeyFingerprint = fingerprint,
        publicKey = publicKey,
        privateKeySecretReferenceId = privateKeyReference,
        origin = SshKeyOrigin.IMPORTED,
        isPassphraseProtected = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        comment = comment,
    )

    private fun snippet(
        number: Int,
        name: String,
        group: String? = null,
        command: String = "echo $number",
    ) = Snippet(
        id = uuid(number + 500),
        name = name,
        group = group,
        command = command,
        tapAction = SnippetTapAction.INSERT,
        appendEnter = false,
        confirmMultilineExecution = true,
        isFavorite = false,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun session(
        number: Int,
        hostId: String,
        state: SessionState,
        activityAt: Long,
    ) = RecentSession(
        id = uuid(number + 700),
        hostProfileId = hostId,
        hostDisplayName = "Session $number",
        protocol = ConnectionProtocol.SSH,
        state = state,
        startedAtEpochMillis = 1,
        lastActivityAtEpochMillis = activityAt,
        endedAtEpochMillis = activityAt.takeUnless { state.isActive },
    )

    private fun uuid(number: Int): String =
        "10000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
}
