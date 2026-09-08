package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.ui.resolve

internal const val SavedConnectionPickerTestTag = "saved-connection-picker"
internal const val SavedConnectionPickerHostTestTagPrefix = "saved-connection-picker-host-"

internal fun HostEditorSeed.requiresConnectionPrompt(
    catalog: ConnectionsEditorCatalog,
    moshAvailable: Boolean,
): Boolean {
    val key = draft.keyIdentityId?.let { keyId ->
        catalog.keys.firstOrNull { it.persistentId == keyId }
    }
    val requiresSecret = when (draft.authenticationMethod) {
        HostAuthenticationMethod.PASSWORD -> !savedSecretAvailable
        HostAuthenticationMethod.KEYBOARD_INTERACTIVE -> false
        HostAuthenticationMethod.PRIVATE_KEY -> key?.passphraseProtected == true &&
            !savedSecretAvailable
    }
    return requiresSecret || (draft.protocol == ConnectionProtocol.MOSH && !moshAvailable)
}

@Composable
internal fun SavedConnectionPickerDialog(
    loadState: ConnectionsLoadState,
    onDismiss: () -> Unit,
    onOpenConnections: () -> Unit,
    onSelectHost: (HostEditorSeed) -> Unit,
    protocol: ConnectionProtocol? = null,
) {
    val catalog = (loadState as? ConnectionsLoadState.Ready)?.editorCatalog
    val hosts = catalog?.hosts.orEmpty().filter { protocol == null || it.draft.protocol == protocol }.sortedWith(
        compareByDescending<HostEditorSeed> { it.draft.isFavourite }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.draft.displayName },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SavedConnectionPickerTestTag),
        title = { Text(stringResource(R.string.terminal_saved_connection_picker_title)) },
        text = {
            when {
                loadState is ConnectionsLoadState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
                loadState is ConnectionsLoadState.Error -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.connections_unavailable_title))
                    Text(
                        loadState.message.resolve(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                hosts.isEmpty() -> Text(
                    stringResource(R.string.terminal_saved_connection_picker_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.terminal_saved_connection_picker_detail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(
                            items = hosts,
                            key = { requireNotNull(it.draft.persistentId) },
                        ) { host ->
                            val hostId = requireNotNull(host.draft.persistentId)
                            val connectDescription = stringResource(
                                R.string.connections_connect_to,
                                host.draft.displayName,
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectHost(host) }
                                    .semantics { contentDescription = connectDescription }
                                    .testTag(SavedConnectionPickerHostTestTagPrefix + hostId)
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = host.draft.displayName,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = host.draft.protocol.name,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    text = host.draft.endpointLabel(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (catalog != null && hosts.isEmpty()) {
                TextButton(onClick = onOpenConnections) {
                    Text(stringResource(R.string.terminal_saved_connection_picker_open_connections))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun HostEditorDraft.endpointLabel(): String {
    val displayHost = if (':' in hostname && !hostname.startsWith('[')) "[$hostname]" else hostname
    return "$username@$displayHost:$port"
}
