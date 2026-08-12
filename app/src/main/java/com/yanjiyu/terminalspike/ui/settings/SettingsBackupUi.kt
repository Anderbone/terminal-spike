package com.yanjiyu.terminalspike.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.backup.BackupContentSummary
import com.yanjiyu.terminalspike.core.backup.BackupEnvelopeFormat
import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import com.yanjiyu.terminalspike.core.backup.BackupMode
import com.yanjiyu.terminalspike.ui.WipeableSecretInput
import com.yanjiyu.terminalspike.ui.WipeableSecretInputState
import java.text.DateFormat
import java.util.Date

internal data class BackupSettingsActions(
    val onBeginExport: () -> Unit,
    val onSubmitExport: (BackupMode, Boolean, CharArray) -> Unit,
    val onBeginRestore: () -> Unit,
    val onSubmitRestorePassphrase: (CharArray) -> Unit,
    val onSelectStrategy: (BackupImportStrategy) -> Unit,
    val onPrepareImport: () -> Unit,
    val onApplyImport: () -> Unit,
    val onRecover: () -> Unit,
    val onDismiss: () -> Unit,
) {
    companion object {
        val NONE = BackupSettingsActions(
            onBeginExport = {},
            onSubmitExport = { _, _, passphrase -> passphrase.fill('\u0000') },
            onBeginRestore = {},
            onSubmitRestorePassphrase = { passphrase -> passphrase.fill('\u0000') },
            onSelectStrategy = {},
            onPrepareImport = {},
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
    LazyColumn(
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
        item {
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
        item {
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
        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            BackupModeExplanation(
                title = stringResource(R.string.settings_backup_standard),
                summary = stringResource(R.string.settings_backup_standard_summary),
                detail = stringResource(R.string.settings_backup_standard_encryption),
            )
            BackupModeExplanation(
                title = stringResource(R.string.settings_backup_full),
                summary = stringResource(R.string.settings_backup_full_summary),
                detail = stringResource(R.string.settings_backup_full_warning),
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
        BackupWorkflowStep.EXPORT_SETUP -> ExportSetupDialog(state.error, actions)
        BackupWorkflowStep.PASSPHRASE_REQUIRED -> RestorePassphraseDialog(state, actions)
        BackupWorkflowStep.AUTHENTICATED_PREVIEW -> AuthenticatedPreviewDialog(state, actions)
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
private fun ExportSetupDialog(
    error: BackupWorkflowError?,
    actions: BackupSettingsActions,
) {
    var mode by remember { mutableStateOf(BackupMode.STANDARD) }
    var includeCustomFonts by remember { mutableStateOf(false) }
    val passphrase = remember { WipeableSecretInputState() }
    val confirmation = remember { WipeableSecretInputState() }
    val minimum = SettingsBackupWorkflow.minimumExportPassphraseCharacters(mode)
    val matches = passphrase.contentEquals(confirmation)
    val valid = matches && passphrase.characterCount >= minimum
    fun dismissAndWipe() {
        passphrase.wipe()
        confirmation.wipe()
        actions.onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismissAndWipe,
        title = { Text(stringResource(R.string.settings_backup_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BackupModeChoice(
                    selected = mode == BackupMode.STANDARD,
                    title = stringResource(R.string.settings_backup_standard),
                    summary = stringResource(R.string.settings_backup_standard_summary),
                    onClick = { mode = BackupMode.STANDARD },
                )
                BackupModeChoice(
                    selected = mode == BackupMode.FULL,
                    title = stringResource(R.string.settings_backup_full),
                    summary = stringResource(R.string.settings_backup_full_summary),
                    onClick = { mode = BackupMode.FULL },
                )
                if (mode == BackupMode.FULL) {
                    Text(
                        text = stringResource(R.string.settings_backup_full_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeCustomFonts = !includeCustomFonts }
                        .testTag(BackupIncludeCustomFontsTestTag)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeCustomFonts,
                        onCheckedChange = { includeCustomFonts = it },
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.settings_backup_include_custom_fonts))
                        Text(
                            text = stringResource(R.string.settings_backup_include_custom_fonts_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                WipeableSecretInput(
                    state = passphrase,
                    label = stringResource(R.string.settings_backup_passphrase),
                    testTag = BackupPassphraseTestTag,
                    imeAction = ImeAction.Next,
                    maxCharacters = BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS,
                    modifier = Modifier.fillMaxWidth(),
                )
                WipeableSecretInput(
                    state = confirmation,
                    label = stringResource(R.string.settings_backup_confirm_passphrase),
                    testTag = BackupPassphraseConfirmationTestTag,
                    imeAction = ImeAction.Done,
                    maxCharacters = BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_backup_passphrase_requirement, minimum),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (confirmation.hasValue && !matches) {
                    Text(
                        text = stringResource(R.string.settings_backup_passphrase_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { BackupErrorText(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val owned = passphrase.takeChars()
                    confirmation.wipe()
                    try {
                        actions.onSubmitExport(mode, includeCustomFonts, owned)
                    } catch (error: Throwable) {
                        owned.fill('\u0000')
                        throw error
                    }
                },
            ) { Text(stringResource(R.string.settings_backup_choose_location)) }
        },
        dismissButton = {
            TextButton(
                onClick = ::dismissAndWipe,
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RestorePassphraseDialog(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    val passphrase = remember { WipeableSecretInputState() }
    fun dismissAndWipe() {
        passphrase.wipe()
        actions.onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismissAndWipe,
        title = { Text(stringResource(R.string.settings_backup_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.header?.let { HeaderSummary(it) }
                WipeableSecretInput(
                    state = passphrase,
                    label = stringResource(R.string.settings_backup_passphrase),
                    testTag = BackupPassphraseTestTag,
                    imeAction = ImeAction.Done,
                    maxCharacters = BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { BackupErrorText(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = passphrase.hasValue,
                onClick = {
                    val owned = passphrase.takeChars()
                    try {
                        actions.onSubmitRestorePassphrase(owned)
                    } catch (error: Throwable) {
                        owned.fill('\u0000')
                        throw error
                    }
                },
            ) { Text(stringResource(R.string.settings_backup_unlock_action)) }
        },
        dismissButton = {
            TextButton(
                onClick = ::dismissAndWipe,
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AuthenticatedPreviewDialog(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    val archive = state.archive ?: return
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.settings_backup_authenticated_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderSummary(archive.header)
                ContentSummary(archive.content)
                if (archive.incompatibleRecords + archive.skippedRecords > 0) {
                    Text(
                        text = stringResource(
                            R.string.settings_backup_archive_skips,
                            archive.incompatibleRecords,
                            archive.skippedRecords,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_backup_strategy_heading),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                BackupImportStrategy.entries.forEach { strategy ->
                    StrategyChoice(
                        strategy = strategy,
                        selected = state.selectedStrategy == strategy,
                        onClick = { actions.onSelectStrategy(strategy) },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = actions.onPrepareImport) {
                Text(stringResource(R.string.settings_backup_preview_changes))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportReviewDialog(
    state: BackupWorkflowUiState,
    actions: BackupSettingsActions,
) {
    val plan = state.plan ?: return
    AlertDialog(
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
    AlertDialog(
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
    AlertDialog(
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
    AlertDialog(
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
private fun BackupModeChoice(
    selected: Boolean,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StrategyChoice(
    strategy: BackupImportStrategy,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(strategyTitle(strategy), style = MaterialTheme.typography.titleSmall)
            Text(strategySummary(strategy), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BackupModeExplanation(title: String, summary: String, detail: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeaderSummary(header: BackupHeaderUiSummary) {
    val created = remember(header.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(header.createdAtEpochMillis))
    }
    Text(
        text = stringResource(
            R.string.settings_backup_header_summary,
            modeTitle(header.mode),
            created,
            header.appVersionName,
            header.payloadSchemaVersion,
        ),
        style = MaterialTheme.typography.bodyMedium,
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
private fun modeTitle(mode: BackupMode): String = when (mode) {
    BackupMode.STANDARD -> stringResource(R.string.settings_backup_standard)
    BackupMode.FULL -> stringResource(R.string.settings_backup_full)
}

@Composable
private fun strategyTitle(strategy: BackupImportStrategy): String = when (strategy) {
    BackupImportStrategy.MERGE -> stringResource(R.string.settings_backup_strategy_merge)
    BackupImportStrategy.REPLACE_CORRESPONDING ->
        stringResource(R.string.settings_backup_strategy_replace)
    BackupImportStrategy.KEEP_BOTH -> stringResource(R.string.settings_backup_strategy_keep_both)
}

@Composable
private fun strategySummary(strategy: BackupImportStrategy): String = when (strategy) {
    BackupImportStrategy.MERGE -> stringResource(R.string.settings_backup_strategy_merge_summary)
    BackupImportStrategy.REPLACE_CORRESPONDING ->
        stringResource(R.string.settings_backup_strategy_replace_summary)
    BackupImportStrategy.KEEP_BOTH ->
        stringResource(R.string.settings_backup_strategy_keep_both_summary)
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
internal const val BackupPassphraseConfirmationTestTag = "backup-passphrase-confirmation"
internal const val BackupIncludeCustomFontsTestTag = "backup-include-custom-fonts"
