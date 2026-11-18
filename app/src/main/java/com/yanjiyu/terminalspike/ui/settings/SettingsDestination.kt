package com.yanjiyu.terminalspike.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardContentKind
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardRequest
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardWriter
import com.yanjiyu.terminalspike.ui.MoshExtensionUiState

internal data class SettingsActions(
    val onAppearanceMode: (AppearanceMode) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    val onAccent: (AccentPreset) -> Unit,
    val onTerminalTheme: (String) -> Unit,
    val onSaveCustomTheme: (CustomTerminalThemeDraft) -> Unit = {},
    val onDeleteCustomTheme: (String) -> Unit = {},
    val onTerminalFont: (String) -> Unit,
    val onImportFont: (Uri) -> Unit,
    val onFontSize: (Float) -> Unit,
    val onLineHeight: (Float) -> Unit,
    val onLetterSpacing: (Float) -> Unit,
    val onBoldRendering: (Boolean) -> Unit = {},
    val onLigatures: (Boolean) -> Unit = {},
    val onPinchZoom: (Boolean) -> Unit = {},
    val onResetAppearance: () -> Unit,
    val onScrollback: (Int) -> Unit,
    val onCursorStyle: (CursorStyle) -> Unit,
    val onCursorBlink: (Boolean) -> Unit,
    val onBell: ((BellSettings) -> BellSettings) -> Unit,
    val onLinks: ((LinkBehavior) -> LinkBehavior) -> Unit,
    val onScrollBehavior: ((ScrollBehavior) -> ScrollBehavior) -> Unit,
    val onTermValue: (String) -> Unit,
    val onAlternateHistory: (Boolean) -> Unit,
    val onMultilinePasteConfirmation: (Boolean) -> Unit,
    val onVoiceInputLanguage: (VoiceInputLanguage) -> Unit = {},
    val onSnippetsAboveKeyboard: (Boolean) -> Unit = {},
    val onInputKeyboardLayout: (String) -> Unit = {},
    val onInputButtonsOnRight: (Boolean) -> Unit = {},
    val onVoiceInputEnabled: (Boolean) -> Unit = {},
    val onResetTerminal: () -> Unit,
    val onKeyboardPreset: (String) -> Unit,
    val onKeyboardActions: (List<KeyboardAction>) -> Unit,
    val onKeyboardLayout: (KeyboardLayout) -> Unit,
    val onModifierBehavior: (ModifierBehavior) -> Unit,
    val onKeyboardHaptics: (Boolean) -> Unit,
    val onKeyRepeat: (Boolean) -> Unit,
    val onInputMode: (TerminalInputMode) -> Unit,
    val onTmuxPrefix: (String) -> Unit,
    val onCopyTmuxHelp: (String) -> Unit = {},
    val onResetKeyboard: () -> Unit,
    val onKeepaliveInterval: (Int) -> Unit = {},
    val onReconnectEnabled: (Boolean) -> Unit = {},
    val onReconnectMaxAttempts: (Int) -> Unit = {},
    val onTmuxSessionSelectorEnabled: (Boolean) -> Unit = {},
    val onAutoAttachSingleHerdrSession: (Boolean) -> Unit = {},
    val onKeepCpuAwake: (Boolean) -> Unit = {},
    val onNotificationPrivacy: (Boolean) -> Unit = {},
    val onDisconnectNotifications: (Boolean) -> Unit = {},
    val onReconnectNotifications: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit,
    val onAppLockMode: (AppLockModeOption) -> Unit,
    val onAppLockDelay: (Int) -> Unit,
    val onScreenshotBlocking: (Boolean) -> Unit,
    val onSensitiveClipboardClear: (SensitiveClipboardClearPreset) -> Unit = {},
    val onBeginClearSavedCredentials: () -> Unit = {},
    val onConfirmClearSavedCredentials: (String) -> Unit = {},
    val onDismissClearSavedCredentials: () -> Unit = {},
)

@Composable
internal fun SettingsDestination(
    initialCategory: SettingsCategory?,
    knownHosts: List<KnownHostSummary>,
    moshExtension: MoshExtensionUiState,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
    onRefreshMoshExtension: () -> Unit,
    onOpenRendererLab: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSshKeys: () -> Unit,
    onOpenSnippets: () -> Unit,
    snippetsContent: (@Composable () -> Unit)? = null,
    keysContent: (@Composable (String) -> Unit)? = null,
    onOpenPortForwards: () -> Unit = {},
    terminalProfileId: String? = null,
    keyboardProfileId: String? = null,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val backupState by settingsViewModel.backupUiState.collectAsStateWithLifecycle()
    val savedCredentialClearState by settingsViewModel.savedCredentialClearState
        .collectAsStateWithLifecycle()
    val context = LocalContext.current
    val terminalClipboardWriter = remember(context) {
        val scheduler = (context.applicationContext as? TerminalSpikeApplication)
            ?.container
            ?.terminalClipboardClearScheduler
        TerminalClipboardWriter(context, scheduler)
    }
    val tmuxClipboardLabel = stringResource(R.string.settings_tmux_clipboard_label)
    LaunchedEffect(settingsViewModel, terminalProfileId, keyboardProfileId) {
        settingsViewModel.selectProfileTargets(terminalProfileId, keyboardProfileId)
    }
    val createBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupDocumentContract.MIME_TYPE),
        settingsViewModel::onBackupExportDocumentSelected,
    )
    val openBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        settingsViewModel::onBackupRestoreDocumentSelected,
    )
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.backupDocumentRequests.collect { request ->
            when (request) {
                is BackupDocumentRequest.Create -> runCatching {
                    createBackupDocument.launch(request.suggestedFileName)
                }.onFailure {
                    settingsViewModel.onBackupExportDocumentSelected(null)
                }
                BackupDocumentRequest.Open -> runCatching {
                    openBackupDocument.launch(
                        arrayOf(
                            BackupDocumentContract.MIME_TYPE,
                            BackupDocumentContract.FALLBACK_MIME_TYPE,
                        ),
                    )
                }.onFailure {
                    settingsViewModel.onBackupRestoreDocumentSelected(null)
                }
            }
        }
    }
    val backupActions = BackupSettingsActions(
        onBeginExport = settingsViewModel::beginBackupExport,
        onBeginRestore = settingsViewModel::requestBackupRestoreDocument,
        onApplyImport = settingsViewModel::applyPreparedBackupImport,
        onRecover = settingsViewModel::recoverPendingBackupImport,
        onDismiss = settingsViewModel::dismissBackupWorkflow,
    )
    SettingsScreen(
        state = state,
        initialCategory = initialCategory,
        knownHosts = knownHosts,
        moshExtension = moshExtension,
        actions = SettingsActions(
            onAppearanceMode = settingsViewModel::setAppearanceMode,
            onDynamicColor = settingsViewModel::setDynamicColor,
            onAccent = settingsViewModel::setAccentPreset,
            onTerminalTheme = settingsViewModel::setTerminalTheme,
            onSaveCustomTheme = settingsViewModel::saveCustomTerminalTheme,
            onDeleteCustomTheme = settingsViewModel::deleteCustomTerminalTheme,
            onTerminalFont = settingsViewModel::setTerminalFont,
            onImportFont = settingsViewModel::importTerminalFont,
            onFontSize = settingsViewModel::updateFontSize,
            onLineHeight = settingsViewModel::updateLineHeight,
            onLetterSpacing = settingsViewModel::updateLetterSpacing,
            onBoldRendering = settingsViewModel::updateBoldRendering,
            onLigatures = settingsViewModel::updateLigatures,
            onPinchZoom = settingsViewModel::updatePinchZoom,
            onResetAppearance = settingsViewModel::resetAppearanceProfile,
            onScrollback = settingsViewModel::updateScrollback,
            onCursorStyle = settingsViewModel::updateCursorStyle,
            onCursorBlink = settingsViewModel::updateCursorBlink,
            onBell = settingsViewModel::updateBell,
            onLinks = settingsViewModel::updateLinks,
            onScrollBehavior = settingsViewModel::updateScrollBehavior,
            onTermValue = settingsViewModel::updateTermValue,
            onAlternateHistory = settingsViewModel::updateAlternateHistory,
            onMultilinePasteConfirmation = settingsViewModel::setMultilinePasteConfirmation,
            onVoiceInputLanguage = settingsViewModel::setVoiceInputLanguage,
            onSnippetsAboveKeyboard = settingsViewModel::setSnippetsAboveKeyboard,
            onInputKeyboardLayout = settingsViewModel::setInputKeyboardLayout,
            onInputButtonsOnRight = settingsViewModel::setInputButtonsOnRight,
            onVoiceInputEnabled = settingsViewModel::setVoiceInputEnabled,
            onResetTerminal = settingsViewModel::resetTerminalBehavior,
            onKeyboardPreset = settingsViewModel::applyKeyboardPreset,
            onKeyboardActions = settingsViewModel::updateKeyboardActions,
            onKeyboardLayout = settingsViewModel::updateKeyboardLayout,
            onModifierBehavior = settingsViewModel::updateModifierBehavior,
            onKeyboardHaptics = settingsViewModel::updateKeyboardHaptics,
            onKeyRepeat = settingsViewModel::updateKeyRepeat,
            onInputMode = settingsViewModel::updateInputMode,
            onTmuxPrefix = settingsViewModel::updateTmuxPrefix,
            onCopyTmuxHelp = { command ->
                terminalClipboardWriter.write(
                    TerminalClipboardRequest(
                        label = tmuxClipboardLabel,
                        text = command,
                        kind = TerminalClipboardContentKind.SELECTION,
                    ),
                )
            },
            onResetKeyboard = settingsViewModel::resetKeyboardProfile,
            onKeepaliveInterval = settingsViewModel::setKeepaliveInterval,
            onReconnectEnabled = settingsViewModel::setReconnectEnabled,
            onReconnectMaxAttempts = settingsViewModel::setReconnectMaxAttempts,
            onTmuxSessionSelectorEnabled = settingsViewModel::setTmuxSessionSelectorEnabled,
            onAutoAttachSingleHerdrSession = settingsViewModel::setAutoAttachSingleHerdrSession,
            onKeepCpuAwake = settingsViewModel::setKeepCpuAwake,
            onNotificationPrivacy = settingsViewModel::setNotificationPrivacy,
            onDisconnectNotifications = settingsViewModel::setDisconnectNotifications,
            onReconnectNotifications = settingsViewModel::setReconnectNotifications,
            onKeepScreenOn = settingsViewModel::setKeepScreenOn,
            onAppLockMode = settingsViewModel::setAppLockMode,
            onAppLockDelay = settingsViewModel::setAppLockDelay,
            onScreenshotBlocking = settingsViewModel::setScreenshotBlocking,
            onSensitiveClipboardClear = settingsViewModel::setSensitiveClipboardClear,
            onBeginClearSavedCredentials = settingsViewModel::beginClearAllSavedCredentials,
            onConfirmClearSavedCredentials = settingsViewModel::confirmClearAllSavedCredentials,
            onDismissClearSavedCredentials = settingsViewModel::dismissClearAllSavedCredentials,
        ),
        onForgetKnownHost = onForgetKnownHost,
        onRefreshMoshExtension = onRefreshMoshExtension,
        onOpenRendererLab = onOpenRendererLab,
        onNavigateBack = onNavigateBack,
        onOpenWorkspace = onOpenWorkspace,
        onOpenTerminal = onOpenTerminal,
        onOpenSettings = onOpenSettings,
        onOpenSshKeys = onOpenSshKeys,
        onOpenSnippets = onOpenSnippets,
        snippetsContent = snippetsContent,
        keysContent = keysContent,
        onOpenPortForwards = onOpenPortForwards,
        onDismissMessage = settingsViewModel::consumeMessage,
        backupState = backupState,
        backupActions = backupActions,
        savedCredentialClearState = savedCredentialClearState,
    )
}
