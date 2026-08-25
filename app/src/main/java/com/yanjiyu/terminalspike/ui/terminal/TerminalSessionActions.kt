package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.ui.SessionTabUi
import com.yanjiyu.terminalspike.ui.SshConnectionSeed
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import com.yanjiyu.terminalspike.ui.theme.iconMetrics

internal const val TerminalSessionActionsTestTag = "terminal-session-actions"
internal const val TerminalSessionDetailsTestTag = "terminal-session-details"

private enum class DestructiveSessionAction {
    DISCONNECT,
    CLEAR_LOCAL_SCROLLBACK,
}

internal data class TerminalSessionActionAvailability(
    val reconnect: Boolean,
    val duplicate: Boolean,
    val disconnect: Boolean,
)

internal fun terminalSessionActionAvailability(
    session: SessionTabUi,
    canAddSession: Boolean,
): TerminalSessionActionAvailability {
    val terminal = session.connectionState is ConnectionState.Disconnected ||
        session.connectionState is ConnectionState.Failed
    return TerminalSessionActionAvailability(
        reconnect = !session.isLocalTerminal && terminal,
        duplicate = !session.isLocalTerminal && canAddSession,
        disconnect = !session.isLocalTerminal && !terminal,
    )
}

/**
 * Contextual actions for one captured session ID. The caller passes the current row for that exact
 * ID; if it disappears this component dismisses and no action can retarget to another tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalSessionActions(
    session: SessionTabUi?,
    endpoint: SshConnectionSeed?,
    canAddSession: Boolean,
    onDismiss: () -> Unit,
    onReconnect: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onFind: (Long) -> Unit,
    onClearLocalScrollback: (Long) -> Unit,
    onExportTranscript: (Long) -> Unit,
    onEnterFocusMode: (Long) -> Unit,
    onOpenSnippets: (Long) -> Unit,
    onOpenTerminalSettings: (Long) -> Unit,
    onOpenKeyboardSettings: (Long) -> Unit,
) {
    LaunchedEffect(session?.id) {
        if (session == null) onDismiss()
    }
    val target = session ?: return
    val availability = terminalSessionActionAvailability(target, canAddSession)
    var destructive by remember(target.id) { mutableStateOf<DestructiveSessionAction?>(null) }
    var showDetails by remember(target.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TerminalSessionActionsTestTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = target.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                maxLines = 1,
            )
            if (availability.reconnect) {
                SessionActionRow(R.string.session_action_reconnect) {
                    onDismiss()
                    onReconnect(target.id)
                }
            }
            if (availability.duplicate) {
                SessionActionRow(R.string.session_action_duplicate) {
                    onDismiss()
                    onDuplicate(target.id)
                }
            }
            if (availability.disconnect) {
                SessionActionRow(R.string.session_action_disconnect) {
                    destructive = DestructiveSessionAction.DISCONNECT
                }
            }
            SessionActionRow(R.string.session_action_find) {
                onDismiss()
                onFind(target.id)
            }
            SessionActionRow(R.string.session_action_clear_scrollback) {
                destructive = DestructiveSessionAction.CLEAR_LOCAL_SCROLLBACK
            }
            SessionActionRow(R.string.session_action_export_transcript) {
                onDismiss()
                onExportTranscript(target.id)
            }
            SessionActionRow(R.string.session_action_focus_fullscreen) {
                onDismiss()
                onEnterFocusMode(target.id)
            }
            HorizontalDivider()
            SessionActionRow(R.string.session_action_snippets) {
                onDismiss()
                onOpenSnippets(target.id)
            }
            SessionActionRow(R.string.session_action_terminal_settings) {
                onDismiss()
                onOpenTerminalSettings(target.id)
            }
            SessionActionRow(R.string.session_action_keyboard_settings) {
                onDismiss()
                onOpenKeyboardSettings(target.id)
            }
            SessionActionRow(R.string.session_action_connection_details) { showDetails = true }
            HorizontalDivider()
            SessionActionRow(R.string.session_action_close_tab) {
                onDismiss()
                onClose(target.id)
            }
        }
    }

    destructive?.let { action ->
        val title = when (action) {
            DestructiveSessionAction.DISCONNECT ->
                stringResource(R.string.session_disconnect_title, target.title)
            DestructiveSessionAction.CLEAR_LOCAL_SCROLLBACK ->
                stringResource(R.string.session_clear_scrollback_title)
        }
        val detail = when (action) {
            DestructiveSessionAction.DISCONNECT ->
                stringResource(R.string.session_disconnect_detail)
            DestructiveSessionAction.CLEAR_LOCAL_SCROLLBACK ->
                stringResource(R.string.session_clear_scrollback_detail)
        }
        AlertDialog(
            onDismissRequest = { destructive = null },
            title = { Text(title) },
            text = { Text(detail) },
            confirmButton = {
                Button(
                    onClick = {
                        destructive = null
                        onDismiss()
                        when (action) {
                            DestructiveSessionAction.DISCONNECT -> onDisconnect(target.id)
                            DestructiveSessionAction.CLEAR_LOCAL_SCROLLBACK ->
                                onClearLocalScrollback(target.id)
                        }
                    },
                ) {
                    Text(
                        when (action) {
                            DestructiveSessionAction.DISCONNECT ->
                                stringResource(R.string.session_action_disconnect)
                            DestructiveSessionAction.CLEAR_LOCAL_SCROLLBACK ->
                                stringResource(R.string.clear)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { destructive = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDetails) {
        TerminalConnectionDetails(
            session = target,
            endpoint = endpoint,
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
private fun SessionActionRow(@StringRes labelRes: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        trailingContent = {
            ConnectionsGlyphIcon(
                glyph = ConnectionsGlyph.BACK,
                modifier = Modifier
                    .size(MaterialTheme.iconMetrics.compact)
                    .graphicsLayer(rotationZ = 180f),
            )
        },
    )
}

@Composable
private fun TerminalConnectionDetails(
    session: SessionTabUi,
    endpoint: SshConnectionSeed?,
    onDismiss: () -> Unit,
) {
    var revealEndpoint by remember(session.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TerminalSessionDetailsTestTag),
        title = { Text(stringResource(R.string.session_action_connection_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(R.string.session_detail_name, session.title)
                DetailRow(R.string.session_detail_protocol, session.protocol.name)
                DetailRow(
                    R.string.session_detail_state,
                    stringResource(session.connectionState.safeLabelResId()),
                )
                session.terminalProfileId?.let {
                    DetailRow(R.string.session_detail_terminal_profile, it)
                }
                session.keyboardProfileId?.let {
                    DetailRow(R.string.session_detail_keyboard_profile, it)
                }
                if (revealEndpoint && endpoint != null) {
                    SelectionContainer {
                        Text("${endpoint.username}@${endpoint.host}:${endpoint.port}")
                    }
                }
            }
        },
        confirmButton = {
            if (endpoint != null && !revealEndpoint) {
                Button(onClick = { revealEndpoint = true }) {
                    Text(stringResource(R.string.session_show_endpoint))
                }
            } else {
                Button(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
        dismissButton = if (endpoint != null && !revealEndpoint) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        } else {
            null
        },
    )
}

@Composable
private fun DetailRow(@StringRes labelRes: Int, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.session_detail_label, stringResource(labelRes)),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun ConnectionState.safeLabelResId(): Int = when (this) {
    ConnectionState.Disconnected -> R.string.session_state_disconnected
    ConnectionState.Connecting -> R.string.session_state_connecting
    ConnectionState.Connected -> R.string.session_state_connected
    is ConnectionState.Reconnecting -> R.string.session_state_reconnecting
    is ConnectionState.AwaitingApproval -> R.string.session_state_waiting_approval
    is ConnectionState.Failed -> R.string.session_state_failed
}
