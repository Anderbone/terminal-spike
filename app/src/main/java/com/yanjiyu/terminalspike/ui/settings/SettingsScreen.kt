package com.yanjiyu.terminalspike.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.ui.AboutSection
import com.yanjiyu.terminalspike.ui.AdaptivePrimaryNavigation
import com.yanjiyu.terminalspike.ui.AppDestination
import com.yanjiyu.terminalspike.ui.AppGlyph
import com.yanjiyu.terminalspike.ui.AppGlyphIcon
import com.yanjiyu.terminalspike.ui.DeveloperSettingsSection
import com.yanjiyu.terminalspike.ui.MoshExtensionSection
import com.yanjiyu.terminalspike.ui.MoshExtensionUiState
import com.yanjiyu.terminalspike.ui.NoticeSnackbarHost
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import com.yanjiyu.terminalspike.ui.guide.UserGuideIndex
import com.yanjiyu.terminalspike.ui.resolve
import com.yanjiyu.terminalspike.ui.theme.AppDialog
import com.yanjiyu.terminalspike.ui.theme.iconMetrics
import com.yanjiyu.terminalspike.ui.theme.spacing
import com.yanjiyu.terminalspike.ui.theme.upperNoticePlacement
import com.yanjiyu.terminalspike.ui.uiText

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
    onOpenSshKeys: () -> Unit = {},
    onOpenSnippets: () -> Unit = {},
    snippetsContent: (@Composable () -> Unit)? = null,
    keysContent: (@Composable (String) -> Unit)? = null,
    onOpenPortForwards: () -> Unit = {},
    onDismissMessage: () -> Unit,
    backupState: BackupWorkflowUiState = BackupWorkflowUiState(),
    backupActions: BackupSettingsActions = BackupSettingsActions.NONE,
    savedCredentialClearState: SavedCredentialClearUiState = SavedCredentialClearUiState.Idle,
    safeContentInsets: WindowInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Vertical)
        .union(WindowInsets.displayCutout),
) {
    var selectedCategory by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    val categoryStates = rememberSaveableStateHolder()
    var query by rememberSaveable { mutableStateOf("") }
    var pendingKnownHost by remember { mutableStateOf<KnownHostSummary?>(null) }
    val screenDescription = stringResource(R.string.settings_screen_description)
    var showSearchResults by rememberSaveable { mutableStateOf(false) }
    var searchAnchor by rememberSaveable { mutableStateOf<Int?>(null) }
    val isKeySearch = selectedCategory == SettingsCategory.SSH_KEYS && keysContent != null
    val categories = settingsCategoriesForSearch("", includeDeveloper = BuildConfig.DEBUG)
    val searchResults = rememberSettingsSearchResults(query)
    BackHandler(enabled = selectedCategory != null || showSearchResults) {
        if (showSearchResults) {
            query = ""
            showSearchResults = false
        } else if (query.isNotBlank() && !isKeySearch) {
            showSearchResults = true
            searchAnchor = null
        } else {
            searchAnchor = null
            selectedCategory = if (selectedCategory in advancedSettingsCategories && query.isBlank()) {
                SettingsCategory.ADVANCED
            } else {
                null
            }
        }
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val selectCategory: (SettingsCategory) -> Unit = { category ->
        focusManager.clearFocus()
        keyboardController?.hide()
        showSearchResults = false
        searchAnchor = null
        if (category == SettingsCategory.SSH_KEYS && keysContent == null) {
            onOpenSshKeys()
        } else {
            if (category == SettingsCategory.SSH_KEYS || selectedCategory == SettingsCategory.SSH_KEYS) {
                query = ""
            }
            selectedCategory = category
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = screenDescription },
        color = MaterialTheme.colorScheme.background,
    ) {
        AdaptivePrimaryNavigation(
            selected = AppDestination.SETTINGS,
            onWorkspace = onOpenWorkspace,
            onConnections = onOpenTerminal,
            onSettings = {
                selectedCategory = null
                showSearchResults = false
                searchAnchor = null
                query = ""
                onOpenSettings()
            },
            modifier = Modifier.fillMaxSize(),
            safeContentInsets = safeContentInsets,
        ) { contentModifier, expanded ->
            Column(modifier = contentModifier.fillMaxSize()) {
                SettingsSearchHeader(
                    query = query,
                    onQueryChange = {
                        query = it
                        if (!isKeySearch) {
                            showSearchResults = it.isNotBlank()
                            searchAnchor = null
                            if (it.isBlank()) selectedCategory = null
                        }
                    },
                    searchDescription = stringResource(
                        if (selectedCategory == SettingsCategory.SSH_KEYS && keysContent != null)
                            R.string.connections_search_keys else R.string.settings_search_label,
                    ),
                )
                if (showSearchResults && !isKeySearch) {
                    SettingsSearchResults(
                        results = searchResults,
                        onSelected = { entry ->
                            selectCategory(entry.category)
                            searchAnchor = entry.anchorRes
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (expanded) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SettingsCategoryList(
                            categories = categories,
                            state = state,
                            selected = if (query.isBlank() && selectedCategory in advancedSettingsCategories) {
                                SettingsCategory.ADVANCED
                            } else {
                                selectedCategory ?: SettingsCategory.APPEARANCE
                            },
                            query = query,
                            onSelected = selectCategory,
                            modifier = Modifier
                                .width(276.dp)
                                .fillMaxHeight()
                                .testTag(ExpandedSettingsCategoryListTestTag),
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        SettingsDetail(
                            category = selectedCategory ?: SettingsCategory.APPEARANCE,
                            searchAnchor = searchAnchor,
                            onSelectCategory = selectCategory,
                            state = state,
                            knownHosts = knownHosts,
                            moshExtension = moshExtension,
                            actions = actions,
                            onForgetKnownHost = { pendingKnownHost = it },
                            onRefreshMoshExtension = onRefreshMoshExtension,
                            onOpenRendererLab = onOpenRendererLab,
                            onOpenSshKeys = onOpenSshKeys,
                            onOpenSnippets = onOpenSnippets,
                            snippetsContent = snippetsContent,
                            keysContent = keysContent?.let { content -> { content(query) } },
                            onOpenPortForwards = onOpenPortForwards,
                            backupState = backupState,
                            backupActions = backupActions,
                            savedCredentialClearState = savedCredentialClearState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(ExpandedSettingsDetailTestTag),
                        )
                    }
                } else {
                    AnimatedContent(
                        targetState = selectedCategory,
                        transitionSpec = {
                            val direction = if (targetState == null) -1 else 1
                            (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 12 * direction })
                                .togetherWith(fadeOut(tween(100)) + slideOutHorizontally(tween(220)) { -it / 12 * direction })
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "settings-detail",
                    ) { category ->
                        categoryStates.SaveableStateProvider(category?.name ?: "categories") {
                            if (category == null) {
                                SettingsCategoryList(
                                    categories = categories,
                                    state = state,
                                    selected = null,
                                    query = query,
                                    onSelected = selectCategory,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                SettingsDetail(
                                    category = category,
                                    searchAnchor = searchAnchor,
                                    onSelectCategory = selectCategory,
                                    state = state,
                                    knownHosts = knownHosts,
                                    moshExtension = moshExtension,
                                    actions = actions,
                                    onForgetKnownHost = { pendingKnownHost = it },
                                    onRefreshMoshExtension = onRefreshMoshExtension,
                                    onOpenRendererLab = onOpenRendererLab,
                                    onOpenSshKeys = onOpenSshKeys,
                                    onOpenSnippets = onOpenSnippets,
                                    snippetsContent = snippetsContent,
                                    keysContent = keysContent?.let { content -> { content(query) } },
                                    onOpenPortForwards = onOpenPortForwards,
                                    backupState = backupState,
                                    backupActions = backupActions,
                                    savedCredentialClearState = savedCredentialClearState,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        NoticeSnackbarHost(
            notice = state.message?.let { uiText(it) },
            hostState = remember { SnackbarHostState() },
            onNoticePresented = { presented ->
                if (state.message?.let { uiText(it) } == presented) onDismissMessage()
            },
            modifier = Modifier.upperNoticePlacement(),
        )
    }
    pendingKnownHost?.let { knownHost ->
        AppDialog(
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
private fun SettingsCategoryList(
    categories: List<SettingsCategory>,
    state: SettingsUiState,
    selected: SettingsCategory?,
    query: String,
    onSelected: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val listDescription = stringResource(R.string.settings_category_list_description)
    LazyColumn(
        modifier = modifier.semantics {
            contentDescription = listDescription
        },
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.large,
            vertical = MaterialTheme.spacing.medium,
        ),
    ) {
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
                selected = category == selected,
                onClick = { onSelected(category) },
                summary = settingsCategorySummary(category, state).resolve(),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (query.isBlank()) {
            item(key = "community_and_support") {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .padding(top = MaterialTheme.spacing.medium).testTag("settings-community-support"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsExternalLink(
                        label = stringResource(R.string.settings_discord_short),
                        description = stringResource(R.string.settings_join_discord),
                        logo = R.drawable.ic_brand_discord,
                        brandColor = Color(0xFF5865F2),
                        logoColor = Color.White,
                        onClick = { uriHandler.openUri("https://discord.gg/yMPzeCeWMT") },
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("settings-discord"),
                    )
                    SettingsExternalLink(
                        label = stringResource(R.string.settings_buy_me_a_coffee),
                        description = stringResource(R.string.settings_buy_me_a_coffee),
                        logo = R.drawable.ic_brand_buymeacoffee,
                        brandColor = Color(0xFFFFDD00),
                        logoColor = Color(0xFF181818),
                        onClick = { uriHandler.openUri("https://buymeacoffee.com/jiyuyan") },
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("settings-coffee"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "settings-selection",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppGlyphIcon(
                glyph = category.glyph,
                modifier = Modifier.size(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = summary ?: stringResource(category.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnectionsGlyphIcon(
                glyph = ConnectionsGlyph.BACK,
                modifier = Modifier
                    .padding(start = MaterialTheme.spacing.small)
                    .graphicsLayer(rotationZ = 180f)
                    .size(MaterialTheme.iconMetrics.compact),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private val SettingsCategory.glyph: AppGlyph
    get() = when (this) {
        SettingsCategory.APPEARANCE -> AppGlyph.APPEARANCE
        SettingsCategory.TERMINAL -> AppGlyph.TERMINAL
        SettingsCategory.KEYBOARD -> AppGlyph.KEYBOARD
        SettingsCategory.SSH_KEYS -> AppGlyph.KEYCHAIN
        SettingsCategory.SNIPPETS -> AppGlyph.SNIPPETS
        SettingsCategory.PORT_FORWARDING -> AppGlyph.WORKSPACE
        SettingsCategory.SESSIONS_BACKGROUND -> AppGlyph.WORKSPACE
        SettingsCategory.NOTIFICATIONS -> AppGlyph.NOTIFICATIONS
        SettingsCategory.BACKUP_RESTORE -> AppGlyph.BACKUP
        SettingsCategory.SECURITY -> AppGlyph.SHIELD
        SettingsCategory.LOCAL_ARCH -> AppGlyph.TERMINAL
        SettingsCategory.MOSH -> AppGlyph.SIGNAL
        SettingsCategory.GUIDE -> AppGlyph.INFO
        SettingsCategory.ABOUT -> AppGlyph.INFO
        SettingsCategory.ADVANCED -> AppGlyph.LAB
        SettingsCategory.DEVELOPER -> AppGlyph.LAB
    }

internal const val AdvancedSettingsListTestTag = "advanced-settings-list"

@Composable
private fun SettingsDetail(
    category: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    state: SettingsUiState,
    knownHosts: List<KnownHostSummary>,
    moshExtension: MoshExtensionUiState,
    actions: SettingsActions,
    onForgetKnownHost: (KnownHostSummary) -> Unit,
    onRefreshMoshExtension: () -> Unit,
    onOpenRendererLab: () -> Unit,
    onOpenSshKeys: () -> Unit,
    onOpenSnippets: () -> Unit,
    snippetsContent: (@Composable () -> Unit)? = null,
    keysContent: (@Composable () -> Unit)? = null,
    onOpenPortForwards: () -> Unit = {},
    backupState: BackupWorkflowUiState,
    backupActions: BackupSettingsActions,
    savedCredentialClearState: SavedCredentialClearUiState,
    modifier: Modifier,
    searchAnchor: Int? = null,
) {
    CompositionLocalProvider(LocalSettingsSearchAnchor provides searchAnchor) {
        key(category, searchAnchor) {
            Box(modifier = modifier) {
                when (category) {
                    SettingsCategory.ADVANCED -> LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag(AdvancedSettingsListTestTag),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        item {
                            DetailHeading(R.string.settings_category_advanced, R.string.settings_category_advanced_summary)
                        }
                        items(advancedSettingsCategories.filter { BuildConfig.DEBUG || it != SettingsCategory.DEVELOPER }) { advanced ->
                            SettingsCategoryRow(
                                category = advanced,
                                selected = false,
                                onClick = { onSelectCategory(advanced) },
                            )
                        }
                    }
                    SettingsCategory.APPEARANCE -> AppearanceSettings(state, actions)
                    SettingsCategory.TERMINAL -> TerminalSettings(state, actions)
                    SettingsCategory.KEYBOARD -> KeyboardSettings(state, actions)
                    SettingsCategory.SSH_KEYS -> {
                        if (keysContent != null) keysContent() else DataManagementSettings(
                            category = SettingsCategory.SSH_KEYS,
                            detail = R.string.settings_ssh_keys_management_detail,
                            action = R.string.settings_ssh_keys_management_action,
                            onOpen = onOpenSshKeys,
                        )
                    }
                    SettingsCategory.SNIPPETS -> Column(modifier = Modifier.fillMaxSize()) {
                        if (snippetsContent != null) {
                            Box(modifier = Modifier.weight(1f)) { snippetsContent() }
                        } else {
                            TextButton(onClick = onOpenSnippets) {
                                Text(stringResource(R.string.settings_snippets_management_action))
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(stringResource(R.string.settings_snippets_display), style = MaterialTheme.typography.labelMedium)
                            HorizontalChoices {
                                listOf(false, true).forEach { above ->
                                    FilterChip(
                                        selected = state.preferences.snippetsAboveKeyboard == above,
                                        onClick = { actions.onSnippetsAboveKeyboard(above) },
                                        label = {
                                            Text(
                                                stringResource(if (above) R.string.settings_snippets_display_above else R.string.settings_snippets_display_swipe),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    SettingsCategory.PORT_FORWARDING -> DataManagementSettings(
                        category = SettingsCategory.PORT_FORWARDING,
                        detail = R.string.port_forward_settings_detail,
                        action = R.string.port_forward_manage,
                        onOpen = onOpenPortForwards,
                    )
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
                    SettingsCategory.LOCAL_ARCH -> com.yanjiyu.terminalspike.ui.LocalArchSettings()
                    SettingsCategory.MOSH -> MoshExtensionSection(
                        state = moshExtension,
                        onRefresh = onRefreshMoshExtension,
                    )
                    SettingsCategory.GUIDE -> UserGuideIndex()
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
    }
}

@Composable
private fun DataManagementSettings(
    category: SettingsCategory,
    @StringRes detail: Int,
    @StringRes action: Int,
    onOpen: () -> Unit,
    extraSettings: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DetailHeading(category.titleRes, category.summaryRes) }
        if (extraSettings != null) item { Column { extraSettings() } }
        item {
            Text(
                text = stringResource(detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(action))
            }
        }
    }
}

@Composable
private fun SettingsExternalLink(
    label: String,
    description: String,
    @androidx.annotation.DrawableRes logo: Int,
    brandColor: Color,
    logoColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = brandColor) {
                Icon(
                    painterResource(logo), contentDescription = null, tint = logoColor,
                    modifier = Modifier.padding(4.dp).size(22.dp),
                )
            }
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal const val ExpandedSettingsCategoryListTestTag = "expanded-tool-section-list"
internal const val ExpandedSettingsDetailTestTag = "expanded-tool-detail"
