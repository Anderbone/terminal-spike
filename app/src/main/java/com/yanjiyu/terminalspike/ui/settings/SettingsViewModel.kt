package com.yanjiyu.terminalspike.ui.settings

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.migration.LegacyIds
import com.yanjiyu.terminalspike.core.data.settings.CURRENT_KEYBOARD_DECK_REVISION
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class SettingsProfileTargets(
    val terminalProfileId: String? = null,
    val keyboardProfileId: String? = null,
)

internal fun <T> resolveSettingsProfile(
    profiles: List<T>,
    requestedId: String?,
    defaultId: String,
    idOf: (T) -> String,
): T? = requestedId?.let { target -> profiles.firstOrNull { idOf(it) == target } }
    ?: profiles.firstOrNull { idOf(it) == defaultId }
    ?: profiles.firstOrNull()

internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as TerminalSpikeApplication).container
    private val fontStore = container.customTerminalFonts
    private val mutationMutex = Mutex()
    private val pendingWrites = AtomicInteger(0)
    private val _uiState = MutableStateFlow(SettingsUiState())
    private val profileTargets = MutableStateFlow(SettingsProfileTargets())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val _savedCredentialClearState =
        MutableStateFlow<SavedCredentialClearUiState>(SavedCredentialClearUiState.Idle)
    val savedCredentialClearState: StateFlow<SavedCredentialClearUiState> =
        _savedCredentialClearState.asStateFlow()
    private val backupWorkflow = SettingsBackupWorkflow(
        gateway = AndroidSettingsBackupGateway(
            context = application,
            transfers = container.backupTransfers,
            imports = container.backupImports,
        ),
        scope = viewModelScope,
    )
    val backupUiState: StateFlow<BackupWorkflowUiState> = backupWorkflow.state
    val backupDocumentRequests = backupWorkflow.documentRequests

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(importedFonts = fontStore.list())
            try {
                container.startLegacyMigrationForCutover().await()
                repairShippedKeyboardDeckOnce()
                combine(
                    container.settings.settings,
                    container.terminalProfiles.observeAll(),
                    container.keyboardProfiles.observeAll(),
                    container.customTerminalThemes.observeAll(),
                    profileTargets,
                ) { settings, terminalProfiles, keyboardProfiles, customThemes, targets ->
                    SettingsUiState(
                        preferences = settings.toPreferences(),
                        terminalProfile = resolveSettingsProfile(
                            profiles = terminalProfiles,
                            requestedId = targets.terminalProfileId,
                            defaultId = settings.defaultTerminalProfileId,
                            idOf = TerminalProfile::id,
                        ),
                        keyboardProfile = resolveSettingsProfile(
                            profiles = keyboardProfiles,
                            requestedId = targets.keyboardProfileId,
                            defaultId = settings.defaultKeyboardProfileId,
                            idOf = KeyboardProfile::id,
                        ),
                        importedFonts = fontStore.list(),
                        customTerminalThemes = customThemes,
                        profilesLoading = false,
                        writeInProgress = pendingWrites.get() > 0,
                        message = _uiState.value.message,
                    )
                }.collect { _uiState.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(
                    profilesLoading = false,
                    message = R.string.settings_profiles_unavailable,
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun selectProfileTargets(terminalProfileId: String?, keyboardProfileId: String?) {
        profileTargets.value = SettingsProfileTargets(terminalProfileId, keyboardProfileId)
    }

    fun beginBackupExport() = backupWorkflow.beginExport()

    /** Transfers mutable passphrase ownership to the backup workflow. */
    fun requestBackupExportDocument(
        mode: com.yanjiyu.terminalspike.core.backup.BackupMode,
        includeCustomFonts: Boolean,
        passphrase: CharArray,
    ) = backupWorkflow.requestExportDocument(mode, includeCustomFonts, passphrase)

    fun onBackupExportDocumentSelected(uri: Uri?) = backupWorkflow.onExportDocumentResult(
        uri?.let { BackupDocumentReference(it.toString()) },
    )

    fun requestBackupRestoreDocument() = backupWorkflow.requestRestoreDocument()

    fun onBackupRestoreDocumentSelected(uri: Uri?) = backupWorkflow.onRestoreDocumentResult(
        uri?.let { BackupDocumentReference(it.toString()) },
    )

    /** Transfers mutable passphrase ownership to the backup workflow. */
    fun unlockSelectedBackup(passphrase: CharArray) = backupWorkflow.unlockSelectedBackup(passphrase)

    fun selectBackupImportStrategy(
        strategy: com.yanjiyu.terminalspike.core.backup.BackupImportStrategy,
    ) = backupWorkflow.selectImportStrategy(strategy)

    fun prepareBackupImportPreview() = backupWorkflow.prepareImportPreview()

    fun applyPreparedBackupImport() = backupWorkflow.applyPreparedImport()

    fun recoverPendingBackupImport() = backupWorkflow.recoverPendingImport()

    fun dismissBackupWorkflow() = backupWorkflow.dismiss()

    fun setAppearanceMode(mode: AppearanceMode) = updateGlobal { it.setThemeMode(mode.toProto()) }

    fun setDynamicColor(enabled: Boolean) = updateGlobal { it.setDynamicColorEnabled(enabled) }

    fun setAccentPreset(preset: AccentPreset) = updateGlobal { it.setAccentPreset(preset.wireCode) }

    fun setKeepScreenOn(enabled: Boolean) =
        updateGlobal { it.setKeepScreenOnWhileTerminalVisible(enabled) }

    fun setKeepaliveInterval(seconds: Int) =
        updateGlobal { it.setKeepaliveIntervalSeconds(seconds) }

    fun setReconnectEnabled(enabled: Boolean) =
        updateGlobal { it.setReconnectEnabled(enabled) }

    fun setReconnectMaxAttempts(attempts: Int) =
        updateGlobal { it.setReconnectMaxAttempts(attempts) }

    fun setTmuxSessionSelectorEnabled(enabled: Boolean) =
        updateGlobal { it.setTmuxSessionSelectorDisabled(!enabled) }

    fun setKeepCpuAwake(enabled: Boolean) =
        updateGlobal { it.setKeepCpuAwake(enabled) }

    fun setNotificationPrivacy(enabled: Boolean) =
        updateGlobal { it.setNotificationPrivacyEnabled(enabled) }

    fun setDisconnectNotifications(enabled: Boolean) =
        updateGlobal { it.setDisconnectNotificationsEnabled(enabled) }

    fun setReconnectNotifications(enabled: Boolean) =
        updateGlobal { it.setReconnectNotificationsEnabled(enabled) }

    fun setScreenshotBlocking(enabled: Boolean) =
        updateGlobal { it.setScreenshotBlockingEnabled(enabled) }

    fun setSensitiveClipboardClear(preset: SensitiveClipboardClearPreset) =
        updateGlobal { it.setSensitiveClipboardClearSeconds(preset.seconds) }

    fun beginClearAllSavedCredentials() {
        if (
            _savedCredentialClearState.value !is SavedCredentialClearUiState.Idle &&
            _savedCredentialClearState.value !is SavedCredentialClearUiState.PreviewFailed
        ) {
            return
        }
        _savedCredentialClearState.value = SavedCredentialClearUiState.LoadingPreview
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val preview = container.terminalDataRepository.previewClearAllSavedCredentials()
                _savedCredentialClearState.value = if (
                    preview.credentialCount == 0 && preview.keyIdentityCount == 0
                ) {
                    SavedCredentialClearUiState.Completed(0, 0)
                } else {
                    SavedCredentialClearUiState.Confirming(
                        credentialCount = preview.credentialCount,
                        keyIdentityCount = preview.keyIdentityCount,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _savedCredentialClearState.value = SavedCredentialClearUiState.PreviewFailed
            }
        }
    }

    fun confirmClearAllSavedCredentials(confirmation: String) {
        if (confirmation != CLEAR_SAVED_CREDENTIALS_CONFIRMATION) return
        val current = _savedCredentialClearState.value as? SavedCredentialClearUiState.Confirming
            ?: return
        if (current.clearing) return
        _savedCredentialClearState.value = current.copy(clearing = true, failed = false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = container.terminalDataRepository.clearAllSavedCredentials()
                _savedCredentialClearState.value = SavedCredentialClearUiState.Completed(
                    credentialsDeleted = outcome.cleared.credentialMetadataDeleted,
                    keyIdentitiesDeleted = outcome.cleared.keyIdentitiesDeleted,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _savedCredentialClearState.value = current.copy(clearing = false, failed = true)
            }
        }
    }

    fun dismissClearAllSavedCredentials() {
        val current = _savedCredentialClearState.value
        if (current is SavedCredentialClearUiState.Confirming && current.clearing) return
        _savedCredentialClearState.value = SavedCredentialClearUiState.Idle
    }

    fun setAppLockMode(mode: AppLockModeOption) = updateGlobal {
        it.setAppLockMode(mode.toProto())
        if (mode == AppLockModeOption.DELAYED && it.appLockDelaySeconds == 0) {
            it.setAppLockDelaySeconds(DEFAULT_APP_LOCK_DELAY_SECONDS)
        }
    }

    fun setAppLockDelay(seconds: Int) = updateGlobal { it.setAppLockDelaySeconds(seconds) }

    fun setMultilinePasteConfirmation(enabled: Boolean) =
        updateGlobal { it.setMultilinePasteConfirmationEnabled(enabled) }

    fun setVoiceInputLanguage(language: VoiceInputLanguage) =
        updateGlobal { it.setVoiceInputLanguageTag(language.languageTag) }

    fun setTerminalTheme(themeId: String) {
        val supported = TerminalThemes.presets.any { it.id == themeId } ||
            _uiState.value.customTerminalThemes.any { it.id == themeId }
        if (!supported) {
            viewModelScope.launch { publishMessage(R.string.settings_terminal_theme_unavailable) }
            return
        }
        updateTerminalProfile { it.copy(themeId = themeId) }
    }

    fun saveCustomTerminalTheme(draft: CustomTerminalThemeDraft) = mutate {
        val existing = draft.id?.let { id ->
            container.customTerminalThemes.get(id)
                ?: throw IllegalStateException("The custom terminal theme no longer exists.")
        }
        val now = System.currentTimeMillis()
        val theme = CustomTerminalTheme(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = draft.name,
            foregroundArgb = draft.foregroundArgb,
            backgroundArgb = draft.backgroundArgb,
            cursorArgb = draft.cursorArgb,
            selectionArgb = draft.selectionArgb,
            ansi16Argb = draft.ansi16Argb,
            boldUsesBrightColours = draft.boldUsesBrightColours,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = maxOf(now, existing?.createdAtEpochMillis ?: now),
        )
        if (existing == null) {
            container.customTerminalThemes.insert(theme)
        } else {
            check(container.customTerminalThemes.update(theme)) {
                "The custom terminal theme could not be updated."
            }
        }
        updateTerminalProfileLocked { it.copy(themeId = theme.id) }
        publishMessage(R.string.settings_terminal_theme_saved)
    }

    fun deleteCustomTerminalTheme(id: String) = mutate {
        check(
            container.customTerminalThemes.deleteAndResetProfiles(
                id = id,
                fallbackThemeId = TerminalThemes.CURRENT_ID,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        ) { "The custom terminal theme could not be deleted." }
        publishMessage(R.string.settings_terminal_theme_deleted)
    }

    fun setTerminalFont(fontId: String) {
        val supported = TerminalRendererProfile.isBundledFontId(fontId) || fontStore.resolve(fontId) != null
        if (!supported) {
            viewModelScope.launch { publishMessage(R.string.settings_font_unavailable) }
            return
        }
        updateTerminalProfile { it.copy(fontId = fontId) }
    }

    fun importTerminalFont(uri: Uri) = mutate {
        val imported = fontStore.import(uri)
        updateTerminalProfileLocked { it.copy(fontId = imported.id) }
        withContext(Dispatchers.Main.immediate) {
            _uiState.value = _uiState.value.copy(
                importedFonts = fontStore.list(),
                message = R.string.settings_font_imported,
            )
        }
    }

    fun updateFontSize(value: Float) = updateTerminalProfile { it.copy(fontSizeSp = value) }

    fun updateLineHeight(value: Float) =
        updateTerminalProfile { it.copy(lineHeightMultiplier = value) }

    fun updateLetterSpacing(value: Float) =
        updateTerminalProfile { it.copy(letterSpacingEm = value) }

    fun updateBoldRendering(enabled: Boolean) =
        updateTerminalProfile { it.copy(boldRenderingEnabled = enabled) }

    fun updateLigatures(enabled: Boolean) =
        updateTerminalProfile { it.copy(ligaturesEnabled = enabled) }

    fun updatePinchZoom(enabled: Boolean) =
        updateTerminalProfile { it.copy(pinchZoomEnabled = enabled) }

    fun resetAppearanceProfile() = updateTerminalProfile {
        it.copy(
            themeId = TerminalThemes.CURRENT_ID,
            fontId = DEFAULT_FONT_ID,
            fontSizeSp = DEFAULT_FONT_SIZE_SP,
            lineHeightMultiplier = DEFAULT_LINE_HEIGHT,
            letterSpacingEm = DEFAULT_LETTER_SPACING,
            boldRenderingEnabled = true,
            ligaturesEnabled = false,
            pinchZoomEnabled = true,
        )
    }

    fun updateScrollback(lines: Int) = updateTerminalProfile { it.copy(scrollbackLines = lines) }

    fun updateCursorStyle(style: CursorStyle) = updateTerminalProfile { it.copy(cursorStyle = style) }

    fun updateCursorBlink(enabled: Boolean) =
        updateTerminalProfile { it.copy(cursorBlinkEnabled = enabled) }

    fun updateBell(transform: (BellSettings) -> BellSettings) =
        updateTerminalProfile { it.copy(bell = transform(it.bell)) }

    fun updateLinks(transform: (LinkBehavior) -> LinkBehavior) = mutate {
        val updated = updateTerminalProfileLocked { profile ->
            profile.copy(links = transform(profile.links))
        }
        container.settings.update {
            // Keep the security/global policy aligned with the selected default profile.
            it.setOsc52Policy(updated.links.remoteClipboardMode.toProto())
        }
    }

    fun updateScrollBehavior(transform: (ScrollBehavior) -> ScrollBehavior) =
        updateTerminalProfile { it.copy(scroll = transform(it.scroll)) }

    fun updateTermValue(value: String) = updateTerminalProfile { it.copy(termValue = value) }

    fun updateAlternateHistory(enabled: Boolean) =
        updateTerminalProfile { it.copy(preserveAlternateScreenHistory = enabled) }

    fun resetTerminalBehavior() = mutate {
        val updated = updateTerminalProfileLocked {
            it.copy(
                cursorStyle = CursorStyle.BLOCK,
                cursorBlinkEnabled = true,
                scrollbackLines = DEFAULT_SCROLLBACK_LINES,
                bell = BellSettings(),
                scroll = ScrollBehavior(),
                links = LinkBehavior(remoteClipboardMode = RemoteClipboardMode.ASK),
                termValue = TerminalProfile.DEFAULT_TERM_VALUE,
                preserveAlternateScreenHistory = true,
            )
        }
        container.settings.update { it.setOsc52Policy(updated.links.remoteClipboardMode.toProto()) }
    }

    fun applyKeyboardPreset(presetId: String) {
        val preset = KeyboardPresets.selectable.firstOrNull { it.id == presetId } ?: return
        updateKeyboardProfile { current ->
            current.copy(
                orderedActions = preset.actions,
                layout = preset.layout,
                modifierBehavior = preset.modifierBehavior,
                hapticFeedbackEnabled = preset.hapticFeedbackEnabled,
                keyRepeatEnabled = preset.keyRepeatEnabled,
                inputMode = preset.inputMode,
                tmuxPrefix = preset.tmuxPrefix,
            )
        }
    }

    fun updateKeyboardActions(actions: List<KeyboardAction>) =
        updateKeyboardProfile { it.copy(orderedActions = actions) }

    fun updateKeyboardLayout(layout: KeyboardLayout) =
        updateKeyboardProfile { it.copy(layout = layout) }

    fun updateModifierBehavior(behavior: ModifierBehavior) =
        updateKeyboardProfile { it.copy(modifierBehavior = behavior) }

    fun updateKeyboardHaptics(enabled: Boolean) =
        updateKeyboardProfile { it.copy(hapticFeedbackEnabled = enabled) }

    fun updateKeyRepeat(enabled: Boolean) =
        updateKeyboardProfile { it.copy(keyRepeatEnabled = enabled) }

    fun updateInputMode(mode: TerminalInputMode) =
        updateKeyboardProfile { it.copy(inputMode = mode) }

    fun updateTmuxPrefix(value: String) = updateKeyboardProfile { it.copy(tmuxPrefix = value) }

    fun resetKeyboardProfile() = applyKeyboardPreset(KeyboardPresets.general.id)

    override fun onCleared() {
        backupWorkflow.close()
        super.onCleared()
    }

    private fun updateGlobal(transform: (AppSettings.Builder) -> Unit) = mutate {
        container.settings.update(transform)
    }

    private suspend fun repairShippedKeyboardDeckOnce() {
        val settings = container.settings.settings.first()
        val plan = planKeyboardDeckMigration(
            completedRevision = settings.keyboardDeckRevision,
            canonicalProfile = container.keyboardProfiles.get(LegacyIds.defaultKeyboardProfile),
            nowEpochMillis = System.currentTimeMillis(),
        )
        if (!plan.shouldRecordCompletion) return
        plan.upgradedProfile?.let { upgraded ->
            check(container.keyboardProfiles.update(upgraded)) {
                "The shipped keyboard profile disappeared during its one-time repair."
            }
        }
        container.settings.update { current ->
            if (current.keyboardDeckRevision < CURRENT_KEYBOARD_DECK_REVISION) {
                current.setKeyboardDeckRevision(CURRENT_KEYBOARD_DECK_REVISION)
            }
        }
    }

    private fun updateTerminalProfile(transform: (TerminalProfile) -> TerminalProfile) = mutate {
        updateTerminalProfileLocked(transform)
    }

    private suspend fun updateTerminalProfileLocked(
        transform: (TerminalProfile) -> TerminalProfile,
    ): TerminalProfile {
        val id = _uiState.value.terminalProfile?.id ?: throw IllegalStateException("No terminal profile is available.")
        val current = container.terminalProfiles.get(id)
            ?: throw IllegalStateException("The selected terminal profile no longer exists.")
        val updated = transform(current).copy(updatedAtEpochMillis = System.currentTimeMillis())
        check(
            container.terminalProfiles.update(
                updated,
            ),
        ) { "The terminal profile could not be updated." }
        return updated
    }

    private fun updateKeyboardProfile(transform: (KeyboardProfile) -> KeyboardProfile) = mutate {
        val id = _uiState.value.keyboardProfile?.id
            ?: throw IllegalStateException("No keyboard profile is available.")
        val current = container.keyboardProfiles.get(id)
            ?: throw IllegalStateException("The selected keyboard profile no longer exists.")
        check(
            container.keyboardProfiles.update(
                transform(current).copy(updatedAtEpochMillis = System.currentTimeMillis()),
            ),
        ) { "The keyboard profile could not be updated." }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            pendingWrites.incrementAndGet()
            withContext(Dispatchers.Main.immediate) {
                _uiState.value = _uiState.value.copy(writeInProgress = true, message = null)
            }
            try {
                mutationMutex.withLock { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                publishMessage(R.string.settings_change_failed)
            } finally {
                val remaining = pendingWrites.decrementAndGet()
                withContext(Dispatchers.Main.immediate) {
                    _uiState.value = _uiState.value.copy(writeInProgress = remaining > 0)
                }
            }
        }
    }

    private suspend fun publishMessage(@StringRes message: Int) {
        withContext(Dispatchers.Main.immediate) {
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    companion object {
        const val SYSTEM_MONOSPACE_FONT_ID = TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID
        const val DEFAULT_FONT_ID = TerminalRendererProfile.DEFAULT_FONT_ID
        const val DEFAULT_FONT_SIZE_SP = 14f
        const val DEFAULT_LINE_HEIGHT = 1f
        const val DEFAULT_LETTER_SPACING = 0f
        const val DEFAULT_SCROLLBACK_LINES = 20_000
        const val DEFAULT_APP_LOCK_DELAY_SECONDS = 30

        val scrollbackPresets = listOf(1_000, 10_000, 20_000, 50_000, 100_000, 200_000)
        val appLockDelayPresets = listOf(15, 30, 60, 300)
    }
}

private fun RemoteClipboardMode.toProto(): AppSettings.Osc52Policy = when (this) {
    RemoteClipboardMode.DISABLED -> AppSettings.Osc52Policy.OSC52_POLICY_DISABLED
    RemoteClipboardMode.ASK -> AppSettings.Osc52Policy.OSC52_POLICY_ASK
}
