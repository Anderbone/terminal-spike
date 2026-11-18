package com.yanjiyu.terminalspike.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.backup.BackupContentSummary
import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import com.yanjiyu.terminalspike.ui.theme.AppDialog

internal data class BackupSettingsActions(
    val onBeginExport: () -> Unit,
    val onBeginRestore: () -> Unit,
    val onApplyImport: () -> Unit,
    val onRecover: () -> Unit,
    val onDismiss: () -> Unit,
) {
    companion object {
        val NONE = BackupSettingsActions(
            onBeginExport = {},
            onBeginRestore = {},
            onApplyImport = {},
            onRecover = {},
            onDismiss = {},
        )
    }
}

@Composable
internal fun BackupRestoreSettingsContent(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize().testTag(BackupRestoreSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_category_backup_restore),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_backup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.recoveryAvailable) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_backup_recovery_available),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.settings_backup_recovery_summary),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = actions.onRecover,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_backup_recover_action))
                        }
                    }
                }
            }
        }
        item(key = R.string.settings_backup_save_action) {
            Button(
                onClick = actions.onBeginExport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag(BackupSaveActionTestTag),
            ) {
                Text(stringResource(R.string.settings_backup_save_action))
            }
            Text(
                text = stringResource(R.string.settings_backup_save_summary),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = R.string.settings_backup_restore_action) {
            OutlinedButton(
                onClick = actions.onBeginRestore,
                enabled = !state.busy && !state.recoveryAvailable,
                modifier = Modifier.fillMaxWidth().testTag(BackupRestoreActionTestTag),
            ) {
                Text(stringResource(R.string.settings_backup_restore_action))
            }
            Text(
                text = if (state.recoveryAvailable) {
                    stringResource(R.string.settings_backup_restore_blocked_by_recovery)
                } else {
                    stringResource(R.string.settings_backup_restore_summary)
                },
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                text = stringResource(R.string.settings_backup_picker_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BackupWorkflowDialogs(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    when (state.step) {
        BackupWorkflowStep.EXPORT_SETUP,
        BackupWorkflowStep.PASSPHRASE_REQUIRED,
        BackupWorkflowStep.AUTHENTICATED_PREVIEW -> Unit
        BackupWorkflowStep.IMPORT_REVIEW -> ImportReviewDialog(state, actions)
        BackupWorkflowStep.EXPORTING,
        BackupWorkflowStep.INSPECTING,
        BackupWorkflowStep.UNLOCKING,
        BackupWorkflowStep.PLANNING,
        BackupWorkflowStep.APPLYING,
        BackupWorkflowStep.RECOVERING -> BusyBackupDialog(state.step)
        BackupWorkflowStep.COMPLETED -> BackupCompletedDialog(state, actions.onDismiss)
        BackupWorkflowStep.FAILED -> BackupFailureDialog(state, actions)
        BackupWorkflowStep.READY,
        BackupWorkflowStep.WAITING_FOR_EXPORT_DOCUMENT,
        BackupWorkflowStep.WAITING_FOR_RESTORE_DOCUMENT -> Unit
    }
}

@Composable
private fun ImportReviewDialog(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    val plan = state.plan ?: return
    AppDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.settings_backup_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strategyTitle(plan.strategy), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        R.string.settings_backup_plan_counts,
                        plan.conflicts,
                        plan.skippedRecords,
                        plan.incompatibleRecords,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (plan.recordIdRewrites + plan.secretIdRewrites > 0) {
                    Text(
                        text = stringResource(
                            R.string.settings_backup_rewrite_counts,
                            plan.recordIdRewrites,
                            plan.secretIdRewrites,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (plan.destructive) {
                    Text(
                        text = stringResource(R.string.settings_backup_replace_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = actions.onApplyImport) {
                Text(stringResource(R.string.settings_backup_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun BusyBackupDialog(step: BackupWorkflowStep) {
    AppDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.settings_backup_working_title)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(busyLabel(step))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun BackupCompletedDialog(
    state: BackupWorkflowUiState,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state.completionKind) {
                    BackupCompletionKind.EXPORT -> stringResource(R.string.settings_backup_saved_title)
                    BackupCompletionKind.IMPORT -> stringResource(R.string.settings_backup_restored_title)
                    BackupCompletionKind.RECOVERY -> stringResource(R.string.settings_backup_recovered_title)
                    null -> stringResource(R.string.settings_backup_complete_title)
                },
            )
        },
        text = {
            when (state.completionKind) {
                BackupCompletionKind.EXPORT -> state.exportResult?.let { result ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ContentSummary(result.content)
                        Text(
                            stringResource(R.string.settings_backup_bytes_written, result.bytesWritten),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                BackupCompletionKind.IMPORT,
                BackupCompletionKind.RECOVERY -> state.importResult?.let { result ->
                    ImportResultSummary(result)
                }
                null -> Unit
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun BackupFailureDialog(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    AppDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.settings_backup_failed_title)) },
        text = { BackupErrorText(state.error ?: BackupWorkflowError.IMPORT_FAILED) },
        confirmButton = {
            if (state.recoveryAvailable) {
                Button(onClick = actions.onRecover) {
                    Text(stringResource(R.string.settings_backup_recover_action))
                }
            } else {
                Button(onClick = actions.onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = if (state.recoveryAvailable) {
            { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.not_now)) } }
        } else {
            null
        },
    )
}

@Composable
private fun ContentSummary(content: BackupContentSummary) {
    Text(
        text = stringResource(
            R.string.settings_backup_content_counts,
            content.hosts,
            content.credentials,
            content.sshKeys,
            content.knownHosts,
            content.snippets,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(
            R.string.settings_backup_profile_counts,
            content.terminalProfiles,
            content.terminalThemes,
            content.keyboardProfiles,
            content.portableCredentialSecrets + content.portablePrivateKeys,
            content.customFonts,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImportResultSummary(result: BackupImportResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(
                R.string.settings_backup_result_counts,
                result.hostsApplied,
                result.credentialsApplied,
                result.sshKeysApplied,
                result.knownHostsApplied,
                result.snippetsApplied,
            ),
        )
        Text(
            stringResource(
                R.string.settings_backup_result_profile_counts,
                result.terminalProfilesApplied,
                result.terminalThemesApplied,
                result.keyboardProfilesApplied,
                result.customFontsApplied,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.settings_backup_result_skips,
                result.skippedRecords,
                result.incompatibleRecords,
                result.unavailableSecretPlaceholders,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!result.recoveryMarkerRemoved) {
            Text(
                stringResource(R.string.settings_backup_recovery_cleanup_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BackupErrorText(error: BackupWorkflowError) {
    Text(
        text = stringResource(error.messageRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun strategyTitle(strategy: BackupImportStrategy): String = when (strategy) {
    BackupImportStrategy.MERGE -> stringResource(R.string.settings_backup_strategy_merge)
    BackupImportStrategy.REPLACE_CORRESPONDING ->
        stringResource(R.string.settings_backup_strategy_replace)
    BackupImportStrategy.KEEP_BOTH -> stringResource(R.string.settings_backup_strategy_keep_both)
}

@Composable
private fun busyLabel(step: BackupWorkflowStep): String = when (step) {
    BackupWorkflowStep.EXPORTING -> stringResource(R.string.settings_backup_exporting)
    BackupWorkflowStep.INSPECTING -> stringResource(R.string.settings_backup_inspecting)
    BackupWorkflowStep.UNLOCKING -> stringResource(R.string.settings_backup_unlocking)
    BackupWorkflowStep.PLANNING -> stringResource(R.string.settings_backup_planning)
    BackupWorkflowStep.APPLYING -> stringResource(R.string.settings_backup_applying)
    BackupWorkflowStep.RECOVERING -> stringResource(R.string.settings_backup_recovering)
    else -> stringResource(R.string.settings_backup_working)
}

private fun BackupWorkflowError.messageRes(): Int = when (this) {
    BackupWorkflowError.PASSPHRASE_TOO_SHORT -> R.string.settings_backup_error_passphrase_short
    BackupWorkflowError.PASSPHRASE_TOO_LONG -> R.string.settings_backup_error_passphrase_long
    BackupWorkflowError.DOCUMENT_UNAVAILABLE -> R.string.settings_backup_error_document
    BackupWorkflowError.DOCUMENT_ACCESS_DENIED -> R.string.settings_backup_error_access
    BackupWorkflowError.INVALID_BACKUP -> R.string.settings_backup_error_invalid
    BackupWorkflowError.UNSUPPORTED_BACKUP -> R.string.settings_backup_error_unsupported
    BackupWorkflowError.UNLOCK_FAILED -> R.string.settings_backup_error_unlock
    BackupWorkflowError.DATA_CHANGED -> R.string.settings_backup_error_stale
    BackupWorkflowError.RECOVERY_REQUIRED -> R.string.settings_backup_error_recovery_required
    BackupWorkflowError.RECOVERY_SNAPSHOT_UNAVAILABLE ->
        R.string.settings_backup_error_recovery_snapshot_unavailable
    BackupWorkflowError.EXPORT_FAILED -> R.string.settings_backup_error_export
    BackupWorkflowError.IMPORT_FAILED -> R.string.settings_backup_error_import
    BackupWorkflowError.RECOVERY_FAILED -> R.string.settings_backup_error_recovery
}

internal const val BackupRestoreSettingsTestTag = "backup-restore-settings"
internal const val BackupSaveActionTestTag = "backup-save-action"
internal const val BackupRestoreActionTestTag = "backup-restore-action"
internal const val BackupPassphraseTestTag = "backup-passphrase"
