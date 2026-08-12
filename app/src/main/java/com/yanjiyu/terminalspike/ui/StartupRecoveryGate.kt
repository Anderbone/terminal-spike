package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataState

internal const val StartupRecoveryGateTestTag = "startup-recovery-gate"
internal const val StartupRecoveryRetryTestTag = "startup-recovery-retry"
internal const val StartupRecoveryReviewResetTestTag = "startup-recovery-review-reset"

/** Opaque fail-closed root used while authoritative Room/DataStore state is unresolved. */
@Composable
internal fun StartupRecoveryGate(
    state: AuthoritativeDataState,
    onRetry: () -> Unit,
    /** Must request Android's full app-data reset; marker-only deletion is not safe here. */
    onResetConfirmed: () -> Boolean,
) {
    var confirmingReset by rememberSaveable(state is AuthoritativeDataState.RecoveryFailed) {
        mutableStateOf(false)
    }
    var resetInProgress by rememberSaveable(state is AuthoritativeDataState.RecoveryFailed) {
        mutableStateOf(false)
    }
    var resetFailed by rememberSaveable(state is AuthoritativeDataState.RecoveryFailed) {
        mutableStateOf(false)
    }
    Surface(
        modifier = Modifier.fillMaxSize().testTag(StartupRecoveryGateTestTag),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            when (state) {
                AuthoritativeDataState.Checking -> StartupRecoveryProgress(
                    title = stringResource(R.string.startup_recovery_checking_title),
                    message = stringResource(R.string.startup_recovery_checking_message),
                )

                AuthoritativeDataState.RecoveryRequired -> StartupRecoveryProgress(
                    title = stringResource(R.string.startup_recovery_working_title),
                    message = stringResource(R.string.startup_recovery_working_message),
                )

                is AuthoritativeDataState.RecoveryFailed -> StartupRecoveryMessage(
                    title = stringResource(R.string.startup_recovery_failed_title),
                    message = stringResource(R.string.startup_recovery_failed_message),
                    onRetry = onRetry,
                    onReviewReset = {
                        resetFailed = false
                        confirmingReset = true
                    },
                )

                // MainActivity replaces this root with normal app content only when Ready.
                is AuthoritativeDataState.Ready -> Unit
            }
        }
    }
    if (state is AuthoritativeDataState.RecoveryFailed && confirmingReset) {
        PermanentLocalDataResetConfirmation(
            inProgress = resetInProgress,
            failureMessage = if (resetFailed) {
                stringResource(R.string.local_data_reset_failed)
            } else {
                null
            },
            onResetConfirmed = {
                if (!resetInProgress) {
                    resetInProgress = true
                    val accepted = runCatching(onResetConfirmed).getOrDefault(false)
                    if (!accepted) {
                        resetInProgress = false
                        resetFailed = true
                    }
                }
            },
            onCancel = {
                resetFailed = false
                confirmingReset = false
            },
        )
    }
}

@Composable
private fun StartupRecoveryProgress(title: String, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        StartupRecoveryText(title, message)
    }
}

@Composable
private fun StartupRecoveryMessage(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onReviewReset: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StartupRecoveryText(title, message)
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag(StartupRecoveryRetryTestTag),
        ) {
            Text(stringResource(R.string.try_again))
        }
        TextButton(
            onClick = onReviewReset,
            modifier = Modifier.testTag(StartupRecoveryReviewResetTestTag),
        ) {
            Text(stringResource(R.string.local_data_reset_review))
        }
    }
}

@Composable
private fun StartupRecoveryText(title: String, message: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
