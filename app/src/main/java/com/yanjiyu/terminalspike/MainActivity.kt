package com.yanjiyu.terminalspike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataState
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsSerializer
import com.yanjiyu.terminalspike.core.security.applock.AndroidAppLockAuthenticator
import com.yanjiyu.terminalspike.core.security.applock.AppLockAuthenticationFailure
import com.yanjiyu.terminalspike.core.security.applock.AppLockAuthenticationResult
import com.yanjiyu.terminalspike.core.security.applock.AppLockMode
import com.yanjiyu.terminalspike.core.security.applock.AppLockPolicy
import com.yanjiyu.terminalspike.core.security.applock.AppLockUiState
import com.yanjiyu.terminalspike.core.security.applock.AppLockViewModel
import com.yanjiyu.terminalspike.connection.requiresForegroundService
import com.yanjiyu.terminalspike.ui.AppLockGate
import com.yanjiyu.terminalspike.ui.StartupRecoveryGate
import com.yanjiyu.terminalspike.ui.TerminalSpikeScreen
import com.yanjiyu.terminalspike.ui.TerminalSpikeViewModel
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: TerminalSpikeViewModel by viewModels()
    private val appLockViewModel: AppLockViewModel by viewModels()
    private val currentSettings = MutableStateFlow<AppSettings?>(null)
    private val disconnectAllConfirmationRequested = MutableStateFlow(false)
    private var settingsJob: Job? = null
    private lateinit var appContainer: AppContainer
    private lateinit var appLockAuthenticator: AndroidAppLockAuthenticator
    private lateinit var localAppDataResetter: LocalAppDataResetter
    private val deviceCredentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (::appLockAuthenticator.isInitialized) {
            appLockAuthenticator.onDeviceCredentialResult(result.resultCode == Activity.RESULT_OK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disconnectAllConfirmationRequested.value =
            savedInstanceState?.getBoolean(STATE_DISCONNECT_ALL_CONFIRMATION) == true
        captureNotificationAction(intent)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        appContainer = (application as TerminalSpikeApplication).container
        localAppDataResetter = AndroidLocalAppDataResetter(this)
        appLockAuthenticator = AndroidAppLockAuthenticator(this) { intent ->
            deviceCredentialLauncher.launch(intent)
        }
        collectSettings(appContainer)
        setContent {
            val settings = currentSettings.collectAsStateWithLifecycle().value
            val lockState = appLockViewModel.controller.state.collectAsStateWithLifecycle().value
            val authorityState = appContainer.authoritativeData.state
                .collectAsStateWithLifecycle().value
            val disconnectAllRequested = disconnectAllConfirmationRequested
                .collectAsStateWithLifecycle().value
            val activeRemoteSessionCount = appContainer.sshSessionRepository.sessions
                .collectAsStateWithLifecycle().value
                .count { it.connectionState.requiresForegroundService() }
            LaunchedEffect(authorityState) {
                if (shouldResolveStartup(authorityState)) {
                    appContainer.resolveStartup()
                }
            }
            val visibleLockState = if (settings == null && lockState == AppLockUiState.Unlocked) {
                AppLockUiState.SettingsLoading
            } else {
                lockState
            }
            val shouldSecureWindow = shouldSecureMainWindow(
                settingsLoaded = settings != null,
                screenshotBlockingEnabled = settings?.screenshotBlockingEnabled == true,
                lockState = visibleLockState,
                authorityState = authorityState,
            )
            SideEffect {
                if (shouldSecureWindow) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            DisposableEffect(Unit) {
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            val themeSettings = settings ?: AppSettingsSerializer.defaultValue
            TerminalSpikeTheme(
                themeMode = themeSettings.themeMode.toAppThemeMode(),
                dynamicColorEnabled = themeSettings.dynamicColorEnabled,
                accentPreset = themeSettings.accentPreset,
            ) {
                val mainRoot = selectMainRoot(
                    settingsLoaded = settings != null,
                    lockState = visibleLockState,
                    authorityState = authorityState,
                )
                LaunchedEffect(mainRoot, disconnectAllRequested, activeRemoteSessionCount) {
                    if (
                        mainRoot == MainRoot.CONTENT &&
                        disconnectAllRequested &&
                        activeRemoteSessionCount == 0
                    ) {
                        disconnectAllConfirmationRequested.value = false
                    }
                }
                when (mainRoot) {
                    MainRoot.APP_LOCK -> AppLockGate(
                        state = visibleLockState,
                        onAuthenticate = ::retryAuthentication,
                        onRetrySettings = {
                            collectSettings(appContainer)
                            if (shouldRetryStartupFromSettingsGate(authorityState)) {
                                appContainer.resolveStartup()
                            }
                        },
                        onOpenSecuritySettings = ::openSecuritySettings,
                        onResetConfirmed = localAppDataResetter::requestReset,
                    )

                    MainRoot.STARTUP_RECOVERY -> StartupRecoveryGate(
                        state = authorityState,
                        onRetry = { appContainer.resolveStartup() },
                        onResetConfirmed = localAppDataResetter::requestReset,
                    )

                    MainRoot.CONTENT -> TerminalSpikeScreen(viewModel = viewModel)
                }
                if (
                    shouldShowDisconnectAllConfirmation(
                        requested = disconnectAllRequested,
                        root = mainRoot,
                        activeSessionCount = activeRemoteSessionCount,
                    )
                ) {
                    DisconnectAllSessionsConfirmation(
                        activeSessionCount = activeRemoteSessionCount,
                        onConfirm = {
                            val currentCount = appContainer.sshSessionRepository.sessions.value
                                .count { it.connectionState.requiresForegroundService() }
                            if (currentCount > 0) appContainer.sshSessionRepository.disconnectAll()
                            disconnectAllConfirmationRequested.value = false
                        },
                        onDismiss = { disconnectAllConfirmationRequested.value = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationAction(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_DISCONNECT_ALL_CONFIRMATION,
            disconnectAllConfirmationRequested.value,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val now = SystemClock.elapsedRealtime()
        if (::appLockAuthenticator.isInitialized) {
            appLockViewModel.controller.updateAvailability(appLockAuthenticator.availability(), now)
        }
        appLockViewModel.controller.onResume(now)
        applyWindowSecurity()
        authenticateIfRequired()
    }

    override fun onPause() {
        appLockViewModel.controller.onPause(SystemClock.elapsedRealtime(), isChangingConfigurations)
        applyWindowSecurity()
        super.onPause()
    }

    override fun onStop() {
        appLockViewModel.controller.onStop(SystemClock.elapsedRealtime(), isChangingConfigurations)
        applyWindowSecurity()
        super.onStop()
    }

    override fun onDestroy() {
        if (::appLockAuthenticator.isInitialized) appLockAuthenticator.cancel()
        super.onDestroy()
    }

    private fun collectSettings(container: AppContainer) {
        settingsJob?.cancel()
        currentSettings.value = null
        settingsJob = lifecycleScope.launch {
            try {
                container.settings.settings.collect { settings ->
                    currentSettings.value = settings
                    appLockViewModel.controller.updatePolicy(
                        updated = settings.toAppLockPolicy(),
                        updatedAvailability = appLockAuthenticator.availability(),
                        nowMillis = SystemClock.elapsedRealtime(),
                    )
                    if (!appLockViewModel.controller.isEnabled) appLockAuthenticator.cancel()
                    applyWindowSecurity()
                    authenticateIfRequired()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                appLockAuthenticator.cancel()
                currentSettings.value = null
                appLockViewModel.controller.onSettingsUnavailable()
                applyWindowSecurity()
            }
        }
    }

    private fun retryAuthentication() {
        val now = SystemClock.elapsedRealtime()
        appLockViewModel.controller.updateAvailability(appLockAuthenticator.availability(), now)
        authenticateIfRequired()
    }

    private fun authenticateIfRequired() {
        if (currentSettings.value == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val controller = appLockViewModel.controller
        if (!controller.beginAuthentication(SystemClock.elapsedRealtime())) return
        runCatching {
            appLockAuthenticator.authenticate { result ->
                val now = SystemClock.elapsedRealtime()
                when (result) {
                    AppLockAuthenticationResult.Succeeded -> controller.authenticationSucceeded(now)
                    AppLockAuthenticationResult.Cancelled -> controller.authenticationFailed(
                        AppLockAuthenticationFailure.CANCELLED,
                        now,
                    )
                    AppLockAuthenticationResult.Error -> controller.authenticationFailed(
                        AppLockAuthenticationFailure.ERROR,
                        now,
                    )
                    is AppLockAuthenticationResult.Unavailable -> {
                        controller.updateAvailability(result.availability, now)
                    }
                }
                applyWindowSecurity()
            }
        }.onFailure {
            controller.authenticationFailed(
                AppLockAuthenticationFailure.ERROR,
                SystemClock.elapsedRealtime(),
            )
            applyWindowSecurity()
        }
    }

    private fun openSecuritySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    private fun captureNotificationAction(source: Intent?) {
        if (source?.action != ACTION_CONFIRM_DISCONNECT_ALL) return
        disconnectAllConfirmationRequested.value = true
        setIntent(Intent(source).setAction(null))
    }

    private fun applyWindowSecurity() {
        val settings = currentSettings.value
        val authorityState = if (::appContainer.isInitialized) {
            appContainer.authoritativeData.state.value
        } else {
            AuthoritativeDataState.Checking
        }
        val shouldSecure = shouldSecureMainWindow(
            settingsLoaded = settings != null,
            screenshotBlockingEnabled = settings?.screenshotBlockingEnabled == true,
            lockState = appLockViewModel.controller.state.value,
            authorityState = authorityState,
        )
        if (shouldSecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        internal const val ACTION_CONFIRM_DISCONNECT_ALL =
            "com.yanjiyu.terminalspike.action.CONFIRM_DISCONNECT_ALL_REMOTE_TERMINALS"
        private const val STATE_DISCONNECT_ALL_CONFIRMATION = "disconnect_all_confirmation"
    }
}

@androidx.compose.runtime.Composable
internal fun DisconnectAllSessionsConfirmation(
    activeSessionCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_disconnect_all_confirmation_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.session_disconnect_all_confirmation_message,
                    activeSessionCount,
                    activeSessionCount,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.session_disconnect_all_confirmation_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.session_disconnect_all_confirmation_cancel))
            }
        },
    )
}

internal enum class MainRoot {
    APP_LOCK,
    STARTUP_RECOVERY,
    CONTENT,
}

internal fun selectMainRoot(
    settingsLoaded: Boolean,
    lockState: AppLockUiState,
    authorityState: AuthoritativeDataState,
): MainRoot = when {
    !settingsLoaded || lockState != AppLockUiState.Unlocked -> MainRoot.APP_LOCK
    authorityState !is AuthoritativeDataState.Ready -> MainRoot.STARTUP_RECOVERY
    else -> MainRoot.CONTENT
}

internal fun shouldResolveStartup(authorityState: AuthoritativeDataState): Boolean =
    authorityState == AuthoritativeDataState.RecoveryRequired

internal fun shouldRetryStartupFromSettingsGate(
    authorityState: AuthoritativeDataState,
): Boolean = authorityState is AuthoritativeDataState.RecoveryFailed

internal fun shouldShowDisconnectAllConfirmation(
    requested: Boolean,
    root: MainRoot,
    activeSessionCount: Int,
): Boolean = requested && root == MainRoot.CONTENT && activeSessionCount > 0

internal fun shouldSecureMainWindow(
    settingsLoaded: Boolean,
    screenshotBlockingEnabled: Boolean,
    lockState: AppLockUiState,
    authorityState: AuthoritativeDataState,
): Boolean = !settingsLoaded ||
    screenshotBlockingEnabled ||
    lockState != AppLockUiState.Unlocked ||
    authorityState !is AuthoritativeDataState.Ready

private fun AppSettings.toAppLockPolicy(): AppLockPolicy = AppLockPolicy(
    mode = when (appLockMode) {
        AppSettings.AppLockMode.APP_LOCK_MODE_OFF -> AppLockMode.OFF
        AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE -> AppLockMode.IMMEDIATE
        AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED -> AppLockMode.DELAYED
        AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND -> AppLockMode.ON_BACKGROUND
        else -> error("Unsupported persisted app-lock mode: $appLockMode")
    },
    delayMillis = Integer.toUnsignedLong(appLockDelaySeconds) * 1_000L,
)

private fun AppSettings.ThemeMode.toAppThemeMode(): AppThemeMode = when (this) {
    AppSettings.ThemeMode.THEME_MODE_LIGHT -> AppThemeMode.LIGHT
    AppSettings.ThemeMode.THEME_MODE_DARK -> AppThemeMode.DARK
    else -> AppThemeMode.SYSTEM
}
