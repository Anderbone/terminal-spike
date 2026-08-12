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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.yanjiyu.terminalspike.core.security.applock.AppLockAuthenticationFailure
import com.yanjiyu.terminalspike.core.security.applock.AppLockAvailability
import com.yanjiyu.terminalspike.core.security.applock.AppLockUiState

internal const val AppLockGateTestTag = "app-lock-gate"
internal const val AppLockRetryTestTag = "app-lock-retry"
internal const val AppLockReviewResetTestTag = "app-lock-review-reset"

/** A fail-closed root surface; protected app content is not composed behind it. */
@Composable
internal fun AppLockGate(
    state: AppLockUiState,
    onAuthenticate: () -> Unit,
    onRetrySettings: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    /** Must request Android's full app-data reset; marker/file-only deletion is not safe here. */
    onResetConfirmed: () -> Boolean,
) {
    var confirmingReset by rememberSaveable(state == AppLockUiState.SettingsUnavailable) {
        mutableStateOf(false)
    }
    var resetInProgress by rememberSaveable(state == AppLockUiState.SettingsUnavailable) {
        mutableStateOf(false)
    }
    var resetFailed by rememberSaveable(state == AppLockUiState.SettingsUnavailable) {
        mutableStateOf(false)
    }
    Surface(
        modifier = Modifier.fillMaxSize().testTag(AppLockGateTestTag),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            when (state) {
                AppLockUiState.SettingsLoading,
                AppLockUiState.Obscured,
                AppLockUiState.Authenticating,
                -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)

                AppLockUiState.SettingsUnavailable -> AppLockMessage(
                    title = stringResource(R.string.app_lock_settings_unavailable_title),
                    message = stringResource(R.string.app_lock_settings_unavailable_message),
                    primaryLabel = stringResource(R.string.try_again),
                    onPrimary = onRetrySettings,
                    secondaryLabel = stringResource(R.string.local_data_reset_review),
                    onSecondary = {
                        resetFailed = false
                        confirmingReset = true
                    },
                    secondaryTestTag = AppLockReviewResetTestTag,
                )

                AppLockUiState.AuthenticationRequired -> AppLockMessage(
                    title = stringResource(R.string.app_lock_locked_title),
                    message = stringResource(R.string.app_lock_locked_message),
                    primaryLabel = stringResource(R.string.app_lock_unlock_action),
                    onPrimary = onAuthenticate,
                )

                is AppLockUiState.AuthenticationFailed -> AppLockMessage(
                    title = stringResource(R.string.app_lock_locked_title),
                    message = stringResource(
                        if (state.failure == AppLockAuthenticationFailure.CANCELLED) {
                            R.string.app_lock_authentication_cancelled
                        } else {
                            R.string.app_lock_authentication_error
                        },
                    ),
                    primaryLabel = stringResource(R.string.try_again),
                    onPrimary = onAuthenticate,
                )

                is AppLockUiState.Unavailable -> AppLockMessage(
                    title = stringResource(R.string.app_lock_unavailable_title),
                    message = stringResource(
                        if (state.availability == AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED) {
                            R.string.app_lock_device_credential_required
                        } else {
                            R.string.app_lock_unsupported_message
                        },
                    ),
                    primaryLabel = stringResource(R.string.app_lock_open_security_settings),
                    onPrimary = onOpenSecuritySettings,
                    secondaryLabel = stringResource(R.string.try_again),
                    onSecondary = onAuthenticate,
                )

                // MainActivity replaces this root with app content when it becomes unlocked.
                AppLockUiState.Unlocked -> Unit
            }
        }
    }
    if (state == AppLockUiState.SettingsUnavailable && confirmingReset) {
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
private fun AppLockMessage(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryTestTag: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
        Button(onClick = onPrimary, modifier = Modifier.testTag(AppLockRetryTestTag)) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            OutlinedButton(
                onClick = onSecondary,
                modifier = if (secondaryTestTag == null) {
                    Modifier
                } else {
                    Modifier.testTag(secondaryTestTag)
                },
            ) { Text(secondaryLabel) }
        }
    }
}
