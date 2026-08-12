package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.core.data.repository.CatalogSshKeyIdentity
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

internal enum class ConnectionsTab {
    HOSTS,
    KEYS,
    SNIPPETS,
}

internal enum class HostSort {
    NAME,
    FAVOURITES,
    RECENT,
    GROUP,
}

internal data class HostFilters(
    val favouritesOnly: Boolean = false,
    val group: String? = null,
) {
    internal val normalizedGroup: String?
        get() = group?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
}

/**
 * Ephemeral Connections controls, kept separately from catalog publication so a refresh cannot
 * reset an in-progress search or the selected tab. No entity, endpoint, or credential data belongs
 * in this state.
 */
internal data class ConnectionsInteractionState(
    val selectedTab: ConnectionsTab = ConnectionsTab.HOSTS,
    val searchQuery: String = "",
    val hostSort: HostSort = HostSort.NAME,
    val hostFilters: HostFilters = HostFilters(),
) {
    fun reduce(action: ConnectionsInteractionAction): ConnectionsInteractionState = when (action) {
        is ConnectionsInteractionAction.SelectTab -> copy(selectedTab = action.tab)
        is ConnectionsInteractionAction.SetSearchQuery -> copy(
            searchQuery = action.query.boundedSearchQuery(),
        )
        is ConnectionsInteractionAction.SetHostSort -> copy(hostSort = action.sort)
        is ConnectionsInteractionAction.SetFavouritesOnly -> copy(
            hostFilters = hostFilters.copy(favouritesOnly = action.enabled),
        )
        is ConnectionsInteractionAction.SetHostGroup -> copy(
            hostFilters = hostFilters.copy(
                group = action.group?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
        ConnectionsInteractionAction.ClearHostFilters -> copy(hostFilters = HostFilters())
    }

    fun withLoadState(
        loadState: ConnectionsLoadState,
        hostConnectionTest: HostConnectionTestUiState = HostConnectionTestUiState.Idle,
    ): ConnectionsUiState = ConnectionsUiState(
        selectedTab = selectedTab,
        searchQuery = searchQuery,
        hostSort = hostSort,
        hostFilters = hostFilters,
        loadState = loadState,
        hostConnectionTest = hostConnectionTest,
    )
}

internal sealed interface ConnectionsInteractionAction {
    data class SelectTab(val tab: ConnectionsTab) : ConnectionsInteractionAction

    data class SetSearchQuery(val query: String) : ConnectionsInteractionAction

    data class SetHostSort(val sort: HostSort) : ConnectionsInteractionAction

    data class SetFavouritesOnly(val enabled: Boolean) : ConnectionsInteractionAction

    data class SetHostGroup(val group: String?) : ConnectionsInteractionAction

    data object ClearHostFilters : ConnectionsInteractionAction
}

internal enum class HostActiveSessionStatus {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/**
 * Display-only host metadata. Credential references and authentication material deliberately do
 * not cross into this row model.
 */
internal data class HostRowUi(
    val id: String,
    val displayName: String,
    val username: String,
    val hostname: String,
    val port: Int,
    val protocol: ConnectionProtocol,
    val isFavourite: Boolean,
    val group: String?,
    val tags: List<String>,
    val activeSessionStatus: HostActiveSessionStatus?,
    val activeSessionCount: Int,
    val lastSessionActivityAtEpochMillis: Long?,
) {
    val secondaryLine: String
        get() {
            val displayHost = if (':' in hostname && !hostname.startsWith('[')) {
                "[$hostname]"
            } else {
                hostname
            }
            return "$username@$displayHost:$port"
        }
}

/** Public identity metadata only; neither public nor private key payloads are retained here. */
internal data class KeyRowUi(
    val id: String,
    val name: String,
    val algorithm: String,
    val fingerprint: String,
    val comment: String?,
    val origin: SshKeyOrigin,
    val isPassphraseProtected: Boolean,
)

internal data class SnippetRowUi(
    val id: String,
    val name: String,
    val group: String?,
    val command: String,
    val tapAction: SnippetTapAction,
    val appendEnter: Boolean,
    val confirmMultilineExecution: Boolean,
    val isFavourite: Boolean,
)

internal sealed interface ConnectionsLoadState {
    data object Loading : ConnectionsLoadState

    data class Ready(
        val hosts: List<HostRowUi>,
        val keys: List<KeyRowUi>,
        val snippets: List<SnippetRowUi>,
        val editorCatalog: ConnectionsEditorCatalog = ConnectionsEditorCatalog(),
    ) : ConnectionsLoadState {
        val hostGroups: List<String> = hosts
            .mapNotNull(HostRowUi::group)
            .sortedWith(ROOT_TEXT_COMPARATOR)
            .distinctBy(::foldForSearch)
    }

    data class Error(val message: UiText) : ConnectionsLoadState {
        /** Test/data-fixture bridge; production-authored copy should use a resource-backed message. */
        constructor(message: String) : this(UiText.Dynamic(message))
    }
}

internal data class ConnectionsUiState(
    val selectedTab: ConnectionsTab = ConnectionsTab.HOSTS,
    val searchQuery: String = "",
    val hostSort: HostSort = HostSort.NAME,
    val hostFilters: HostFilters = HostFilters(),
    val loadState: ConnectionsLoadState = ConnectionsLoadState.Loading,
    val hostConnectionTest: HostConnectionTestUiState = HostConnectionTestUiState.Idle,
) {
    fun presentation(): ConnectionsPresentationUi = when (val load = loadState) {
        ConnectionsLoadState.Loading -> ConnectionsPresentationUi.Loading(selectedTab)
        is ConnectionsLoadState.Error -> ConnectionsPresentationUi.Error(
            selectedTab = selectedTab,
            message = load.message,
        )
        is ConnectionsLoadState.Ready -> ConnectionsPresentationUi.Ready(
            selectedTab = selectedTab,
            rows = when (selectedTab) {
                ConnectionsTab.HOSTS -> ConnectionsTabRowsUi.Hosts(
                    content = presentHosts(load.hosts),
                    availableGroups = load.hostGroups,
                )
                ConnectionsTab.KEYS -> ConnectionsTabRowsUi.Keys(
                    content = presentKeys(load.keys),
                )
                ConnectionsTab.SNIPPETS -> ConnectionsTabRowsUi.Snippets(
                    content = presentSnippets(load.snippets),
                )
            },
        )
    }

    private fun presentHosts(source: List<HostRowUi>): ConnectionsListUi<HostRowUi> {
        val terms = searchTerms(searchQuery)
        val group = hostFilters.normalizedGroup
        val visible = source.asSequence()
            .filter { row -> !hostFilters.favouritesOnly || row.isFavourite }
            .filter { row -> group == null || row.group?.lowercase(Locale.ROOT) == group }
            .filter { row ->
                matchesSearch(
                    terms = terms,
                    values = buildList {
                        add(row.displayName)
                        add(row.username)
                        add(row.hostname)
                        row.group?.let(::add)
                        addAll(row.tags)
                    },
                )
            }
            .sortedWith(hostSort.comparator)
            .toList()
        return connectionsList(source, visible)
    }

    private fun presentKeys(source: List<KeyRowUi>): ConnectionsListUi<KeyRowUi> {
        val terms = searchTerms(searchQuery)
        val visible = source
            .filter { row ->
                matchesSearch(
                    terms = terms,
                    values = listOfNotNull(
                        row.name,
                        row.algorithm,
                        row.fingerprint,
                        row.comment,
                    ),
                )
            }
            .sortedWith(KEY_ROW_COMPARATOR)
        return connectionsList(source, visible)
    }

    private fun presentSnippets(source: List<SnippetRowUi>): ConnectionsListUi<SnippetRowUi> {
        val terms = searchTerms(searchQuery)
        val visible = source
            .filter { row ->
                matchesSearch(
                    terms = terms,
                    values = listOfNotNull(row.name, row.group, row.command),
                )
            }
            .sortedWith(SNIPPET_ROW_COMPARATOR)
        return connectionsList(source, visible)
    }
}

internal sealed interface ConnectionsPresentationUi {
    val selectedTab: ConnectionsTab

    data class Loading(
        override val selectedTab: ConnectionsTab,
    ) : ConnectionsPresentationUi

    data class Ready(
        override val selectedTab: ConnectionsTab,
        val rows: ConnectionsTabRowsUi,
    ) : ConnectionsPresentationUi

    data class Error(
        override val selectedTab: ConnectionsTab,
        val message: UiText,
    ) : ConnectionsPresentationUi
}

internal sealed interface ConnectionsTabRowsUi {
    data class Hosts(
        val content: ConnectionsListUi<HostRowUi>,
        val availableGroups: List<String>,
    ) : ConnectionsTabRowsUi

    data class Keys(
        val content: ConnectionsListUi<KeyRowUi>,
    ) : ConnectionsTabRowsUi

    data class Snippets(
        val content: ConnectionsListUi<SnippetRowUi>,
    ) : ConnectionsTabRowsUi
}

internal enum class ConnectionsEmptyState {
    EMPTY_CATALOG,
    NO_RESULTS,
}

internal data class ConnectionsListUi<T>(
    val rows: List<T>,
    val emptyState: ConnectionsEmptyState?,
) {
    init {
        require(rows.isEmpty() == (emptyState != null)) {
            "Only an empty connections list may carry an empty state."
        }
    }
}

internal fun buildConnectionsReadyState(
    hosts: List<HostProfile>,
    keys: List<SshKeyIdentity>,
    snippets: List<Snippet>,
    sessions: List<RecentSession>,
): ConnectionsLoadState.Ready {
    val sessionsByHost = sessions
        .mapNotNull { session -> session.hostProfileId?.let { hostId -> hostId to session } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    return ConnectionsLoadState.Ready(
        hosts = hosts.map { host -> host.toRowUi(sessionsByHost[host.id].orEmpty()) },
        keys = keys.map(SshKeyIdentity::toRowUi),
        snippets = snippets.map(Snippet::toRowUi),
    )
}

/** Builds display-only rows directly from the authoritative non-secret catalog snapshot. */
internal fun buildConnectionsReadyState(
    catalog: TerminalDataCatalog,
    sessions: List<RecentSession>,
    recentHostActivity: List<RecentHostActivity> = emptyList(),
): ConnectionsLoadState.Ready {
    val sessionsByHost = sessions
        .mapNotNull { session -> session.hostProfileId?.let { hostId -> hostId to session } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    val recentActivityByHost = recentHostActivity
        .groupingBy(RecentHostActivity::hostProfileId)
        .fold(0L) { newest, activity ->
            maxOf(newest, activity.lastActivityAtEpochMillis)
        }
    return ConnectionsLoadState.Ready(
        hosts = catalog.hosts.map { host ->
            host.profile.toRowUi(
                sessions = sessionsByHost[host.profile.id].orEmpty(),
                persistedActivityAtEpochMillis = recentActivityByHost[host.profile.id],
                liveActivityOverridesHistory = true,
            )
        },
        keys = catalog.identities.map(CatalogSshKeyIdentity::toRowUi),
        snippets = catalog.snippets.map { catalogSnippet -> catalogSnippet.snippet.toRowUi() },
        editorCatalog = catalog.toConnectionsEditorCatalog(),
    )
}

private fun HostProfile.toRowUi(
    sessions: List<RecentSession>,
    persistedActivityAtEpochMillis: Long? = null,
    liveActivityOverridesHistory: Boolean = false,
): HostRowUi {
    val activeSessions = sessions.filter { session -> session.state.isActive }
    val currentSession = activeSessions.maxWithOrNull(
        compareBy<RecentSession>(RecentSession::lastActivityAtEpochMillis)
            .thenBy(RecentSession::id),
    )
    return HostRowUi(
        id = id,
        displayName = displayName,
        username = username,
        hostname = hostname,
        port = port,
        protocol = protocol,
        isFavourite = isFavorite,
        group = group,
        tags = listOfNotNull(tag),
        activeSessionStatus = currentSession?.state?.toActiveSessionStatus(),
        activeSessionCount = activeSessions.size,
        // An open runtime is the authority for its current activity. Otherwise use the complete,
        // bounded per-host Room aggregation rather than the shorter Workspace history window.
        lastSessionActivityAtEpochMillis = currentSession
            ?.takeIf { liveActivityOverridesHistory }
            ?.lastActivityAtEpochMillis
            ?: listOfNotNull(
                persistedActivityAtEpochMillis,
                sessions.maxOfOrNull(RecentSession::lastActivityAtEpochMillis),
            ).maxOrNull(),
    )
}

private fun SessionState.toActiveSessionStatus(): HostActiveSessionStatus = when (this) {
    SessionState.CONNECTING -> HostActiveSessionStatus.CONNECTING
    SessionState.CONNECTED -> HostActiveSessionStatus.CONNECTED
    SessionState.RECONNECTING -> HostActiveSessionStatus.RECONNECTING
    SessionState.DISCONNECTED, SessionState.FAILED ->
        error("Ended sessions do not have an active-session status.")
}

private fun SshKeyIdentity.toRowUi(): KeyRowUi = KeyRowUi(
    id = id,
    name = name,
    algorithm = algorithm,
    fingerprint = publicKeyFingerprint,
    comment = comment,
    origin = origin,
    isPassphraseProtected = isPassphraseProtected,
)

private fun CatalogSshKeyIdentity.toRowUi(): KeyRowUi = KeyRowUi(
    id = id,
    name = name,
    algorithm = algorithm,
    fingerprint = publicKeyFingerprint,
    comment = comment,
    origin = origin,
    isPassphraseProtected = isPassphraseProtected,
)

private fun Snippet.toRowUi(): SnippetRowUi = SnippetRowUi(
    id = id,
    name = name,
    group = group,
    command = command,
    tapAction = tapAction,
    appendEnter = appendEnter,
    confirmMultilineExecution = confirmMultilineExecution,
    isFavourite = isFavorite,
)

private fun <T> connectionsList(source: List<T>, visible: List<T>): ConnectionsListUi<T> =
    ConnectionsListUi(
        rows = visible,
        emptyState = when {
            visible.isNotEmpty() -> null
            source.isEmpty() -> ConnectionsEmptyState.EMPTY_CATALOG
            else -> ConnectionsEmptyState.NO_RESULTS
        },
    )

private val HostSort.comparator: Comparator<HostRowUi>
    get() = when (this) {
        HostSort.NAME -> HOST_NAME_COMPARATOR
        HostSort.FAVOURITES -> compareByDescending<HostRowUi>(HostRowUi::isFavourite)
            .then(HOST_NAME_COMPARATOR)
        HostSort.RECENT ->
            compareByDescending<HostRowUi> {
                it.lastSessionActivityAtEpochMillis ?: Long.MIN_VALUE
            }.then(HOST_NAME_COMPARATOR)
        HostSort.GROUP -> compareBy<HostRowUi> { it.group == null }
            .thenBy { row -> row.group?.let(::foldForSearch).orEmpty() }
            .then(HOST_NAME_COMPARATOR)
    }

private val HOST_NAME_COMPARATOR = compareBy<HostRowUi>(
    { foldForSearch(it.displayName) },
    HostRowUi::displayName,
    { foldForSearch(it.username) },
    { foldForSearch(it.hostname) },
    HostRowUi::port,
    HostRowUi::id,
)

private val KEY_ROW_COMPARATOR = compareBy<KeyRowUi>(
    { foldForSearch(it.name) },
    KeyRowUi::name,
    { foldForSearch(it.algorithm) },
    KeyRowUi::fingerprint,
    KeyRowUi::id,
)

private val SNIPPET_ROW_COMPARATOR = compareBy<SnippetRowUi>(
    { foldForSearch(it.name) },
    SnippetRowUi::name,
    { it.group?.let(::foldForSearch).orEmpty() },
    SnippetRowUi::id,
)

private val ROOT_TEXT_COMPARATOR = compareBy<String>(::foldForSearch).thenBy { it }
private val SEARCH_WHITESPACE = Regex("\\s+")
private const val MAX_CONNECTIONS_SEARCH_QUERY_LENGTH = 256

private fun String.boundedSearchQuery(): String {
    if (length <= MAX_CONNECTIONS_SEARCH_QUERY_LENGTH) return this
    val boundary = MAX_CONNECTIONS_SEARCH_QUERY_LENGTH
    val safeBoundary = if (this[boundary - 1].isHighSurrogate()) boundary - 1 else boundary
    return substring(0, safeBoundary)
}

private fun searchTerms(query: String): List<String> = query
    .trim()
    .split(SEARCH_WHITESPACE)
    .filter(String::isNotEmpty)
    .map(::foldForSearch)

private fun matchesSearch(terms: List<String>, values: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val foldedValues = values.map(::foldForSearch)
    return terms.all { term -> foldedValues.any { value -> term in value } }
}

private fun foldForSearch(value: String): String = value.lowercase(Locale.ROOT)
