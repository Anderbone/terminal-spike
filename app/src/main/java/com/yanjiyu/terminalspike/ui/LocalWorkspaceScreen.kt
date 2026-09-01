package com.yanjiyu.terminalspike.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import com.yanjiyu.terminalspike.ui.theme.iconMetrics
import com.yanjiyu.terminalspike.ui.theme.spacing
import com.yanjiyu.terminalspike.ui.theme.statusColors
import kotlinx.coroutines.delay

internal enum class AppDestination(@StringRes val labelResId: Int) {
    WORKSPACE(R.string.navigation_connections),
    CONNECTIONS(R.string.navigation_connections),
    TERMINAL(R.string.navigation_terminal),
    SETTINGS(R.string.navigation_settings),
}

internal enum class AppRoute {
    WORKSPACE,
    CONNECTIONS,
    SETTINGS,
    TERMINAL_DETAIL,
}

internal enum class AppGlyph {
    APPEARANCE,
    WORKSPACE,
    HOSTS,
    KEYCHAIN,
    SNIPPETS,
    SHIELD,
    KEYBOARD,
    LAB,
    TERMINAL,
    TOOLS,
    NOTIFICATIONS,
    BACKUP,
    INFO,
    SIGNAL,
}

private val ExpandedNavigationMinimumWidth = 600.dp
internal const val CompactPrimaryNavigationTestTag = "primary-navigation-bar"
internal const val ExpandedPrimaryNavigationTestTag = "primary-navigation-rail"

@Composable
internal fun LocalWorkspaceScreen(
    workspace: WorkspaceUiState,
    settingsReady: Boolean,
    onReopenSession: (Long) -> Unit,
    onReconnectSession: (Long) -> Unit,
    onDisconnectSession: (Long) -> Unit,
    onDuplicateSession: (Long) -> Unit,
    onConnectPinnedHost: (Long) -> Unit,
    onEditPinnedHost: (Long) -> Unit = {},
    onReconnectRecent: (String) -> Unit,
    onQuickConnect: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    activityClock: () -> Long = System::currentTimeMillis,
    activityRefreshIntervalMillis: Long = WORKSPACE_ACTIVITY_REFRESH_INTERVAL_MILLIS,
) {
    var contentVisible by remember { mutableStateOf(false) }
    val activityNowEpochMillis = rememberWorkspaceActivityNow(
        clock = activityClock,
        refreshIntervalMillis = activityRefreshIntervalMillis,
    )
    LaunchedEffect(Unit) { contentVisible = true }
    val newConnectionDescription = stringResource(R.string.workspace_new_connection_description)

    AdaptivePrimaryNavigation(
        selected = AppDestination.WORKSPACE,
        onWorkspace = {},
        onConnections = onOpenTerminal,
        onSettings = onOpenSettings,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) { contentModifier, expanded ->
        Column(
            modifier = contentModifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            WorkspaceHeader(
                title = stringResource(R.string.app_name),
                settingsReady = settingsReady,
            )

            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 8 }),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraLarge)) {
                    WorkspaceActiveSessions(
                        sessions = workspace.activeSessions,
                        expanded = expanded,
                        onReopen = onReopenSession,
                        onReconnect = onReconnectSession,
                        onDisconnect = onDisconnectSession,
                        onDuplicate = onDuplicateSession,
                        onNewConnection = onQuickConnect,
                        newConnectionEnabled = settingsReady,
                        activityNowEpochMillis = activityNowEpochMillis,
                    )
                    WorkspacePinnedHosts(
                        hosts = workspace.pinnedHosts,
                        onConnect = onConnectPinnedHost,
                        onEdit = onEditPinnedHost,
                    )
                    WorkspaceRecentConnections(
                        sessions = workspace.recentConnections,
                        onReconnect = onReconnectRecent,
                        activityNowEpochMillis = activityNowEpochMillis,
                    )
                    Button(
                        onClick = onQuickConnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WorkspaceNewConnectionTestTag)
                            .semantics { contentDescription = newConnectionDescription },
                        enabled = settingsReady,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            stringResource(R.string.workspace_new_connection),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(MaterialTheme.spacing.large))
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    title: String,
    settingsReady: Boolean,
) {
    val screenDescription = stringResource(R.string.workspace_screen_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WorkspaceAppBarTestTag)
            .semantics { contentDescription = screenDescription }
            .padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Box(
                modifier = Modifier
                    .size(MaterialTheme.spacing.small)
                    .background(
                        if (settingsReady) {
                            MaterialTheme.statusColors.connected
                        } else {
                            MaterialTheme.statusColors.disconnected
                        },
                        CircleShape,
                    ),
            )
            Text(
                text = stringResource(
                    if (settingsReady) {
                        R.string.workspace_status_on_device
                    } else {
                        R.string.workspace_status_loading
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun WorkspaceActiveSessions(
    sessions: List<WorkspaceActiveSessionUi>,
    expanded: Boolean,
    onReopen: (Long) -> Unit,
    onReconnect: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onNewConnection: () -> Unit,
    newConnectionEnabled: Boolean,
    activityNowEpochMillis: Long,
) {
    Column(
        modifier = Modifier.testTag(WorkspaceActiveSessionsTestTag),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        WorkspaceSectionHeading(R.string.workspace_active_sessions, sessions.size)
        if (sessions.isEmpty()) {
            WorkspaceEmptyState(
                titleRes = R.string.workspace_no_active_sessions,
                detailRes = R.string.workspace_no_active_sessions_detail,
                actionLabelRes = R.string.workspace_new_connection,
                actionEnabled = newConnectionEnabled,
                onAction = onNewConnection,
            )
        } else if (expanded) {
            sessions.forEach { session ->
                WorkspaceActiveSessionCard(
                    session = session,
                    onReopen = onReopen,
                    onReconnect = onReconnect,
                    onDisconnect = onDisconnect,
                    onDuplicate = onDuplicate,
                    activityNowEpochMillis = activityNowEpochMillis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                sessions.forEach { session ->
                    WorkspaceActiveSessionCard(
                        session = session,
                        onReopen = onReopen,
                        onReconnect = onReconnect,
                        onDisconnect = onDisconnect,
                        onDuplicate = onDuplicate,
                        activityNowEpochMillis = activityNowEpochMillis,
                        modifier = Modifier.widthIn(min = 264.dp, max = 320.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceActiveSessionCard(
    session: WorkspaceActiveSessionUi,
    onReopen: (Long) -> Unit,
    onReconnect: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    activityNowEpochMillis: Long,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val statusLabel = stringResource(session.status.labelResId)
    val openDescription = stringResource(
        R.string.workspace_open_session,
        session.friendlyName,
        session.protocol.name,
        statusLabel,
    )
    val actionsDescription = stringResource(
        R.string.workspace_session_actions,
        session.friendlyName,
    )
    Surface(
        onClick = { onReopen(session.id) },
        modifier = modifier
            .testTag("workspace-active-session-${session.id}")
            .semantics {
                contentDescription = openDescription
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkspaceProtocolBadge(session.protocol)
                Spacer(Modifier.size(MaterialTheme.spacing.small))
                WorkspaceStatusBadge(session.status)
                Spacer(Modifier.weight(1f))
                if (session.canReconnect || session.canDisconnect || session.canDuplicate) {
                    Box {
                        TextButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.semantics {
                                contentDescription = actionsDescription
                            },
                        ) {
                            ConnectionsGlyphIcon(
                                glyph = ConnectionsGlyph.MORE,
                                modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (session.canReconnect) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_reconnect)) },
                                    onClick = {
                                        menuExpanded = false
                                        onReconnect(session.id)
                                    },
                                )
                            }
                            if (session.canDisconnect) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_disconnect)) },
                                    onClick = {
                                        menuExpanded = false
                                        onDisconnect(session.id)
                                    },
                                )
                            }
                            if (session.canDuplicate) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_duplicate)) },
                                    onClick = {
                                        menuExpanded = false
                                        onDuplicate(session.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = session.friendlyName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session.terminalTitle?.let { title ->
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatWorkspaceLastActivity(
                    lastActivityAtEpochMillis = session.lastActivityAtEpochMillis,
                    nowEpochMillis = activityNowEpochMillis,
                ).resolve(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun WorkspacePinnedHosts(
    hosts: List<WorkspacePinnedHostUi>,
    onConnect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(WorkspacePinnedHostsTestTag),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        WorkspaceSectionHeading(R.string.workspace_saved_hosts, hosts.size)
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(R.string.workspace_saved_hosts_empty),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            hosts.forEachIndexed { index, host ->
                WorkspaceHostRow(host = host, onConnect = onConnect, onEdit = onEdit)
                if (index != hosts.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WorkspaceHostRow(
    host: WorkspacePinnedHostUi,
    onConnect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val connectDescription = stringResource(R.string.workspace_connect_to_host, host.friendlyName)
    val editDescription = stringResource(R.string.workspace_edit_host, host.friendlyName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workspace-pinned-host-${host.profileId}")
            .clickable(
                enabled = host.canConnect,
                role = Role.Button,
                onClickLabel = connectDescription,
            ) { onConnect(host.profileId) }
            .padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MaterialTheme.spacing.extraSmall),
        ) {
            Text(
                text = host.friendlyName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WorkspaceProtocolBadge(host.protocol)
        }
        IconButton(
            onClick = { onEdit(host.profileId) },
            modifier = Modifier.semantics { contentDescription = editDescription },
        ) {
            ConnectionsGlyphIcon(
                glyph = ConnectionsGlyph.MORE,
                modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
            )
        }
        TextButton(
            onClick = { onConnect(host.profileId) },
            enabled = host.canConnect,
            modifier = Modifier.semantics { contentDescription = connectDescription },
        ) {
            Text(
                text = stringResource(
                    if (host.canConnect) R.string.workspace_connect else R.string.workspace_unavailable,
                ),
            )
        }
    }
}

@Composable
private fun WorkspaceRecentConnections(
    sessions: List<WorkspaceRecentSessionUi>,
    onReconnect: (String) -> Unit,
    activityNowEpochMillis: Long,
) {
    Column(
        modifier = Modifier.testTag(WorkspaceRecentConnectionsTestTag),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        WorkspaceSectionHeading(R.string.workspace_recent_connections, sessions.size)
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.workspace_recent_connections_empty),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            sessions.forEachIndexed { index, session ->
                val canReconnect = session.sourceProfileId != null
                val reconnectDescription = stringResource(
                    R.string.workspace_reconnect_to_host,
                    session.friendlyName,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workspace-recent-session-${session.id}")
                        .clickable(
                            enabled = canReconnect,
                            role = Role.Button,
                            onClickLabel = reconnectDescription,
                        ) { onReconnect(session.id) }
                        .padding(
                            horizontal = MaterialTheme.spacing.small,
                            vertical = MaterialTheme.spacing.small,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                    ) {
                        Text(
                            text = session.friendlyName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                            WorkspaceProtocolBadge(session.protocol)
                            WorkspaceStatusBadge(session.status)
                        }
                        session.terminalTitle?.let { title ->
                            Text(
                                text = title,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatWorkspaceLastActivity(
                                lastActivityAtEpochMillis = session.lastActivityAtEpochMillis,
                                nowEpochMillis = activityNowEpochMillis,
                            ).resolve(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (canReconnect) {
                        Text(
                            text = stringResource(R.string.workspace_reconnect),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                        )
                    }
                }
                if (index != sessions.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WorkspaceSectionHeading(@StringRes titleRes: Int, count: Int) {
    Row(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun WorkspaceEmptyState(
    @StringRes titleRes: Int,
    @StringRes detailRes: Int,
    @StringRes actionLabelRes: Int,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    val newConnectionDescription = stringResource(
        R.string.workspace_new_connection_empty_description,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(detailRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier
                    .testTag(WorkspaceEmptyNewConnectionTestTag)
                    .semantics {
                        contentDescription = newConnectionDescription
                    },
            ) {
                Text(stringResource(actionLabelRes))
            }
        }
    }
}

@Composable
private fun WorkspaceProtocolBadge(protocol: ConnectionProtocol) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = protocol.name,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WorkspaceStatusBadge(status: WorkspaceSessionStatus) {
    val colors = MaterialTheme.statusColors
    val (container, content) = when (status) {
        WorkspaceSessionStatus.CONNECTED -> colors.connectedContainer to colors.onConnectedContainer
        WorkspaceSessionStatus.CONNECTING -> colors.reconnectingContainer to colors.onReconnectingContainer
        WorkspaceSessionStatus.AWAITING_APPROVAL -> colors.warningContainer to colors.onWarningContainer
        WorkspaceSessionStatus.DISCONNECTED ->
            MaterialTheme.colorScheme.surface to colors.disconnected
        WorkspaceSessionStatus.FAILED -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = stringResource(status.labelResId),
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private val WorkspaceSessionStatus.labelResId: Int
    get() = when (this) {
        WorkspaceSessionStatus.CONNECTING -> R.string.workspace_status_connecting
        WorkspaceSessionStatus.CONNECTED -> R.string.workspace_status_connected
        WorkspaceSessionStatus.AWAITING_APPROVAL -> R.string.workspace_status_needs_approval
        WorkspaceSessionStatus.DISCONNECTED -> R.string.workspace_status_disconnected
        WorkspaceSessionStatus.FAILED -> R.string.workspace_status_failed
    }

internal fun formatWorkspaceLastActivity(
    lastActivityAtEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UiText {
    if (lastActivityAtEpochMillis <= 0) {
        return uiText(R.string.workspace_last_activity_unavailable)
    }
    val elapsed = (nowEpochMillis - lastActivityAtEpochMillis).coerceAtLeast(0)
    return when {
        elapsed < 60_000L -> uiText(R.string.workspace_last_activity_just_now)
        elapsed < 3_600_000L -> {
            val minutes = (elapsed / 60_000L).toInt()
            quantityText(R.plurals.workspace_last_activity_minutes, minutes)
        }
        elapsed < 86_400_000L -> {
            val hours = (elapsed / 3_600_000L).toInt()
            quantityText(R.plurals.workspace_last_activity_hours, hours)
        }
        else -> {
            val days = (elapsed / 86_400_000L).toInt()
            quantityText(R.plurals.workspace_last_activity_days, days)
        }
    }
}

@Composable
internal fun rememberWorkspaceActivityNow(
    clock: () -> Long,
    refreshIntervalMillis: Long = WORKSPACE_ACTIVITY_REFRESH_INTERVAL_MILLIS,
): Long {
    val currentClock by rememberUpdatedState(clock)
    var nowEpochMillis by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(refreshIntervalMillis) {
        val boundedInterval = refreshIntervalMillis.coerceAtLeast(1L)
        while (true) {
            delay(boundedInterval)
            nowEpochMillis = currentClock()
        }
    }
    return nowEpochMillis
}

internal const val WORKSPACE_ACTIVITY_REFRESH_INTERVAL_MILLIS = 60_000L

internal const val WorkspaceAppBarTestTag = "workspace-app-bar"
internal const val WorkspaceActiveSessionsTestTag = "workspace-active-sessions"
internal const val WorkspacePinnedHostsTestTag = "workspace-pinned-hosts"
internal const val WorkspaceRecentConnectionsTestTag = "workspace-recent-connections"
internal const val WorkspaceNewConnectionTestTag = "workspace-new-connection"
internal const val WorkspaceEmptyNewConnectionTestTag = "workspace-empty-new-connection"

@Composable
internal fun AdaptivePrimaryNavigation(
    selected: AppDestination,
    onWorkspace: () -> Unit,
    onConnections: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    safeContentInsets: WindowInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Vertical)
        .union(WindowInsets.displayCutout),
    content: @Composable (contentModifier: Modifier, expanded: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= ExpandedNavigationMinimumWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                WorkspaceNavigationRail(
                    selected = selected,
                    onWorkspace = onWorkspace,
                    onConnections = onConnections,
                    onSettings = onSettings,
                    modifier = Modifier.fillMaxHeight(),
                    windowInsets = safeContentInsets.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Vertical,
                    ),
                )
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            safeContentInsets.only(
                                WindowInsetsSides.Top + WindowInsetsSides.End +
                                    WindowInsetsSides.Bottom,
                            ),
                        ),
                    true,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            safeContentInsets.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        ),
                    false,
                )
                WorkspaceBottomBar(
                    selected = selected,
                    onWorkspace = onWorkspace,
                    onConnections = onConnections,
                    onSettings = onSettings,
                    windowInsets = safeContentInsets.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun WorkspaceBottomBar(
    selected: AppDestination,
    onWorkspace: () -> Unit,
    onConnections: () -> Unit,
    onSettings: () -> Unit,
    windowInsets: WindowInsets,
) {
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CompactPrimaryNavigationTestTag),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = windowInsets,
    ) {
        WorkspaceNavigationItem(
            label = stringResource(R.string.navigation_connections),
            glyph = AppGlyph.HOSTS,
            selected = selected == AppDestination.WORKSPACE ||
                selected == AppDestination.CONNECTIONS,
            description = stringResource(R.string.navigation_open_connections),
            onClick = onWorkspace,
        )
        WorkspaceNavigationItem(
            label = stringResource(R.string.navigation_terminal),
            glyph = AppGlyph.TERMINAL,
            selected = selected == AppDestination.TERMINAL,
            description = stringResource(R.string.navigation_open_terminal),
            onClick = onConnections,
        )
        WorkspaceNavigationItem(
            label = stringResource(R.string.navigation_settings),
            glyph = AppGlyph.TOOLS,
            selected = selected == AppDestination.SETTINGS,
            description = stringResource(R.string.navigation_open_settings),
            onClick = onSettings,
        )
    }
}

@Composable
private fun RowScope.WorkspaceNavigationItem(
    label: String,
    glyph: AppGlyph,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        icon = {
            AppGlyphIcon(
                glyph = glyph,
                modifier = Modifier.size(24.dp),
            )
        },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

@Composable
private fun WorkspaceNavigationRail(
    selected: AppDestination,
    onWorkspace: () -> Unit,
    onConnections: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets,
) {
    NavigationRail(
        modifier = modifier.testTag(ExpandedPrimaryNavigationTestTag),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = windowInsets,
    ) {
        WorkspaceRailNavigationItem(
            label = stringResource(R.string.navigation_connections),
            glyph = AppGlyph.HOSTS,
            selected = selected == AppDestination.WORKSPACE ||
                selected == AppDestination.CONNECTIONS,
            description = stringResource(R.string.navigation_open_connections),
            onClick = onWorkspace,
        )
        WorkspaceRailNavigationItem(
            label = stringResource(R.string.navigation_terminal),
            glyph = AppGlyph.TERMINAL,
            selected = selected == AppDestination.TERMINAL,
            description = stringResource(R.string.navigation_open_terminal),
            onClick = onConnections,
        )
        WorkspaceRailNavigationItem(
            label = stringResource(R.string.navigation_settings),
            glyph = AppGlyph.TOOLS,
            selected = selected == AppDestination.SETTINGS,
            description = stringResource(R.string.navigation_open_settings),
            onClick = onSettings,
        )
    }
}

@Composable
private fun ColumnScope.WorkspaceRailNavigationItem(
    label: String,
    glyph: AppGlyph,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        icon = {
            AppGlyphIcon(
                glyph = glyph,
                modifier = Modifier.size(24.dp),
            )
        },
        label = { Text(label) },
        alwaysShowLabel = true,
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

@Composable
internal fun AppGlyphIcon(
    glyph: AppGlyph,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val resolvedColor = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = minOf(w, h) * 0.095f
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (glyph) {
            AppGlyph.APPEARANCE -> {
                val center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f)
                drawCircle(resolvedColor, w * 0.2f, center, style = stroke)
                listOf(
                    (0.5f to 0.06f) to (0.5f to 0.2f),
                    (0.5f to 0.8f) to (0.5f to 0.94f),
                    (0.06f to 0.5f) to (0.2f to 0.5f),
                    (0.8f to 0.5f) to (0.94f to 0.5f),
                    (0.18f to 0.18f) to (0.28f to 0.28f),
                    (0.72f to 0.72f) to (0.82f to 0.82f),
                    (0.82f to 0.18f) to (0.72f to 0.28f),
                    (0.28f to 0.72f) to (0.18f to 0.82f),
                ).forEach { (start, end) ->
                    val startOffset = androidx.compose.ui.geometry.Offset(w * start.first, h * start.second)
                    val endOffset = androidx.compose.ui.geometry.Offset(w * end.first, h * end.second)
                    drawLine(resolvedColor, startOffset, endOffset, strokeWidth * 0.75f, StrokeCap.Round)
                }
            }
            AppGlyph.WORKSPACE -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.13f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.74f, h * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f),
                    style = stroke,
                )
                drawCircle(resolvedColor, w * 0.09f, androidx.compose.ui.geometry.Offset(w * 0.37f, h * 0.5f), style = stroke)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.42f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.42f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.58f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.58f), strokeWidth, StrokeCap.Round)
            }
            AppGlyph.HOSTS -> {
                listOf(0.18f, 0.56f).forEach { y ->
                    drawRoundRect(
                        resolvedColor,
                        androidx.compose.ui.geometry.Offset(w * 0.08f, h * y),
                        androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.27f),
                        androidx.compose.ui.geometry.CornerRadius(w * 0.07f),
                        style = stroke,
                    )
                    drawCircle(resolvedColor, w * 0.035f, androidx.compose.ui.geometry.Offset(w * 0.23f, h * (y + 0.135f)))
                    drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.39f, h * (y + 0.135f)), androidx.compose.ui.geometry.Offset(w * 0.78f, h * (y + 0.135f)), strokeWidth * 0.7f, StrokeCap.Round)
                }
            }
            AppGlyph.KEYCHAIN -> {
                drawCircle(resolvedColor, w * 0.19f, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.34f), style = stroke)
                val path = Path().apply {
                    moveTo(w * 0.47f, h * 0.47f)
                    lineTo(w * 0.82f, h * 0.82f)
                    moveTo(w * 0.65f, h * 0.65f)
                    lineTo(w * 0.55f, h * 0.75f)
                    moveTo(w * 0.75f, h * 0.75f)
                    lineTo(w * 0.65f, h * 0.85f)
                }
                drawPath(path, resolvedColor, style = stroke)
            }
            AppGlyph.SNIPPETS -> {
                val left = Path().apply {
                    moveTo(w * 0.38f, h * 0.12f)
                    cubicTo(w * 0.20f, h * 0.12f, w * 0.28f, h * 0.40f, w * 0.12f, h * 0.5f)
                    cubicTo(w * 0.28f, h * 0.60f, w * 0.20f, h * 0.88f, w * 0.38f, h * 0.88f)
                }
                val right = Path().apply {
                    moveTo(w * 0.62f, h * 0.12f)
                    cubicTo(w * 0.80f, h * 0.12f, w * 0.72f, h * 0.40f, w * 0.88f, h * 0.5f)
                    cubicTo(w * 0.72f, h * 0.60f, w * 0.80f, h * 0.88f, w * 0.62f, h * 0.88f)
                }
                drawPath(left, resolvedColor, style = stroke)
                drawPath(right, resolvedColor, style = stroke)
            }
            AppGlyph.SHIELD -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.84f, h * 0.23f)
                    lineTo(w * 0.78f, h * 0.65f)
                    quadraticTo(w * 0.7f, h * 0.84f, w * 0.5f, h * 0.94f)
                    quadraticTo(w * 0.3f, h * 0.84f, w * 0.22f, h * 0.65f)
                    lineTo(w * 0.16f, h * 0.23f)
                    close()
                }
                drawPath(path, resolvedColor, style = stroke)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.51f), androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.64f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.38f), strokeWidth, StrokeCap.Round)
            }
            AppGlyph.KEYBOARD -> {
                drawRoundRect(
                    resolvedColor,
                    androidx.compose.ui.geometry.Offset(w * 0.07f, h * 0.2f),
                    androidx.compose.ui.geometry.Size(w * 0.86f, h * 0.62f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                for (row in 0..1) for (column in 0..3) {
                    drawCircle(
                        resolvedColor,
                        strokeWidth * 0.36f,
                        androidx.compose.ui.geometry.Offset(w * (0.23f + column * 0.18f), h * (0.38f + row * 0.18f)),
                    )
                }
            }
            AppGlyph.LAB -> {
                drawArc(resolvedColor, 205f, 130f, false, style = stroke)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.35f), strokeWidth, StrokeCap.Round)
                drawCircle(resolvedColor, strokeWidth * 0.65f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f))
            }
            AppGlyph.TERMINAL -> {
                drawRoundRect(
                    resolvedColor,
                    androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.16f),
                    androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.68f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.09f),
                    style = stroke,
                )
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.4f), androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.6f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.62f), androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.62f), strokeWidth, StrokeCap.Round)
            }
            AppGlyph.TOOLS -> {
                listOf(0.27f, 0.5f, 0.73f).forEachIndexed { index, y ->
                    drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.14f, h * y), androidx.compose.ui.geometry.Offset(w * 0.86f, h * y), strokeWidth, StrokeCap.Round)
                    val x = listOf(0.35f, 0.67f, 0.45f)[index]
                    drawCircle(resolvedColor, w * 0.08f, androidx.compose.ui.geometry.Offset(w * x, h * y), style = stroke)
                }
            }
            AppGlyph.NOTIFICATIONS -> {
                drawArc(
                    color = resolvedColor,
                    startAngle = 198f,
                    sweepAngle = 144f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.16f),
                    size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.62f),
                    style = stroke,
                )
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.72f), androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.72f), strokeWidth, StrokeCap.Round)
                drawArc(
                    color = resolvedColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.68f),
                    size = androidx.compose.ui.geometry.Size(w * 0.2f, h * 0.2f),
                    style = stroke,
                )
            }
            AppGlyph.BACKUP -> {
                drawRoundRect(
                    resolvedColor,
                    androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.18f),
                    androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.68f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.24f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.62f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.48f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.48f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), strokeWidth, StrokeCap.Round)
            }
            AppGlyph.INFO -> {
                drawCircle(resolvedColor, w * 0.4f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(resolvedColor, strokeWidth * 0.55f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.3f))
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.46f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.72f), strokeWidth, StrokeCap.Round)
            }
            AppGlyph.SIGNAL -> {
                listOf(0.22f, 0.42f, 0.62f).forEachIndexed { index, inset ->
                    drawArc(
                        color = resolvedColor,
                        startAngle = 220f,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(w * inset / 2f, h * inset / 2f),
                        size = androidx.compose.ui.geometry.Size(w * (1f - inset), h * (1f - inset)),
                        style = stroke,
                    )
                }
                drawCircle(resolvedColor, strokeWidth * 0.7f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.78f))
            }
        }
    }
}
