package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure

@Composable
internal fun SettingsRecoveryDialog(
    failure: SettingsLoadFailure,
    inProgress: Boolean,
    onRetry: () -> Unit,
    onResetConfirmed: () -> Unit,
) {
    var confirmingReset by rememberSaveable(failure) { mutableStateOf(false) }
    if (confirmingReset) {
        PermanentLocalDataResetConfirmation(
            inProgress = inProgress,
            failureMessage = null,
            onResetConfirmed = onResetConfirmed,
            onCancel = { confirmingReset = false },
        )
        return
    }

    val title = when (failure) {
        SettingsLoadFailure.APP_DATA_UNAVAILABLE ->
            stringResource(R.string.local_data_recovery_unavailable_title)
        SettingsLoadFailure.KEY_UNAVAILABLE,
        SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED,
        -> stringResource(R.string.encrypted_local_data_recovery_title)
    }
    val message = when (failure) {
        SettingsLoadFailure.KEY_UNAVAILABLE ->
            stringResource(R.string.encrypted_local_data_key_unavailable_message)
        SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED ->
            stringResource(R.string.encrypted_local_data_corrupt_message)
        SettingsLoadFailure.APP_DATA_UNAVAILABLE ->
            stringResource(R.string.local_data_recovery_unavailable_message)
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Text(
                message,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = onRetry,
                enabled = !inProgress,
            ) {
                Text(
                    stringResource(
                        if (inProgress) R.string.local_data_retrying else R.string.try_again,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmingReset = true },
                enabled = !inProgress,
            ) {
                Text(stringResource(R.string.local_data_reset_review))
            }
        },
    )
}

/** Shared second, destructive step. Callers must never substitute marker-only deletion here. */
@Composable
internal fun PermanentLocalDataResetConfirmation(
    inProgress: Boolean,
    failureMessage: String?,
    onResetConfirmed: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.local_data_reset_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.local_data_reset_message),
                )
                failureMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onResetConfirmed,
                enabled = !inProgress,
            ) {
                Text(
                    stringResource(
                        if (inProgress) R.string.local_data_resetting else R.string.local_data_reset_confirm,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !inProgress,
            ) {
                Text(stringResource(R.string.local_data_reset_cancel))
            }
        },
    )
}
