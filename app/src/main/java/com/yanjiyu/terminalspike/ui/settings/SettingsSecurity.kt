package com.yanjiyu.terminalspike.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.security.applock.AndroidAppLockAuthenticator
import com.yanjiyu.terminalspike.core.security.applock.AppLockAvailability
import com.yanjiyu.terminalspike.ui.theme.AppDialog

@Composable
internal fun SecuritySettings(
    state: SettingsUiState,
    knownHosts: List<KnownHostSummary>,
    onAppLockMode: (AppLockModeOption) -> Unit,
    onAppLockDelay: (Int) -> Unit,
    onScreenshotBlocking: (Boolean) -> Unit,
    onSensitiveClipboardClear: (SensitiveClipboardClearPreset) -> Unit,
    savedCredentialClearState: SavedCredentialClearUiState,
    onBeginClearSavedCredentials: () -> Unit,
    onConfirmClearSavedCredentials: (String) -> Unit,
    onDismissClearSavedCredentials: () -> Unit,
    onForgetKnownHost: (KnownHostSummary) -> Unit,
) {
    val context = LocalContext.current
    val appLockAvailability = rememberAppLockAvailability(context)
    val canEnableAppLock = appLockAvailability == AppLockAvailability.AVAILABLE
    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize().testTag(SecuritySettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DetailHeading(R.string.settings_category_security, R.string.settings_security_intro) }
        item { SectionLabel(R.string.settings_app_lock_heading) }
        item(key = R.string.settings_app_lock_authentication) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_app_lock_authentication),
                summary = stringResource(R.string.settings_app_lock_authentication_summary),
                value = stringResource(
                    when (appLockAvailability) {
                        AppLockAvailability.AVAILABLE -> R.string.settings_app_lock_available
                        AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED -> {
                            R.string.settings_app_lock_device_credential_missing
                        }
                        AppLockAvailability.UNSUPPORTED -> R.string.settings_app_lock_unsupported
                    },
                ),
            )
            HorizontalChoices {
                AppLockModeOption.entries.forEach { mode ->
                    FilterChip(
                        selected = state.preferences.appLockMode == mode,
                        enabled = mode == AppLockModeOption.OFF || canEnableAppLock,
                        onClick = { onAppLockMode(mode) },
                        label = { Text(stringResource(mode.labelRes)) },
                    )
                }
            }
            Text(
                text = stringResource(
                    when (state.preferences.appLockMode) {
                        AppLockModeOption.OFF -> R.string.settings_app_lock_off_summary
                        AppLockModeOption.IMMEDIATE -> R.string.settings_app_lock_immediate_summary
                        AppLockModeOption.DELAYED -> R.string.settings_app_lock_delayed_summary
                        AppLockModeOption.ON_BACKGROUND -> R.string.settings_app_lock_on_background_summary
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.preferences.appLockMode == AppLockModeOption.DELAYED) {
            item {
                Text(
                    text = stringResource(R.string.settings_app_lock_timeout),
                    style = MaterialTheme.typography.bodyLarge,
                )
                HorizontalChoices {
                    (SettingsViewModel.appLockDelayPresets + state.preferences.appLockDelaySeconds)
                        .distinct()
                        .sorted()
                        .forEach { seconds ->
                            FilterChip(
                                selected = state.preferences.appLockDelaySeconds == seconds,
                                enabled = canEnableAppLock,
                                onClick = { onAppLockDelay(seconds) },
                                label = { Text(stringResource(R.string.settings_seconds_short, seconds)) },
                            )
                        }
                }
            }
        }
        if (!canEnableAppLock) {
            item {
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.app_lock_open_security_settings))
                }
            }
        }
        item { SectionDivider() }
        item(key = R.string.settings_block_screenshots) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_block_screenshots),
                summary = stringResource(R.string.settings_block_screenshots_summary),
                checked = state.preferences.screenshotBlockingEnabled,
                onCheckedChange = onScreenshotBlocking,
            )
        }
        item(key = R.string.settings_credential_storage) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_credential_storage),
                summary = stringResource(R.string.settings_credential_storage_summary),
                value = stringResource(R.string.settings_status_device_encrypted),
            )
            OutlinedButton(
                onClick = onBeginClearSavedCredentials,
                enabled = savedCredentialClearState !is SavedCredentialClearUiState.LoadingPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SavedCredentialClearButtonTestTag),
            ) {
                if (savedCredentialClearState is SavedCredentialClearUiState.LoadingPreview) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_clear_saved_credentials))
            }
        }
        item(key = R.string.settings_remote_clipboard) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_remote_clipboard),
                summary = stringResource(R.string.settings_remote_clipboard_summary),
                value = when (state.terminalProfile?.links?.remoteClipboardMode) {
                    RemoteClipboardMode.ASK -> stringResource(R.string.settings_ask)
                    else -> stringResource(R.string.settings_disabled)
                },
            )
        }
        item(key = R.string.settings_clipboard_clear_heading) {
            Text(
                text = stringResource(R.string.settings_clipboard_clear_heading),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.settings_clipboard_clear_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalChoices {
                SensitiveClipboardClearPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = SensitiveClipboardClearPreset.fromSeconds(
                            state.preferences.sensitiveClipboardClearSeconds,
                        ) == preset,
                        onClick = { onSensitiveClipboardClear(preset) },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }
        }
        item { SectionDivider() }
        item(key = R.string.settings_known_hosts_heading) {
            SectionLabel(R.string.settings_known_hosts_heading)
            Text(
                text = stringResource(R.string.settings_known_hosts_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (knownHosts.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.settings_known_hosts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            items(knownHosts, key = { "${it.host}:${it.algorithm}" }) { host ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(host.host, style = MaterialTheme.typography.bodyLarge)
                        SelectionContainer {
                            Text(
                                text = "${host.algorithm} · ${host.sha256Fingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    TextButton(onClick = { onForgetKnownHost(host) }) {
                        Text(stringResource(R.string.settings_forget_known_host_action_short))
                    }
                }
                HorizontalDivider()
            }
        }
        item {
            SectionDivider()
            Text(
                text = stringResource(R.string.settings_local_first_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SavedCredentialClearDialog(
        state = savedCredentialClearState,
        onRetryPreview = onBeginClearSavedCredentials,
        onConfirm = onConfirmClearSavedCredentials,
        onDismiss = onDismissClearSavedCredentials,
    )
}

@Composable
private fun SavedCredentialClearDialog(
    state: SavedCredentialClearUiState,
    onRetryPreview: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        SavedCredentialClearUiState.Idle,
        SavedCredentialClearUiState.LoadingPreview -> Unit

        SavedCredentialClearUiState.PreviewFailed -> AppDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_clear_saved_credentials)) },
            text = { Text(stringResource(R.string.settings_clear_credentials_preview_failed)) },
            confirmButton = {
                Button(onClick = onRetryPreview) {
                    Text(stringResource(R.string.settings_try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
        )

        is SavedCredentialClearUiState.Confirming -> {
            var confirmation by rememberSaveable(
                state.credentialCount,
                state.keyIdentityCount,
            ) { mutableStateOf("") }
            AppDialog(
                onDismissRequest = { if (!state.clearing) onDismiss() },
                title = { Text(stringResource(R.string.settings_clear_saved_credentials_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_clear_saved_credentials_warning))
                        Text(
                            stringResource(
                                R.string.settings_clear_saved_credentials_counts,
                                state.credentialCount,
                                state.keyIdentityCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(
                                R.string.settings_clear_saved_credentials_type_phrase,
                                CLEAR_SAVED_CREDENTIALS_CONFIRMATION,
                            ),
                        )
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it.take(64) },
                            enabled = !state.clearing,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SavedCredentialClearConfirmationTestTag),
                        )
                        if (state.failed) {
                            Text(
                                stringResource(R.string.settings_clear_credentials_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onConfirm(confirmation) },
                        enabled = !state.clearing &&
                            confirmation == CLEAR_SAVED_CREDENTIALS_CONFIRMATION,
                    ) {
                        if (state.clearing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.settings_clear_saved_credentials_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !state.clearing) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        is SavedCredentialClearUiState.Completed -> AppDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_clear_saved_credentials_complete)) },
            text = {
                Text(
                    if (state.credentialsDeleted == 0 && state.keyIdentitiesDeleted == 0) {
                        stringResource(R.string.settings_clear_saved_credentials_none)
                    } else {
                        stringResource(
                            R.string.settings_clear_saved_credentials_result,
                            state.credentialsDeleted,
                            state.keyIdentitiesDeleted,
                        )
                    },
                )
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
            },
        )
    }
}

internal const val SecuritySettingsTestTag = "security-settings"
internal const val SavedCredentialClearButtonTestTag = "clear-saved-credentials"
internal const val SavedCredentialClearConfirmationTestTag = "clear-saved-credentials-confirmation"

@Composable
private fun rememberAppLockAvailability(context: Context): AppLockAvailability {
    val lifecycleOwner = LocalLifecycleOwner.current
    var availability by remember(context) {
        mutableStateOf(AndroidAppLockAuthenticator.availabilityFor(context))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                availability = AndroidAppLockAuthenticator.availabilityFor(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return availability
}
