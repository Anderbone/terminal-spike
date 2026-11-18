package com.yanjiyu.terminalspike.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yanjiyu.terminalspike.R

private data class BackgroundHealth(
    val notificationsEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val backgroundRestricted: Boolean,
    val batteryOptimized: Boolean,
    val dataSaverStatus: Int,
)

@Composable
internal fun SessionsBackgroundSettings(state: SettingsUiState, actions: SettingsActions) {
    val context = LocalContext.current
    val health = rememberBackgroundHealth(context)
    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_sessions_background, R.string.settings_sessions_intro) }
        item(key = R.string.settings_auto_attach_herdr) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_auto_attach_herdr),
                summary = stringResource(R.string.settings_auto_attach_herdr_summary),
                checked = state.preferences.autoAttachSingleHerdrSession,
                onCheckedChange = actions.onAutoAttachSingleHerdrSession,
            )
        }
        item { SectionLabel(R.string.settings_connection_reliability_heading) }
        item(key = R.string.settings_tmux_session_selector) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_tmux_session_selector),
                summary = stringResource(R.string.settings_tmux_session_selector_summary),
                checked = state.preferences.tmuxSessionSelectorEnabled,
                onCheckedChange = actions.onTmuxSessionSelectorEnabled,
            )
        }
        item(key = R.string.settings_keepalive_interval) {
            Text(
                text = stringResource(R.string.settings_keepalive_interval),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_interval_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalChoices {
                (listOf(0, 15, 30, 60) + state.preferences.keepaliveIntervalSeconds)
                    .distinct()
                    .sorted()
                    .forEach { seconds ->
                        FilterChip(
                            selected = state.preferences.keepaliveIntervalSeconds == seconds,
                            onClick = { actions.onKeepaliveInterval(seconds) },
                            label = {
                                Text(
                                    if (seconds == 0) {
                                        stringResource(R.string.settings_status_off)
                                    } else {
                                        stringResource(R.string.settings_seconds_short, seconds)
                                    },
                                )
                            },
                        )
                    }
            }
        }
        item(key = R.string.settings_automatic_reconnect) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_automatic_reconnect),
                summary = stringResource(R.string.settings_automatic_reconnect_summary),
                checked = state.preferences.reconnectEnabled,
                onCheckedChange = actions.onReconnectEnabled,
            )
        }
        if (state.preferences.reconnectEnabled) {
            item {
                Text(
                    text = stringResource(R.string.settings_reconnect_attempts),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_reconnect_attempts_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalChoices {
                    (listOf(3, 5, 10) + state.preferences.reconnectMaxAttempts)
                        .distinct()
                        .sorted()
                        .forEach { attempts ->
                            FilterChip(
                                selected = state.preferences.reconnectMaxAttempts == attempts,
                                onClick = { actions.onReconnectMaxAttempts(attempts) },
                                label = { Text(attempts.toString()) },
                            )
                        }
                }
            }
        }
        item { SectionDivider() }
        item { SectionLabel(R.string.settings_power_heading) }
        item(key = R.string.settings_keep_cpu_awake) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_cpu_awake),
                summary = stringResource(R.string.settings_keep_cpu_awake_summary),
                checked = state.preferences.keepCpuAwake,
                onCheckedChange = actions.onKeepCpuAwake,
            )
        }
        item(key = R.string.settings_keep_screen_on) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                summary = stringResource(R.string.settings_keep_screen_on_summary),
                checked = state.preferences.keepScreenOnWhileTerminalVisible,
                onCheckedChange = actions.onKeepScreenOn,
            )
        }
        item { SectionDivider() }
        item { SectionLabel(R.string.settings_background_health_heading) }
        item(key = R.string.settings_notifications_status) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_notifications_status),
                summary = stringResource(R.string.settings_notifications_status_summary),
                value = if (health.notificationsEnabled && health.notificationPermissionGranted) {
                    stringResource(R.string.settings_status_enabled)
                } else {
                    stringResource(R.string.settings_status_limited)
                },
            )
        }
        item(key = R.string.settings_background_restriction) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_background_restriction),
                summary = stringResource(R.string.settings_background_restriction_summary),
                value = if (health.backgroundRestricted) {
                    stringResource(R.string.settings_status_restricted)
                } else {
                    stringResource(R.string.settings_status_not_restricted)
                },
            )
        }
        item(key = R.string.settings_battery_optimisation) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_battery_optimisation),
                summary = stringResource(R.string.settings_battery_optimisation_summary),
                value = if (health.batteryOptimized) {
                    stringResource(R.string.settings_status_optimised)
                } else {
                    stringResource(R.string.settings_status_unrestricted)
                },
            )
        }
        item(key = R.string.settings_data_saver) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_data_saver),
                summary = stringResource(R.string.settings_data_saver_summary),
                value = dataSaverLabel(health.dataSaverStatus),
            )
        }
        item {
            SystemSettingsButtons(
                actions = listOf(
                    R.string.settings_open_app_details to appDetailsIntent(context),
                    R.string.settings_open_battery_settings to Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    R.string.settings_open_data_saver to backgroundDataSettingsIntent(context),
                ),
            )
        }
        item {
            Text(
                text = stringResource(R.string.settings_background_honesty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NotificationSettings(state: SettingsUiState, actions: SettingsActions) {
    val context = LocalContext.current
    val health = rememberBackgroundHealth(context)
    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_notifications, R.string.settings_notifications_intro) }
        item(key = R.string.settings_notification_privacy) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_notification_privacy),
                summary = stringResource(R.string.settings_notification_privacy_summary),
                checked = state.preferences.notificationPrivacyEnabled,
                onCheckedChange = actions.onNotificationPrivacy,
            )
        }
        item(key = R.string.settings_disconnect_notifications) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_disconnect_notifications),
                summary = stringResource(R.string.settings_disconnect_notifications_summary),
                checked = state.preferences.disconnectNotificationsEnabled,
                onCheckedChange = actions.onDisconnectNotifications,
            )
        }
        item(key = R.string.settings_reconnect_notifications) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_reconnect_notifications),
                summary = stringResource(R.string.settings_reconnect_notifications_summary),
                checked = state.preferences.reconnectNotificationsEnabled,
                onCheckedChange = actions.onReconnectNotifications,
            )
        }
        item { SectionDivider() }
        item(key = R.string.settings_notification_permission) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_notification_permission),
                summary = stringResource(R.string.settings_notification_permission_summary),
                value = if (health.notificationPermissionGranted) {
                    stringResource(R.string.settings_status_allowed)
                } else {
                    stringResource(R.string.settings_status_not_allowed)
                },
            )
        }
        item(key = R.string.settings_session_channel) {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_session_channel),
                summary = stringResource(R.string.settings_session_channel_summary),
                value = if (health.notificationsEnabled) {
                    stringResource(R.string.settings_status_enabled)
                } else {
                    stringResource(R.string.settings_status_disabled)
                },
            )
        }
        item {
            SystemSettingsButtons(
                actions = listOf(R.string.settings_open_notification_settings to notificationSettingsIntent(context)),
            )
        }
        item {
            Text(
                text = stringResource(R.string.settings_notification_denial_honesty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rememberBackgroundHealth(context: Context): BackgroundHealth {
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(context, refresh) {
        val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        BackgroundHealth(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            notificationPermissionGranted = notificationPermission,
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager.isBackgroundRestricted,
            batteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName),
            dataSaverStatus = connectivity.restrictBackgroundStatus,
        )
    }
}

@Composable
private fun dataSaverLabel(status: Int): String = when (status) {
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> stringResource(R.string.settings_status_off)
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> stringResource(R.string.settings_status_unrestricted)
    else -> stringResource(R.string.settings_status_restricted)
}

@Composable
private fun SystemSettingsButtons(actions: List<Pair<Int, Intent>>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { (label, intent) ->
            val supported = remember(context, intent.action, intent.dataString) {
                intent.resolveActivity(context.packageManager) != null
            }
            OutlinedButton(
                onClick = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                enabled = supported,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(label)) }
        }
    }
}

private fun appDetailsIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

private fun notificationSettingsIntent(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun backgroundDataSettingsIntent(context: Context) = Intent(
    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)
