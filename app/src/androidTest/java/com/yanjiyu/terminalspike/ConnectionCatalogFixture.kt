package com.yanjiyu.terminalspike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.ui.connections.ConnectionsCallbacks
import com.yanjiyu.terminalspike.ui.connections.ConnectionsLoadState
import com.yanjiyu.terminalspike.ui.connections.ConnectionsScreen
import com.yanjiyu.terminalspike.ui.connections.ConnectionsUiState
import com.yanjiyu.terminalspike.ui.connections.HostRowUi

internal const val CatalogFixtureHostId = "10000000-0000-4000-8000-000000000001"

/** Mount the actual catalog; order preferences are private to the enclosing test rule. */
@Composable
internal fun ConnectionCatalogFixture(
    order: IsolatedPreferencesRule,
    onDeleteHost: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val isolated = remember(context) { order.wrap(context) }
    CompositionLocalProvider(LocalContext provides isolated) {
        ConnectionsScreen(
            state = ConnectionsUiState(
                loadState = ConnectionsLoadState.Ready(
                    hosts = listOf(
                        HostRowUi(
                            id = CatalogFixtureHostId, displayName = "Production", username = "deploy",
                            hostname = "server.example", port = 22, protocol = ConnectionProtocol.SSH,
                            isFavourite = false, group = null, tags = emptyList(), activeSessionStatus = null,
                            activeSessionCount = 0, lastSessionActivityAtEpochMillis = null,
                        ),
                    ),
                    keys = emptyList(), snippets = emptyList(),
                ),
            ),
            callbacks = ConnectionsCallbacks(
                onTabSelected = {}, onSearchQueryChanged = {}, onHostSortSelected = {},
                onFavouritesOnlyChanged = {}, onHostGroupSelected = {}, onClearHostFilters = {}, onRetry = {},
                onDeleteHost = onDeleteHost,
            ),
            onOpenWorkspace = {}, onOpenTerminal = {}, onOpenSettings = {},
        )
    }
}
