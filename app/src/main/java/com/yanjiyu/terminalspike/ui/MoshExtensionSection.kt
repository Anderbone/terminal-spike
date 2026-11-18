package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.theme.AppDialog

@Composable
internal fun MoshExtensionSection(
    state: MoshExtensionUiState,
    onRefresh: () -> Unit,
) {
    var showInstallationHelp by remember(state.kind) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag(MoshExtensionSectionTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mosh_extension_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = state.statusLabel.resolve(),
            modifier = Modifier.testTag(MoshExtensionStatusTestTag),
            style = MaterialTheme.typography.titleMedium,
            color = when (state.kind) {
                MoshExtensionUiKind.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
                MoshExtensionUiKind.AVAILABLE -> MaterialTheme.colorScheme.primary
                MoshExtensionUiKind.ABSENT,
                MoshExtensionUiKind.DISABLED,
                MoshExtensionUiKind.INCOMPATIBLE,
                -> MaterialTheme.colorScheme.tertiary
                MoshExtensionUiKind.UNTRUSTED,
                MoshExtensionUiKind.ERROR,
                -> MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = state.summary.resolve(),
            style = MaterialTheme.typography.bodyLarge,
        )
        state.details.forEach { detail ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = detail.label.resolve(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = detail.value.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.mosh_signature_verification),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.verificationMessage.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.actionLabel?.let { label ->
            Button(
                onClick = onRefresh,
                modifier = Modifier.testTag(MoshExtensionRefreshTestTag),
            ) {
                Text(label.resolve())
            }
        }
        state.installationHelpLabel?.let { label ->
            TextButton(
                onClick = { showInstallationHelp = true },
                modifier = Modifier.testTag(MoshExtensionInstallationHelpTestTag),
            ) {
                Text(label.resolve())
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.mosh_ssh_fallback),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.mosh_ssh_fallback_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.mosh_free_software_source),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.mosh_free_software_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.mosh_trademark_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showInstallationHelp) {
        MoshInstallationHelpDialog(onDismiss = { showInstallationHelp = false })
    }
}

@Composable
private fun MoshInstallationHelpDialog(onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(MoshExtensionInstallationHelpDialogTestTag),
        title = { Text(stringResource(R.string.mosh_installation_help_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.mosh_installation_help_matching_apk),
                )
                Text(
                    stringResource(R.string.mosh_installation_help_debug),
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.mosh_installation_help_distribution),
                )
                Text(
                    stringResource(R.string.mosh_installation_help_retry),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(MoshExtensionInstallationHelpCloseTestTag),
            ) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

internal const val MoshExtensionSectionTestTag = "mosh-extension-section"
internal const val MoshExtensionStatusTestTag = "mosh-extension-status"
internal const val MoshExtensionRefreshTestTag = "mosh-extension-refresh"
internal const val MoshExtensionInstallationHelpTestTag = "mosh-extension-installation-help"
internal const val MoshExtensionInstallationHelpDialogTestTag =
    "mosh-extension-installation-help-dialog"
internal const val MoshExtensionInstallationHelpCloseTestTag =
    "mosh-extension-installation-help-close"
