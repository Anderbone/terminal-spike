package com.yanjiyu.terminalspike.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode

@Composable
internal fun TerminalSettings(state: SettingsUiState, actions: SettingsActions) {
    val profile = state.terminalProfile
    var resetRequested by remember { mutableStateOf(false) }

    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize().testTag(TerminalSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_terminal, R.string.settings_terminal_intro) }
        if (profile == null) {
            item { LoadingOrUnavailable(state.profilesLoading) }
        } else {
            item { SectionLabel(R.string.settings_links_clipboard_heading) }
            item(key = R.string.settings_terminal_copy_on_selection) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_terminal_copy_on_selection),
                    summary = stringResource(R.string.settings_terminal_copy_on_selection_summary),
                    checked = profile.links.copyOnSelection,
                    onCheckedChange = { enabled ->
                        actions.onLinks { links -> links.copy(copyOnSelection = enabled) }
                    },
                )
            }
            item(key = R.string.settings_osc52_heading) {
                Text(
                    text = stringResource(R.string.settings_osc52_heading),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_osc52_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalChoices {
                    RemoteClipboardMode.entries.forEach { mode ->
                        FilterChip(
                            selected = profile.links.remoteClipboardMode == mode,
                            onClick = {
                                actions.onLinks { links -> links.copy(remoteClipboardMode = mode) }
                            },
                            label = { Text(remoteClipboardLabel(mode)) },
                        )
                    }
                }
            }
            item(key = R.string.settings_multiline_paste_confirmation) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_multiline_paste_confirmation),
                    summary = stringResource(R.string.settings_multiline_paste_confirmation_summary),
                    checked = state.preferences.multilinePasteConfirmationEnabled,
                    onCheckedChange = actions.onMultilinePasteConfirmation,
                )
            }
            item {
                TextButton(onClick = { resetRequested = true }) {
                    Text(stringResource(R.string.settings_reset_terminal))
                }
            }
        }
    }

    if (resetRequested) {
        ResetDialog(
            title = R.string.settings_reset_terminal_title,
            message = R.string.settings_reset_terminal_message,
            onDismiss = { resetRequested = false },
            onConfirm = {
                resetRequested = false
                actions.onResetTerminal()
            },
        )
    }
}

@Composable
private fun remoteClipboardLabel(mode: RemoteClipboardMode): String = when (mode) {
    RemoteClipboardMode.DISABLED -> stringResource(R.string.settings_disabled)
    RemoteClipboardMode.ASK -> stringResource(R.string.settings_ask)
}

internal const val TerminalSettingsTestTag = "terminal-settings"
