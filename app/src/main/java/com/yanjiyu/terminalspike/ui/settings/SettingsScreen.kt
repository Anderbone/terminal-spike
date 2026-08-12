package com.yanjiyu.terminalspike.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.core.security.applock.AndroidAppLockAuthenticator
import com.yanjiyu.terminalspike.core.security.applock.AppLockAvailability
import com.yanjiyu.terminalspike.settings.ImportedTerminalFont
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalTheme
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import com.yanjiyu.terminalspike.terminal.model.toTerminalTheme
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardContentKind
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardRequest
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardWriter
import com.yanjiyu.terminalspike.terminal.view.TerminalTypefaceRegistry
import com.yanjiyu.terminalspike.ui.AboutSection
import com.yanjiyu.terminalspike.ui.AdaptivePrimaryNavigation
import com.yanjiyu.terminalspike.ui.AppDestination
import com.yanjiyu.terminalspike.ui.CompactToolTabsTestTag
import com.yanjiyu.terminalspike.ui.DeveloperSettingsSection
import com.yanjiyu.terminalspike.ui.ExpandedToolDetailTestTag
import com.yanjiyu.terminalspike.ui.ExpandedToolSectionListTestTag
import com.yanjiyu.terminalspike.ui.MoshExtensionSection
import com.yanjiyu.terminalspike.ui.MoshExtensionUiState
import com.yanjiyu.terminalspike.ui.resolve
import kotlin.math.roundToInt

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
        onSubmitExport = settingsViewModel::requestBackupExportDocument,
        onBeginRestore = settingsViewModel::requestBackupRestoreDocument,
        onSubmitRestorePassphrase = settingsViewModel::unlockSelectedBackup,
        onSelectStrategy = settingsViewModel::selectBackupImportStrategy,
        onPrepareImport = settingsViewModel::prepareBackupImportPreview,
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
        onDismissMessage = settingsViewModel::consumeMessage,
        backupState = backupState,
        backupActions = backupActions,
        savedCredentialClearState = savedCredentialClearState,
    )
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    initialCategory: SettingsCategory?,
    knownHosts: List<KnownHostSummary>,
    moshExtension: MoshExtensionUiState,
    actions: SettingsActions,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
    onRefreshMoshExtension: () -> Unit,
    onOpenRendererLab: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    backupState: BackupWorkflowUiState = BackupWorkflowUiState(),
    backupActions: BackupSettingsActions = BackupSettingsActions.NONE,
    savedCredentialClearState: SavedCredentialClearUiState = SavedCredentialClearUiState.Idle,
) {
    var selectedCategory by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingKnownHost by remember { mutableStateOf<KnownHostSummary?>(null) }
    val categories = settingsCategoriesForSearch(query, includeDeveloper = BuildConfig.DEBUG)
    LaunchedEffect(categories) {
        if (selectedCategory != null && selectedCategory !in categories && categories.isNotEmpty()) {
            selectedCategory = categories.first()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdaptivePrimaryNavigation(
            selected = AppDestination.SETTINGS,
            onWorkspace = onOpenWorkspace,
            onConnections = onOpenTerminal,
            onSettings = onOpenSettings,
            modifier = Modifier.fillMaxSize(),
        ) { contentModifier, expanded ->
            Column(modifier = contentModifier.fillMaxSize()) {
                SettingsTopBar(
                    category = selectedCategory,
                    state = state,
                    expanded = expanded,
                    onBack = {
                        if (!expanded && selectedCategory != null) selectedCategory = null else onNavigateBack()
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (expanded) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SettingsCategoryList(
                            categories = categories,
                            state = state,
                            selected = selectedCategory ?: SettingsCategory.APPEARANCE,
                            query = query,
                            onQueryChange = { query = it },
                            onSelected = { selectedCategory = it },
                            modifier = Modifier
                                .width(276.dp)
                                .fillMaxHeight()
                                .testTag(ExpandedToolSectionListTestTag),
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        SettingsDetail(
                            category = selectedCategory ?: SettingsCategory.APPEARANCE,
                            state = state,
                            knownHosts = knownHosts,
                            moshExtension = moshExtension,
                            actions = actions,
                            onForgetKnownHost = { pendingKnownHost = it },
                            onRefreshMoshExtension = onRefreshMoshExtension,
                            onOpenRendererLab = onOpenRendererLab,
                            backupState = backupState,
                            backupActions = backupActions,
                            savedCredentialClearState = savedCredentialClearState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(ExpandedToolDetailTestTag),
                        )
                    }
                } else if (selectedCategory == null) {
                    SettingsCategoryList(
                        categories = categories,
                        state = state,
                        selected = null,
                        query = query,
                        onQueryChange = { query = it },
                        onSelected = { selectedCategory = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SettingsDetail(
                        category = requireNotNull(selectedCategory),
                        state = state,
                        knownHosts = knownHosts,
                        moshExtension = moshExtension,
                        actions = actions,
                        onForgetKnownHost = { pendingKnownHost = it },
                        onRefreshMoshExtension = onRefreshMoshExtension,
                        onOpenRendererLab = onOpenRendererLab,
                        backupState = backupState,
                        backupActions = backupActions,
                        savedCredentialClearState = savedCredentialClearState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    state.message?.let { messageRes ->
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                action = {
                    TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.dismiss)) }
                },
            ) { Text(stringResource(messageRes)) }
        }
    }
    pendingKnownHost?.let { knownHost ->
        AlertDialog(
            onDismissRequest = { pendingKnownHost = null },
            title = { Text(stringResource(R.string.settings_forget_known_host_title, knownHost.host)) },
            text = { Text(stringResource(R.string.settings_forget_known_host_message)) },
            confirmButton = {
                Button(onClick = {
                    onForgetKnownHost(knownHost.host, knownHost.algorithm)
                    pendingKnownHost = null
                }) { Text(stringResource(R.string.settings_forget_known_host_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingKnownHost = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    BackupWorkflowDialogs(backupState, backupActions)
}

@Composable
private fun SettingsTopBar(
    category: SettingsCategory?,
    state: SettingsUiState,
    expanded: Boolean,
    onBack: () -> Unit,
) {
    val backDescription = stringResource(R.string.settings_back_description)
    val screenDescription = stringResource(R.string.settings_screen_description)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backDescription },
        ) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = if (!expanded && category != null) stringResource(category.titleRes) else stringResource(R.string.settings_title),
                modifier = Modifier.semantics { contentDescription = screenDescription },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (!expanded && category != null) {
                    settingsCategorySummary(category, state).resolve()
                } else {
                    stringResource(R.string.settings_subtitle)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsCategoryList(
    categories: List<SettingsCategory>,
    state: SettingsUiState,
    selected: SettingsCategory?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelected: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clearDescription = stringResource(R.string.settings_clear_search)
    val listDescription = stringResource(R.string.settings_category_list_description)
    LazyColumn(
        modifier = modifier.semantics {
            contentDescription = listDescription
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { onQueryChange(it.take(80)) },
                modifier = Modifier.fillMaxWidth().testTag(SettingsSearchTestTag),
                label = { Text(stringResource(R.string.settings_search_label)) },
                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleMedium) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.semantics {
                                contentDescription = clearDescription
                            },
                        ) { Text("×") }
                    }
                } else {
                    null
                },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (categories.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.settings_no_results_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_no_results_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(categories, key = SettingsCategory::name) { category ->
            SettingsCategoryRow(
                category = category,
                state = state,
                selected = category == selected,
                onClick = { onSelected(category) },
            )
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    state: SettingsUiState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = category.mark,
                modifier = Modifier.width(34.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = settingsCategorySummary(category, state).resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private val SettingsCategory.mark: String
    get() = when (this) {
        SettingsCategory.APPEARANCE -> "Aa"
        SettingsCategory.TERMINAL -> ">_"
        SettingsCategory.KEYBOARD -> "⌨"
        SettingsCategory.SESSIONS_BACKGROUND -> "◉"
        SettingsCategory.NOTIFICATIONS -> "●"
        SettingsCategory.BACKUP_RESTORE -> "↕"
        SettingsCategory.SECURITY -> "◇"
        SettingsCategory.MOSH -> "M"
        SettingsCategory.ABOUT -> "i"
        SettingsCategory.DEVELOPER -> "{}"
    }

internal const val SettingsSearchTestTag = "settings-search"
internal const val SettingsCategoryListContentDescription = "Settings categories"

@Composable
private fun SettingsDetail(
    category: SettingsCategory,
    state: SettingsUiState,
    knownHosts: List<KnownHostSummary>,
    moshExtension: MoshExtensionUiState,
    actions: SettingsActions,
    onForgetKnownHost: (KnownHostSummary) -> Unit,
    onRefreshMoshExtension: () -> Unit,
    onOpenRendererLab: () -> Unit,
    backupState: BackupWorkflowUiState,
    backupActions: BackupSettingsActions,
    savedCredentialClearState: SavedCredentialClearUiState,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        when (category) {
            SettingsCategory.APPEARANCE -> AppearanceSettings(state, actions)
            SettingsCategory.TERMINAL -> TerminalSettings(state, actions)
            SettingsCategory.KEYBOARD -> KeyboardSettings(state, actions)
            SettingsCategory.SESSIONS_BACKGROUND -> SessionsBackgroundSettings(state, actions)
            SettingsCategory.NOTIFICATIONS -> NotificationSettings(state, actions)
            SettingsCategory.BACKUP_RESTORE -> BackupRestoreSettingsContent(backupState, backupActions)
            SettingsCategory.SECURITY -> SecuritySettings(
                state = state,
                knownHosts = knownHosts,
                onAppLockMode = actions.onAppLockMode,
                onAppLockDelay = actions.onAppLockDelay,
                onScreenshotBlocking = actions.onScreenshotBlocking,
                onSensitiveClipboardClear = actions.onSensitiveClipboardClear,
                savedCredentialClearState = savedCredentialClearState,
                onBeginClearSavedCredentials = actions.onBeginClearSavedCredentials,
                onConfirmClearSavedCredentials = actions.onConfirmClearSavedCredentials,
                onDismissClearSavedCredentials = actions.onDismissClearSavedCredentials,
                onForgetKnownHost = onForgetKnownHost,
            )
            SettingsCategory.MOSH -> MoshExtensionSection(
                state = moshExtension,
                onRefresh = onRefreshMoshExtension,
            )
            SettingsCategory.ABOUT -> AboutSection()
            SettingsCategory.DEVELOPER -> DeveloperSettingsSection(
                onOpenRendererLab = onOpenRendererLab,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.writeInProgress) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun AppearanceSettings(state: SettingsUiState, actions: SettingsActions) {
    val profile = state.terminalProfile
    val selectedCustomTheme = state.customTerminalThemes.firstOrNull { it.id == profile?.themeId }
    val selectedTheme = selectedCustomTheme?.toTerminalTheme()
        ?: TerminalThemes.find(profile?.themeId ?: TerminalThemes.CURRENT_ID)
    val themeChoices = TerminalThemes.presets + state.customTerminalThemes.map { it.toTerminalTheme() }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.onImportFont(uri)
    }
    var resetRequested by remember { mutableStateOf(false) }
    var themeEditorTarget by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(AppearanceSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_appearance, R.string.settings_appearance_intro) }
        item { SectionLabel(R.string.settings_app_appearance_heading) }
        item {
            HorizontalChoices {
                AppearanceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.preferences.appearanceMode == mode,
                        onClick = { actions.onAppearanceMode(mode) },
                        label = { Text(stringResource(mode.labelRes)) },
                    )
                }
            }
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_dynamic_colour),
                summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    stringResource(R.string.settings_dynamic_colour_summary)
                } else {
                    stringResource(R.string.settings_dynamic_colour_unavailable)
                },
                checked = state.preferences.dynamicColorEnabled,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = actions.onDynamicColor,
            )
        }
        item {
            Text(
                text = stringResource(R.string.settings_accent_heading),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalChoices {
                AccentPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.preferences.accentPreset == preset,
                        enabled = !state.preferences.dynamicColorEnabled,
                        onClick = { actions.onAccent(preset) },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }
        }
        item { SectionDivider() }
        item { SectionLabel(R.string.settings_terminal_theme_heading) }
        item {
            Text(
                text = stringResource(R.string.settings_terminal_theme_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalChoices {
                themeChoices.forEach { theme ->
                    ThemeChoice(theme, selected = theme.id == selectedTheme.id) {
                        actions.onTerminalTheme(theme.id)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { themeEditorTarget = NEW_CUSTOM_THEME_TARGET },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_terminal_theme_add)) }
                if (selectedCustomTheme != null) {
                    OutlinedButton(
                        onClick = { themeEditorTarget = selectedCustomTheme.id },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.settings_terminal_theme_edit)) }
                }
            }
        }
        item {
            TerminalProfilePreview(
                profile = profile,
                theme = selectedTheme,
                importedFont = state.importedFonts.firstOrNull { it.id == profile?.fontId },
            )
        }
        item { SectionLabel(R.string.settings_font_heading) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChoiceRow(
                    title = stringResource(R.string.settings_font_system_monospace),
                    fontId = TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID,
                    importedFont = null,
                    selected = profile?.fontId == SettingsViewModel.SYSTEM_MONOSPACE_FONT_ID,
                    onClick = { actions.onTerminalFont(SettingsViewModel.SYSTEM_MONOSPACE_FONT_ID) },
                )
                bundledFontChoices.forEach { choice ->
                    FontChoiceRow(
                        title = stringResource(choice.titleRes),
                        fontId = choice.id,
                        importedFont = null,
                        selected = profile?.fontId == choice.id,
                        onClick = { actions.onTerminalFont(choice.id) },
                    )
                }
                state.importedFonts.forEach { imported ->
                    FontChoiceRow(
                        title = imported.displayName,
                        fontId = imported.id,
                        importedFont = imported,
                        selected = profile?.fontId == imported.id,
                        onClick = { actions.onTerminalFont(imported.id) },
                    )
                }
                OutlinedButton(
                    onClick = {
                        importFont.launch(
                            arrayOf(
                                "font/ttf",
                                "font/otf",
                                "application/x-font-ttf",
                                "application/x-font-opentype",
                                "application/octet-stream",
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_import_font)) }
                Text(
                    text = stringResource(R.string.settings_import_font_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_font_nerd_fallback_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (profile != null) {
            item {
                ProfileSlider(
                    title = stringResource(R.string.settings_font_size),
                    value = profile.fontSizeSp,
                    range = 8f..32f,
                    steps = 23,
                    valueLabel = stringResource(R.string.settings_sp_value, profile.fontSizeSp),
                    onCommit = actions.onFontSize,
                )
            }
            item {
                ProfileSlider(
                    title = stringResource(R.string.settings_line_height),
                    value = profile.lineHeightMultiplier,
                    range = 0.8f..1.8f,
                    steps = 9,
                    valueLabel = String.format("%.2f×", profile.lineHeightMultiplier),
                    onCommit = actions.onLineHeight,
                )
            }
            item {
                ProfileSlider(
                    title = stringResource(R.string.settings_letter_spacing),
                    value = profile.letterSpacingEm,
                    range = -0.1f..0.2f,
                    steps = 29,
                    valueLabel = String.format("%.2f em", profile.letterSpacingEm),
                    onCommit = actions.onLetterSpacing,
                )
            }
            item {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_font_bold_rendering),
                    summary = stringResource(R.string.settings_font_bold_rendering_summary),
                    checked = profile.boldRenderingEnabled,
                    onCheckedChange = actions.onBoldRendering,
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_font_ligatures),
                    summary = stringResource(R.string.settings_font_ligatures_summary),
                    checked = profile.ligaturesEnabled,
                    onCheckedChange = actions.onLigatures,
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_font_pinch_zoom),
                    summary = stringResource(R.string.settings_font_pinch_zoom_summary),
                    checked = profile.pinchZoomEnabled,
                    onCheckedChange = actions.onPinchZoom,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.settings_preview_coverage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            TextButton(onClick = { resetRequested = true }) {
                Text(stringResource(R.string.settings_reset_appearance))
            }
        }
    }

    if (resetRequested) {
        ResetDialog(
            title = R.string.settings_reset_appearance_title,
            message = R.string.settings_reset_appearance_message,
            onDismiss = { resetRequested = false },
            onConfirm = {
                resetRequested = false
                actions.onResetAppearance()
            },
        )
    }

    themeEditorTarget?.let { target ->
        val initialTheme = if (target == NEW_CUSTOM_THEME_TARGET) {
            null
        } else {
            state.customTerminalThemes.firstOrNull { it.id == target }
        }
        if (target == NEW_CUSTOM_THEME_TARGET || initialTheme != null) {
            CustomTerminalThemeEditorDialog(
                initialTheme = initialTheme,
                onDismiss = { themeEditorTarget = null },
                onSave = { draft ->
                    themeEditorTarget = null
                    actions.onSaveCustomTheme(draft)
                },
                onDelete = { id ->
                    themeEditorTarget = null
                    actions.onDeleteCustomTheme(id)
                },
            )
        } else {
            LaunchedEffect(target) { themeEditorTarget = null }
        }
    }
}

@Composable
private fun ThemeChoice(theme: TerminalTheme, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Row {
                    theme.ansi16.take(3).forEach { colour ->
                        Box(Modifier.size(8.dp).background(Color(colour)))
                    }
                }
                Text(theme.displayName)
            }
        },
    )
}

@Composable
private fun FontChoiceRow(
    title: String,
    fontId: String,
    importedFont: ImportedTerminalFont?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val typeface = remember(fontId, importedFont?.id) {
        importedFont?.file?.let(TerminalTypefaceRegistry::register)
        TerminalTypefaceRegistry.resolve(
            context = context,
            fontId = fontId,
            customFontPath = importedFont?.file?.absolutePath,
        )?.normal ?: Typeface.MONOSPACE
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title)
                AndroidView(
                    factory = { previewContext ->
                        TextView(previewContext).apply {
                            textSize = 14f
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    },
                    update = { preview ->
                        preview.typeface = typeface
                        preview.text = FONT_CHOICE_PREVIEW
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun TerminalProfilePreview(
    profile: TerminalProfile?,
    theme: TerminalTheme,
    importedFont: ImportedTerminalFont?,
) {
    val context = LocalContext.current
    val typefaces = remember(profile?.fontId, importedFont?.id) {
        importedFont?.file?.let(TerminalTypefaceRegistry::register)
        TerminalTypefaceRegistry.resolve(
            context = context,
            fontId = profile?.fontId ?: TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID,
            customFontPath = importedFont?.file?.absolutePath,
        )
    }
    AndroidView(
        factory = { previewContext ->
            TextView(previewContext).apply {
                setPadding(
                    16.toDpPx(previewContext),
                    14.toDpPx(previewContext),
                    16.toDpPx(previewContext),
                    14.toDpPx(previewContext),
                )
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = previewContext.getString(R.string.settings_terminal_preview_description)
            }
        },
        update = { view ->
            view.typeface = typefaces?.normal ?: Typeface.MONOSPACE
            view.textSize = profile?.fontSizeSp ?: SettingsViewModel.DEFAULT_FONT_SIZE_SP
            view.letterSpacing = profile?.letterSpacingEm ?: 0f
            view.paint.fontFeatureSettings = if (profile?.ligaturesEnabled == true) {
                "'liga' 1, 'calt' 1"
            } else {
                "'liga' 0, 'calt' 0"
            }
            view.setLineSpacing(0f, profile?.lineHeightMultiplier ?: 1f)
            view.setTextColor(theme.foreground)
            view.setBackgroundColor(theme.background)
            view.text = styledPreviewText(
                context = context,
                boldRenderingEnabled = profile?.boldRenderingEnabled != false,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 184.dp)
            .clip(RoundedCornerShape(16.dp))
            .testTag(TerminalPreviewTestTag),
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp)) {
        theme.ansi16.chunked(8).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { colour -> Box(Modifier.weight(1f).height(8.dp).background(Color(colour))) }
            }
        }
    }
}

private fun styledPreviewText(context: Context, boldRenderingEnabled: Boolean): SpannableString {
    val plain = context.getString(R.string.settings_terminal_preview_content)
    return SpannableString(plain).apply {
        val boldStart = plain.indexOf("Bold")
        if (boldRenderingEnabled && boldStart >= 0) setSpan(
            StyleSpan(Typeface.BOLD),
            boldStart,
            boldStart + "Bold".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val italicStart = plain.indexOf("Italic")
        if (italicStart >= 0) setSpan(
            StyleSpan(Typeface.ITALIC),
            italicStart,
            italicStart + "Italic".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private fun Int.toDpPx(context: Context): Int = (this * context.resources.displayMetrics.density).roundToInt()

private data class BundledFontChoice(@StringRes val titleRes: Int, val id: String)

private val bundledFontChoices = listOf(
    BundledFontChoice(R.string.settings_font_source_code_pro, TerminalRendererProfile.SOURCE_CODE_PRO_FONT_ID),
    BundledFontChoice(R.string.settings_font_jetbrains_mono, TerminalRendererProfile.JETBRAINS_MONO_FONT_ID),
    BundledFontChoice(R.string.settings_font_ibm_plex_mono, TerminalRendererProfile.IBM_PLEX_MONO_FONT_ID),
    BundledFontChoice(R.string.settings_font_cascadia_mono, TerminalRendererProfile.CASCADIA_MONO_FONT_ID),
)

private const val FONT_CHOICE_PREVIEW = "Aa 01 != -> "

internal const val AppearanceSettingsTestTag = "appearance-settings"
internal const val TerminalPreviewTestTag = "terminal-profile-preview"
private const val NEW_CUSTOM_THEME_TARGET = "new_custom_theme"

@Composable
private fun TerminalSettings(state: SettingsUiState, actions: SettingsActions) {
    val profile = state.terminalProfile
    var customScrollback by remember(profile?.scrollbackLines) {
        mutableStateOf(profile?.scrollbackLines?.toString() ?: SettingsViewModel.DEFAULT_SCROLLBACK_LINES.toString())
    }
    var termDraft by remember(profile?.termValue) {
        mutableStateOf(profile?.termValue ?: TerminalProfile.DEFAULT_TERM_VALUE)
    }
    var resetRequested by remember { mutableStateOf(false) }
    val scrollbackValue = customScrollback.toIntOrNull()
    val validScrollback = scrollbackValue != null && scrollbackValue in 0..200_000
    val validTerm = termDraft.length in 1..64 && termDraft.all { it.isLetterOrDigit() || it in "._+-" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TerminalSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_terminal, R.string.settings_terminal_intro) }
        if (profile == null) {
            item { LoadingOrUnavailable(state.profilesLoading) }
        } else {
            item { SectionLabel(R.string.settings_scrollback_heading) }
            item {
                HorizontalChoices {
                    SettingsViewModel.scrollbackPresets.forEach { lines ->
                        FilterChip(
                            selected = profile.scrollbackLines == lines,
                            onClick = {
                                customScrollback = lines.toString()
                                actions.onScrollback(lines)
                            },
                            label = { Text(formatLineCount(lines)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customScrollback,
                        onValueChange = { customScrollback = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.settings_custom_lines)) },
                        supportingText = {
                            Text(
                                if (validScrollback) {
                                    stringResource(R.string.settings_scrollback_range)
                                } else {
                                    stringResource(R.string.settings_scrollback_invalid)
                                },
                            )
                        },
                        isError = !validScrollback,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        singleLine = true,
                    )
                    Button(
                        onClick = { actions.onScrollback(requireNotNull(scrollbackValue)) },
                        enabled = validScrollback && scrollbackValue != profile.scrollbackLines,
                    ) { Text(stringResource(R.string.apply)) }
                }
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_cursor_heading) }
            item {
                HorizontalChoices {
                    CursorStyle.entries.forEach { style ->
                        FilterChip(
                            selected = profile.cursorStyle == style,
                            onClick = { actions.onCursorStyle(style) },
                            label = { Text(cursorStyleLabel(style)) },
                        )
                    }
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_cursor_blink),
                    summary = stringResource(R.string.settings_cursor_blink_summary),
                    checked = profile.cursorBlinkEnabled,
                    onCheckedChange = actions.onCursorBlink,
                )
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_bell_heading) }
            item {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_visual_bell),
                    summary = stringResource(R.string.settings_visual_bell_summary),
                    checked = profile.bell.visualBellEnabled,
                    onCheckedChange = { enabled ->
                        actions.onBell { bell -> bell.copy(visualBellEnabled = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_vibration_bell),
                    summary = stringResource(R.string.settings_vibration_bell_summary),
                    checked = profile.bell.vibrationBellEnabled,
                    onCheckedChange = { enabled ->
                        actions.onBell { bell -> bell.copy(vibrationBellEnabled = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_audible_bell),
                    summary = stringResource(R.string.settings_audible_bell_summary),
                    checked = profile.bell.audibleBellEnabled,
                    onCheckedChange = { enabled ->
                        actions.onBell { bell -> bell.copy(audibleBellEnabled = enabled) }
                    },
                )
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_links_clipboard_heading) }
            item {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_url_detection),
                    summary = stringResource(R.string.settings_url_detection_summary),
                    checked = profile.links.detectPlainTextUrls,
                    onCheckedChange = { enabled ->
                        actions.onLinks { links -> links.copy(detectPlainTextUrls = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_osc8_links),
                    summary = stringResource(R.string.settings_osc8_links_summary),
                    checked = profile.links.osc8HyperlinksEnabled,
                    onCheckedChange = { enabled ->
                        actions.onLinks { links -> links.copy(osc8HyperlinksEnabled = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_terminal_copy_on_selection),
                    summary = stringResource(R.string.settings_terminal_copy_on_selection_summary),
                    checked = profile.links.copyOnSelection,
                    onCheckedChange = { enabled ->
                        actions.onLinks { links -> links.copy(copyOnSelection = enabled) }
                    },
                )
                Text(
                    text = stringResource(R.string.settings_osc52_heading),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_osc52_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalChoices {
                    RemoteClipboardMode.entries.forEach { mode ->
                        FilterChip(
                            selected = profile.links.remoteClipboardMode == mode,
                            onClick = {
                                actions.onLinks { links -> links.copy(remoteClipboardMode = mode) }
                            },
                            label = { Text(remoteClipboardLabel(mode)) },
                        )
                    }
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_multiline_paste_confirmation),
                    summary = stringResource(R.string.settings_multiline_paste_confirmation_summary),
                    checked = state.preferences.multilinePasteConfirmationEnabled,
                    onCheckedChange = actions.onMultilinePasteConfirmation,
                )
                ReadOnlySettingRow(
                    title = stringResource(R.string.settings_bracketed_paste),
                    summary = stringResource(R.string.settings_bracketed_paste_summary),
                    value = stringResource(R.string.settings_negotiated_automatically),
                )
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_scrolling_heading) }
            item {
                Text(
                    text = stringResource(R.string.settings_touch_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_touch_mode_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalChoices {
                    TouchScrollMode.entries.forEach { mode ->
                        FilterChip(
                            selected = profile.scroll.touchMode == mode,
                            onClick = {
                                actions.onScrollBehavior { scroll -> scroll.copy(touchMode = mode) }
                            },
                            label = { Text(touchModeLabel(mode)) },
                        )
                    }
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_two_finger_override),
                    summary = stringResource(R.string.settings_two_finger_override_summary),
                    checked = profile.scroll.twoFingerLocalScrollOverride,
                    onCheckedChange = { enabled ->
                        actions.onScrollBehavior { scroll -> scroll.copy(twoFingerLocalScrollOverride = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_jump_on_input),
                    summary = stringResource(R.string.settings_jump_on_input_summary),
                    checked = profile.scroll.jumpToBottomOnKeyboardInput,
                    onCheckedChange = { enabled ->
                        actions.onScrollBehavior { scroll -> scroll.copy(jumpToBottomOnKeyboardInput = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_keep_viewport),
                    summary = stringResource(R.string.settings_keep_viewport_summary),
                    checked = profile.scroll.keepViewportPositionOnOutput,
                    onCheckedChange = { enabled ->
                        actions.onScrollBehavior { scroll -> scroll.copy(keepViewportPositionOnOutput = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_alternate_history),
                    summary = stringResource(R.string.settings_alternate_history_summary),
                    checked = profile.preserveAlternateScreenHistory,
                    onCheckedChange = actions.onAlternateHistory,
                )
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_term_heading) }
            item {
                OutlinedTextField(
                    value = termDraft,
                    onValueChange = { termDraft = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TERM") },
                    isError = !validTerm,
                    supportingText = {
                        Text(
                            if (validTerm) stringResource(R.string.settings_term_summary)
                            else stringResource(R.string.settings_term_invalid),
                        )
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { actions.onTermValue(termDraft) },
                            enabled = validTerm && termDraft != profile.termValue,
                        ) { Text(stringResource(R.string.apply)) }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    text = stringResource(R.string.settings_tmux_term_guidance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                TextButton(onClick = { resetRequested = true }) {
                    Text(stringResource(R.string.settings_reset_terminal))
                }
            }
        }
    }

    if (resetRequested) {
        ResetDialog(
            title = R.string.settings_reset_terminal_title,
            message = R.string.settings_reset_terminal_message,
            onDismiss = { resetRequested = false },
            onConfirm = {
                resetRequested = false
                actions.onResetTerminal()
            },
        )
    }
}

private fun formatLineCount(lines: Int): String = when {
    lines >= 1_000 -> "${lines / 1_000}k"
    else -> lines.toString()
}

@Composable
private fun cursorStyleLabel(style: CursorStyle): String = when (style) {
    CursorStyle.BLOCK -> stringResource(R.string.settings_cursor_block)
    CursorStyle.UNDERLINE -> stringResource(R.string.settings_cursor_underline)
    CursorStyle.BEAM -> stringResource(R.string.settings_cursor_beam)
}

@Composable
private fun remoteClipboardLabel(mode: RemoteClipboardMode): String = when (mode) {
    RemoteClipboardMode.DISABLED -> stringResource(R.string.settings_disabled)
    RemoteClipboardMode.ASK -> stringResource(R.string.settings_ask)
}

@Composable
private fun touchModeLabel(mode: TouchScrollMode): String = when (mode) {
    TouchScrollMode.AUTO -> stringResource(R.string.settings_touch_auto)
    TouchScrollMode.LOCAL_SCROLLBACK -> stringResource(R.string.settings_touch_local)
    TouchScrollMode.REMOTE_MOUSE -> stringResource(R.string.settings_touch_remote)
}

internal const val TerminalSettingsTestTag = "terminal-settings"

@Composable
private fun KeyboardSettings(state: SettingsUiState, actions: SettingsActions) {
    val profile = state.keyboardProfile
    var editKeys by remember { mutableStateOf(false) }
    var replacementIndex by remember(profile?.id) { mutableStateOf<Int?>(null) }
    var resetRequested by remember { mutableStateOf(false) }
    var tmuxPrefixDraft by remember(profile?.tmuxPrefix) {
        mutableStateOf(profile?.tmuxPrefix ?: KeyboardProfile.DEFAULT_TMUX_PREFIX)
    }
    val validTmuxPrefix = isValidTmuxPrefix(tmuxPrefixDraft)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(KeyboardSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_keyboard, R.string.settings_keyboard_intro) }
        if (profile == null) {
            item { LoadingOrUnavailable(state.profilesLoading) }
        } else {
            item { SectionLabel(R.string.settings_keyboard_preset_heading) }
            item {
                val selectedPresetId = KeyboardPresets.selectedId(profile)
                HorizontalChoices {
                    KeyboardPresets.selectable.forEach { preset ->
                        FilterChip(
                            selected = selectedPresetId == preset.id,
                            onClick = { actions.onKeyboardPreset(preset.id) },
                            label = { Text(stringResource(preset.labelRes)) },
                        )
                    }
                    if (selectedPresetId == "custom") {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_keyboard_preset_custom),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.settings_keyboard_replace_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                KeyboardDeckPreview(
                    profile = profile,
                    onKeyClick = { index -> replacementIndex = index },
                )
                Button(
                    onClick = { editKeys = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text(stringResource(R.string.settings_edit_keyboard_keys)) }
                Text(
                    text = stringResource(R.string.settings_keyboard_key_count, profile.orderedActions.size, profile.layout.rowCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_keyboard_layout_heading) }
            item {
                HorizontalChoices {
                    KeyboardLayout.entries.forEach { layout ->
                        FilterChip(
                            selected = profile.layout == layout,
                            onClick = { actions.onKeyboardLayout(layout) },
                            label = {
                                Text(
                                    if (layout == KeyboardLayout.ONE_ROW) {
                                        stringResource(R.string.settings_keyboard_one_row)
                                    } else {
                                        stringResource(R.string.settings_keyboard_two_rows)
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_keyboard_layout_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(stringResource(R.string.settings_modifier_behaviour), style = MaterialTheme.typography.bodyLarge)
                HorizontalChoices {
                    ModifierBehavior.entries.forEach { behavior ->
                        FilterChip(
                            selected = profile.modifierBehavior == behavior,
                            onClick = { actions.onModifierBehavior(behavior) },
                            label = {
                                Text(
                                    if (behavior == ModifierBehavior.ONE_SHOT) {
                                        stringResource(R.string.settings_modifier_one_shot)
                                    } else {
                                        stringResource(R.string.settings_modifier_lockable)
                                    },
                                )
                            },
                        )
                    }
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_keyboard_haptics),
                    summary = stringResource(R.string.settings_keyboard_haptics_summary),
                    checked = profile.hapticFeedbackEnabled,
                    onCheckedChange = actions.onKeyboardHaptics,
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_keyboard_repeat),
                    summary = stringResource(R.string.settings_keyboard_repeat_summary),
                    checked = profile.keyRepeatEnabled,
                    onCheckedChange = actions.onKeyRepeat,
                )
            }
            item { SectionDivider() }
            item { SectionLabel(R.string.settings_input_mode_heading) }
            item {
                HorizontalChoices {
                    TerminalInputMode.entries.forEach { mode ->
                        FilterChip(
                            selected = profile.inputMode == mode,
                            onClick = { actions.onInputMode(mode) },
                            label = {
                                Text(
                                    if (mode == TerminalInputMode.RAW) {
                                        stringResource(R.string.settings_input_raw)
                                    } else {
                                        stringResource(R.string.settings_input_text)
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = if (profile.inputMode == TerminalInputMode.RAW) {
                        stringResource(R.string.settings_input_raw_summary)
                    } else {
                        stringResource(R.string.settings_input_text_summary)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = tmuxPrefixDraft,
                    onValueChange = { tmuxPrefixDraft = it.take(16) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_tmux_prefix)) },
                    supportingText = {
                        Text(
                            if (validTmuxPrefix) stringResource(R.string.settings_tmux_prefix_summary)
                            else stringResource(R.string.settings_tmux_prefix_invalid),
                        )
                    },
                    isError = !validTmuxPrefix,
                    trailingIcon = {
                        TextButton(
                            onClick = { actions.onTmuxPrefix(tmuxPrefixDraft) },
                            enabled = validTmuxPrefix && tmuxPrefixDraft != profile.tmuxPrefix,
                        ) { Text(stringResource(R.string.apply)) }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
            item {
                SectionLabel(R.string.settings_tmux_help_heading)
                Text(
                    text = stringResource(R.string.settings_tmux_help_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                TmuxHelpSnippetRow(
                    label = stringResource(R.string.settings_tmux_mouse_label),
                    command = TMUX_MOUSE_SNIPPET,
                    onCopy = actions.onCopyTmuxHelp,
                )
                TmuxHelpSnippetRow(
                    label = stringResource(R.string.settings_tmux_history_label),
                    command = TMUX_HISTORY_SNIPPET,
                    onCopy = actions.onCopyTmuxHelp,
                )
            }
            item {
                TextButton(onClick = { resetRequested = true }) {
                    Text(stringResource(R.string.settings_restore_keyboard_preset))
                }
            }
        }
    }

    if (editKeys && profile != null) {
        KeyboardActionsDialog(
            initialActions = profile.orderedActions,
            onDismiss = { editKeys = false },
            onSave = {
                editKeys = false
                actions.onKeyboardActions(it)
            },
        )
    }
    replacementIndex?.let { index ->
        profile?.orderedActions?.getOrNull(index)?.let { current ->
            KeyboardKeyReplacementDialog(
                current = current,
                onDismiss = { replacementIndex = null },
                onSelect = { replacement ->
                    actions.onKeyboardActions(
                        replaceKeyboardActionAt(profile.orderedActions, index, replacement),
                    )
                    replacementIndex = null
                },
            )
        }
    }
    if (resetRequested) {
        ResetDialog(
            title = R.string.settings_reset_keyboard_title,
            message = R.string.settings_reset_keyboard_message,
            onDismiss = { resetRequested = false },
            onConfirm = {
                resetRequested = false
                actions.onResetKeyboard()
            },
        )
    }
}

@Composable
private fun TmuxHelpSnippetRow(
    label: String,
    command: String,
    onCopy: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onCopy(command) }) {
            Text(stringResource(R.string.settings_tmux_copy))
        }
    }
}

private const val TMUX_MOUSE_SNIPPET = "set -g mouse on"
private const val TMUX_HISTORY_SNIPPET = "set -g history-limit 100000"

internal const val KeyboardPreviewKeyTestTagPrefix = "keyboard-preview-key-"

@Composable
private fun KeyboardDeckPreview(
    profile: KeyboardProfile,
    onKeyClick: (Int) -> Unit,
) {
    val rowCount = profile.layout.rowCount
    val columns = ((profile.orderedActions.size + rowCount - 1) / rowCount).coerceAtLeast(1)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(KeyboardPreviewTestTag),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            profile.orderedActions.chunked(columns).forEachIndexed { rowIndex, rowActions ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowActions.forEachIndexed { columnIndex, action ->
                        val index = rowIndex * columns + columnIndex
                        val label = keyboardActionLabel(action)
                        val replaceDescription = stringResource(
                            R.string.settings_keyboard_replace_description,
                            label,
                            index + 1,
                        )
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("$KeyboardPreviewKeyTestTagPrefix$index")
                                .clickable(role = Role.Button) { onKeyClick(index) }
                                .semantics {
                                    contentDescription = replaceDescription
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    repeat(columns - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun KeyboardKeyReplacementDialog(
    current: KeyboardAction,
    onDismiss: () -> Unit,
    onSelect: (KeyboardAction) -> Unit,
) {
    var search by remember(current) { mutableStateOf("") }
    val choices = runtimeSupportedKeyboardActions.filter { action ->
        keyboardActionSearchText(action).contains(search.trim(), ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.settings_keyboard_replace_title,
                    keyboardActionLabel(current),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_keyboard_replace_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_keyboard_replace_search)) },
                    singleLine = true,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 60.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .testTag(KeyboardReplacementGridTestTag),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(6.dp),
                ) {
                    gridItems(choices, key = KeyboardAction::wireCode) { action ->
                        val label = keyboardActionLabel(action)
                        val replaceWithDescription = stringResource(
                            R.string.settings_keyboard_replace_with_description,
                            label,
                        )
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onSelect(action) }
                                .semantics {
                                    contentDescription = replaceWithDescription
                                    selected = action == current
                                },
                            color = if (action == current) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (action == current) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = if (action == current) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (action == current) {
                                    Text(
                                        stringResource(R.string.settings_keyboard_replace_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal const val KeyboardReplacementGridTestTag = "keyboard-replacement-grid"

internal fun replaceKeyboardActionAt(
    actions: List<KeyboardAction>,
    index: Int,
    replacement: KeyboardAction,
): List<KeyboardAction> {
    require(index in actions.indices) { "Keyboard action position is out of range." }
    val current = actions[index]
    if (replacement == current) return actions
    return actions.toMutableList().apply {
        val existingIndex = indexOf(replacement)
        this[index] = replacement
        if (existingIndex >= 0) this[existingIndex] = current
    }
}

@Composable
private fun KeyboardActionsDialog(
    initialActions: List<KeyboardAction>,
    onDismiss: () -> Unit,
    onSave: (List<KeyboardAction>) -> Unit,
) {
    var actions by remember(initialActions) { mutableStateOf(initialActions) }
    var search by remember { mutableStateOf("") }
    val available = runtimeSupportedKeyboardActions.filter { action ->
        action !in actions && keyboardActionSearchText(action).contains(search.trim(), ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_edit_keyboard_keys)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_edit_keyboard_keys_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                ) {
                    items(actions, key = KeyboardAction::wireCode) { action ->
                        val index = actions.indexOf(action)
                        KeyboardActionEditorRow(
                            action = action,
                            index = index,
                            actionCount = actions.size,
                            onMove = { move ->
                                actions = actions.moveStableAction(action.wireCode, move)
                            },
                            onRemove = { actions = actions - action },
                        )
                        HorizontalDivider()
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_add_key)) },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                    items(available.take(30), key = KeyboardAction::wireCode) { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { actions = actions + action }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(keyboardActionLabel(action), modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.add), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(actions) }, enabled = actions.isNotEmpty()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun KeyboardActionEditorRow(
    action: KeyboardAction,
    index: Int,
    actionCount: Int,
    onMove: (KeyboardActionMove) -> Unit,
    onRemove: () -> Unit,
) {
    val label = keyboardActionLabel(action)
    val moveBeforeLabel = stringResource(R.string.settings_keyboard_move_before)
    val moveAfterLabel = stringResource(R.string.settings_keyboard_move_after)
    val dragDescription = stringResource(R.string.settings_keyboard_drag_description, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pointerInput(action.wireCode, index, actionCount) {
                var accumulatedDrag = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulatedDrag = 0f },
                    onDragCancel = { accumulatedDrag = 0f },
                    onDragEnd = { accumulatedDrag = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDrag += dragAmount.y
                        val threshold = size.height.coerceAtLeast(1) * 0.55f
                        when {
                            accumulatedDrag <= -threshold && index > 0 -> {
                                onMove(KeyboardActionMove.BEFORE)
                                accumulatedDrag = 0f
                            }
                            accumulatedDrag >= threshold && index < actionCount - 1 -> {
                                onMove(KeyboardActionMove.AFTER)
                                accumulatedDrag = 0f
                            }
                        }
                    },
                )
            }
            .semantics {
                contentDescription = dragDescription
                customActions = buildList {
                    if (index > 0) {
                        add(
                            CustomAccessibilityAction(moveBeforeLabel) {
                                onMove(KeyboardActionMove.BEFORE)
                                true
                            },
                        )
                    }
                    if (index < actionCount - 1) {
                        add(
                            CustomAccessibilityAction(moveAfterLabel) {
                                onMove(KeyboardActionMove.AFTER)
                                true
                            },
                        )
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}. $label",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
        )
        TextButton(
            enabled = index > 0,
            onClick = { onMove(KeyboardActionMove.BEFORE) },
        ) { Text("↑") }
        TextButton(
            enabled = index < actionCount - 1,
            onClick = { onMove(KeyboardActionMove.AFTER) },
        ) { Text("↓") }
        TextButton(
            enabled = actionCount > 1,
            onClick = onRemove,
        ) { Text(stringResource(R.string.remove)) }
    }
}

@Composable
private fun keyboardActionSearchText(action: KeyboardAction): String =
    "${action.wireCode} ${keyboardActionLabel(action)}".replace('_', ' ')

@Composable
private fun keyboardActionLabel(action: KeyboardAction): String = when (action) {
    KeyboardAction.ESCAPE -> stringResource(R.string.settings_keyboard_action_escape)
    KeyboardAction.CONTROL -> stringResource(R.string.settings_keyboard_action_control)
    KeyboardAction.ALT -> stringResource(R.string.settings_keyboard_action_alt)
    KeyboardAction.TAB -> stringResource(R.string.settings_keyboard_action_tab)
    KeyboardAction.SHIFT -> stringResource(R.string.settings_keyboard_action_shift)
    KeyboardAction.ARROW_UP -> "↑"
    KeyboardAction.ARROW_DOWN -> "↓"
    KeyboardAction.ARROW_LEFT -> "←"
    KeyboardAction.ARROW_RIGHT -> "→"
    KeyboardAction.HOME -> stringResource(R.string.settings_keyboard_action_home)
    KeyboardAction.END -> stringResource(R.string.settings_keyboard_action_end)
    KeyboardAction.PAGE_UP -> stringResource(R.string.settings_keyboard_action_page_up)
    KeyboardAction.PAGE_DOWN -> stringResource(R.string.settings_keyboard_action_page_down)
    KeyboardAction.BACKSPACE -> stringResource(R.string.settings_keyboard_action_backspace)
    KeyboardAction.INSERT -> stringResource(R.string.settings_keyboard_action_insert)
    KeyboardAction.DELETE -> stringResource(R.string.settings_keyboard_action_delete)
    KeyboardAction.ENTER -> stringResource(R.string.settings_keyboard_action_enter)
    KeyboardAction.SLASH -> "/"
    KeyboardAction.BACKSLASH -> "\\"
    KeyboardAction.PIPE -> "|"
    KeyboardAction.TILDE -> "~"
    KeyboardAction.BACKTICK -> "`"
    KeyboardAction.HYPHEN -> "-"
    KeyboardAction.UNDERSCORE -> "_"
    KeyboardAction.AT_SIGN -> "@"
    KeyboardAction.F1, KeyboardAction.F2, KeyboardAction.F3, KeyboardAction.F4,
    KeyboardAction.F5, KeyboardAction.F6, KeyboardAction.F7, KeyboardAction.F8,
    KeyboardAction.F9, KeyboardAction.F10, KeyboardAction.F11, KeyboardAction.F12,
    -> action.name
    KeyboardAction.CTRL_C -> "^C"
    KeyboardAction.CTRL_D -> "^D"
    KeyboardAction.CTRL_L -> "^L"
    KeyboardAction.CTRL_R -> "^R"
    KeyboardAction.CTRL_W -> "^W"
    KeyboardAction.CTRL_U -> "^U"
    KeyboardAction.CTRL_A -> "^A"
    KeyboardAction.CTRL_B -> "^B"
    KeyboardAction.CTRL_E -> "^E"
    KeyboardAction.CTRL_K -> "^K"
    KeyboardAction.CTRL_Z -> "^Z"
    KeyboardAction.TMUX_PREFIX -> stringResource(R.string.settings_keyboard_action_tmux)
    KeyboardAction.PASTE -> stringResource(R.string.settings_keyboard_action_paste)
    KeyboardAction.SNIPPETS -> stringResource(R.string.settings_keyboard_action_snippets)
    KeyboardAction.HIDE_KEYBOARD -> stringResource(R.string.settings_keyboard_action_hide)
    KeyboardAction.KEYBOARD_SETTINGS -> stringResource(R.string.settings_keyboard_action_keys)
    KeyboardAction.COLON -> ":"
    KeyboardAction.SEMICOLON -> ";"
    KeyboardAction.HASH -> "#"
    KeyboardAction.DOLLAR -> "$"
    KeyboardAction.EQUALS -> "="
    KeyboardAction.SPACE -> stringResource(R.string.settings_keyboard_action_space)
    KeyboardAction.EXCLAMATION -> "!"
    KeyboardAction.QUESTION -> "?"
    KeyboardAction.ASTERISK -> "*"
    KeyboardAction.PLUS -> "+"
    KeyboardAction.PERIOD -> "."
    KeyboardAction.COMMA -> ","
    KeyboardAction.LEFT_PAREN -> "("
    KeyboardAction.RIGHT_PAREN -> ")"
    KeyboardAction.LEFT_BRACKET -> "["
    KeyboardAction.RIGHT_BRACKET -> "]"
    KeyboardAction.LEFT_BRACE -> "{"
    KeyboardAction.RIGHT_BRACE -> "}"
    KeyboardAction.SINGLE_QUOTE -> "'"
    KeyboardAction.DOUBLE_QUOTE -> "\""
    KeyboardAction.LESS_THAN -> "<"
    KeyboardAction.GREATER_THAN -> ">"
    KeyboardAction.AMPERSAND -> "&"
    KeyboardAction.CARET -> "^"
    KeyboardAction.PERCENT -> "%"
}

private fun isValidTmuxPrefix(value: String): Boolean {
    return value.isRuntimeTmuxPrefix()
}

internal const val KeyboardSettingsTestTag = "keyboard-settings"
internal const val KeyboardPreviewTestTag = "keyboard-profile-preview"

private data class BackgroundHealth(
    val notificationsEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val backgroundRestricted: Boolean,
    val batteryOptimized: Boolean,
    val dataSaverStatus: Int,
)

@Composable
private fun SessionsBackgroundSettings(state: SettingsUiState, actions: SettingsActions) {
    val context = LocalContext.current
    val health = rememberBackgroundHealth(context)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_sessions_background, R.string.settings_sessions_intro) }
        item { SectionLabel(R.string.settings_connection_reliability_heading) }
        item {
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
        item {
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
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_cpu_awake),
                summary = stringResource(R.string.settings_keep_cpu_awake_summary),
                checked = state.preferences.keepCpuAwake,
                onCheckedChange = actions.onKeepCpuAwake,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                summary = stringResource(R.string.settings_keep_screen_on_summary),
                checked = state.preferences.keepScreenOnWhileTerminalVisible,
                onCheckedChange = actions.onKeepScreenOn,
            )
        }
        item { SectionDivider() }
        item { SectionLabel(R.string.settings_background_health_heading) }
        item {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_notifications_status),
                summary = stringResource(R.string.settings_notifications_status_summary),
                value = if (health.notificationsEnabled && health.notificationPermissionGranted) {
                    stringResource(R.string.settings_status_enabled)
                } else {
                    stringResource(R.string.settings_status_limited)
                },
            )
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_background_restriction),
                summary = stringResource(R.string.settings_background_restriction_summary),
                value = if (health.backgroundRestricted) {
                    stringResource(R.string.settings_status_restricted)
                } else {
                    stringResource(R.string.settings_status_not_restricted)
                },
            )
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_battery_optimisation),
                summary = stringResource(R.string.settings_battery_optimisation_summary),
                value = if (health.batteryOptimized) {
                    stringResource(R.string.settings_status_optimised)
                } else {
                    stringResource(R.string.settings_status_unrestricted)
                },
            )
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
private fun NotificationSettings(state: SettingsUiState, actions: SettingsActions) {
    val context = LocalContext.current
    val health = rememberBackgroundHealth(context)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_notifications, R.string.settings_notifications_intro) }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_notification_privacy),
                summary = stringResource(R.string.settings_notification_privacy_summary),
                checked = state.preferences.notificationPrivacyEnabled,
                onCheckedChange = actions.onNotificationPrivacy,
            )
            SettingSwitchRow(
                title = stringResource(R.string.settings_disconnect_notifications),
                summary = stringResource(R.string.settings_disconnect_notifications_summary),
                checked = state.preferences.disconnectNotificationsEnabled,
                onCheckedChange = actions.onDisconnectNotifications,
            )
            SettingSwitchRow(
                title = stringResource(R.string.settings_reconnect_notifications),
                summary = stringResource(R.string.settings_reconnect_notifications_summary),
                checked = state.preferences.reconnectNotificationsEnabled,
                onCheckedChange = actions.onReconnectNotifications,
            )
        }
        item { SectionDivider() }
        item {
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_notification_permission),
                summary = stringResource(R.string.settings_notification_permission_summary),
                value = if (health.notificationPermissionGranted) {
                    stringResource(R.string.settings_status_allowed)
                } else {
                    stringResource(R.string.settings_status_not_allowed)
                },
            )
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
private fun SecuritySettings(
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SecuritySettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DetailHeading(R.string.settings_category_security, R.string.settings_security_intro) }
        item { SectionLabel(R.string.settings_app_lock_heading) }
        item {
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
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_block_screenshots),
                summary = stringResource(R.string.settings_block_screenshots_summary),
                checked = state.preferences.screenshotBlockingEnabled,
                onCheckedChange = onScreenshotBlocking,
            )
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
            ReadOnlySettingRow(
                title = stringResource(R.string.settings_remote_clipboard),
                summary = stringResource(R.string.settings_remote_clipboard_summary),
                value = when (state.terminalProfile?.links?.remoteClipboardMode) {
                    RemoteClipboardMode.ASK -> stringResource(R.string.settings_ask)
                    else -> stringResource(R.string.settings_disabled)
                },
            )
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
        item {
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

        SavedCredentialClearUiState.PreviewFailed -> AlertDialog(
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
            AlertDialog(
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

        is SavedCredentialClearUiState.Completed -> AlertDialog(
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

@Composable
private fun DetailHeading(@StringRes title: Int, @StringRes summary: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun HorizontalChoices(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReadOnlySettingRow(title: String, summary: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingOrUnavailable(loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(
                if (loading) R.string.settings_profiles_loading else R.string.settings_profiles_unavailable,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResetDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.reset)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
