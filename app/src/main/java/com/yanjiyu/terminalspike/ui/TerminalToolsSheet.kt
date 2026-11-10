package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yanjiyu.terminalspike.ui.theme.AppDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedHostConnectionCompatibility
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.ui.settings.SettingsCategory
import com.yanjiyu.terminalspike.ui.settings.SettingsDestination
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import com.yanjiyu.terminalspike.ui.theme.iconMetrics

internal enum class ToolSection(@androidx.annotation.StringRes val labelRes: Int) {
    PROFILES(R.string.connections_tab_hosts),
    IDENTITIES(R.string.connections_tab_keys),
    SNIPPETS(R.string.connections_tab_snippets),
    TERMINAL(R.string.settings_category_terminal),
    KEYS(R.string.settings_category_keyboard),
    SECURITY(R.string.settings_category_security),
    MOSH(R.string.settings_category_mosh),
    ABOUT(R.string.settings_category_about),
    DEVELOPER(R.string.settings_category_developer),
}

internal const val ExpandedToolSectionListTestTag = "expanded-tool-section-list"
internal const val ExpandedToolDetailTestTag = "expanded-tool-detail"
internal const val CompactToolTabsTestTag = "compact-tool-tabs"

private sealed interface PendingToolDeletion {
    data class Profile(val profile: SavedSshProfile) : PendingToolDeletion

    data class Identity(val identity: SavedSshIdentity) : PendingToolDeletion

    data class KnownHost(val knownHost: KnownHostSummary) : PendingToolDeletion

    data class Snippet(val snippet: CommandSnippet) : PendingToolDeletion
}

@Composable
internal fun LocalToolsScreen(
    destination: AppDestination,
    profiles: List<SavedSshProfile>,
    identities: List<SavedSshIdentity>,
    knownHosts: List<KnownHostSummary>,
    snippets: List<CommandSnippet>,
    extraKeys: List<TerminalExtraKey>,
    moshExtension: MoshExtensionUiState,
    onNavigateBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSshKeys: () -> Unit = {},
    onOpenSnippets: () -> Unit = {},
    onOpenPortForwards: () -> Unit = {},
    onUseProfile: (SavedSshProfile) -> Unit,
    onSaveProfile: (label: String, host: String, port: String, username: String, existingId: Long?) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onImportIdentity: () -> Unit,
    onReimportIdentity: (String) -> Unit,
    onDeleteIdentity: (Long) -> Unit,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
    onSaveSnippet: (label: String, command: String, appendEnter: Boolean, existingId: Long?) -> Unit,
    onDeleteSnippet: (Long) -> Unit,
    onSendSnippet: (Long) -> Unit,
    onSetKeyVisible: (TerminalExtraKey, Boolean) -> Unit,
    onReplaceKey: (TerminalExtraKey, TerminalExtraKey) -> Unit,
    onMoveKey: (TerminalExtraKey, Int) -> Unit,
    onResetKeys: () -> Unit,
    onSaveKeys: () -> Unit,
    onRefreshMoshExtension: () -> Unit,
    onOpenRendererLab: () -> Unit,
    initialSection: ToolSection = ToolSection.PROFILES,
    terminalProfileId: String? = null,
    keyboardProfileId: String? = null,
) {
    if (destination == AppDestination.SETTINGS) {
        var editingSettingsSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
        var settingsSnippetEditorVisible by remember { mutableStateOf(false) }
        var deletingSettingsSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
        val context = LocalContext.current
        val toolbarPreferences = remember(context) {
            context.getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
        }
        var hiddenDefaults by remember {
            mutableStateOf(toolbarPreferences.getStringSet("replaced-defaults", emptySet()).orEmpty().toSet())
        }
        var codexHidden by remember { mutableStateOf(toolbarPreferences.getBoolean("codex-replaced", false)) }
        fun hideDefault(id: Long) {
            if (id == 0L) {
                codexHidden = true
                toolbarPreferences.edit().putBoolean("codex-replaced", true).apply()
            } else {
                hiddenDefaults = hiddenDefaults + id.toString()
                toolbarPreferences.edit().putStringSet("replaced-defaults", hiddenDefaults).apply()
            }
        }
        val allSnippets = snippets + com.yanjiyu.terminalspike.settings.DefaultTerminalSnippets.all.filter {
            if (it.id == 0L) !codexHidden else it.id.toString() !in hiddenDefaults
        }

        SettingsDestination(
            initialCategory = initialSection.toSettingsCategory(),
            knownHosts = knownHosts,
            moshExtension = moshExtension,
            onForgetKnownHost = onForgetKnownHost,
            onRefreshMoshExtension = onRefreshMoshExtension,
            onOpenRendererLab = onOpenRendererLab,
            onNavigateBack = onNavigateBack,
            onOpenWorkspace = onOpenWorkspace,
            onOpenTerminal = onOpenConnections,
            onOpenSettings = onOpenSettings,
            onOpenSshKeys = onOpenSshKeys,
            onOpenSnippets = onOpenSnippets,
            snippetsContent = {
                SnippetsSection(
                    snippets = allSnippets,
                    onAdd = { editingSettingsSnippet = null; settingsSnippetEditorVisible = true },
                    onSend = onSendSnippet,
                    onEdit = { editingSettingsSnippet = it; settingsSnippetEditorVisible = true },
                    onDelete = { id -> deletingSettingsSnippet = allSnippets.firstOrNull { it.id == id } },
                    managementOnly = true,
                )
            },
            onOpenPortForwards = onOpenPortForwards,
            terminalProfileId = terminalProfileId,
            keyboardProfileId = keyboardProfileId,
        )
        if (settingsSnippetEditorVisible) {
            SnippetEditorDialog(
                snippet = editingSettingsSnippet,
                onDismiss = { settingsSnippetEditorVisible = false },
                onSave = { label, command, appendEnter ->
                    val editedId = editingSettingsSnippet?.id
                    onSaveSnippet(label, command, appendEnter, editedId)
                    editedId?.takeIf { it <= 0 }?.let(::hideDefault)
                    settingsSnippetEditorVisible = false
                },
            )
        }
        deletingSettingsSnippet?.let { snippet ->
            ToolDeletionConfirmationDialog(
                deletion = PendingToolDeletion.Snippet(snippet),
                onDismiss = { deletingSettingsSnippet = null },
                onConfirm = {
                    if (snippet.id <= 0) hideDefault(snippet.id) else onDeleteSnippet(snippet.id)
                    deletingSettingsSnippet = null
                },
            )
        }
        return
    }

    val availableSections = when (destination) {
        AppDestination.CONNECTIONS -> listOf(
            ToolSection.PROFILES,
            ToolSection.IDENTITIES,
            ToolSection.SNIPPETS,
        )
        AppDestination.SETTINGS -> buildList {
            add(ToolSection.MOSH)
            add(ToolSection.TERMINAL)
            add(ToolSection.KEYS)
            add(ToolSection.SECURITY)
            add(ToolSection.ABOUT)
            if (BuildConfig.DEBUG) add(ToolSection.DEVELOPER)
        }
        AppDestination.TERMINAL -> error("Terminal does not use LocalToolsScreen")
        AppDestination.WORKSPACE -> error("Workspace does not use LocalToolsScreen")
    }
    var section by remember(initialSection, destination) {
        mutableStateOf(initialSection.takeIf { it in availableSections } ?: availableSections.first())
    }
    var addingProfile by remember { mutableStateOf(false) }
    var addingSnippet by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<SavedSshProfile?>(null) }
    var editingSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    var pendingDeletion by remember { mutableStateOf<PendingToolDeletion?>(null) }

    val sectionContent: @Composable () -> Unit = {
        when (section) {
            ToolSection.PROFILES -> ProfilesSection(
                profiles = profiles,
                onAdd = { addingProfile = true },
                onUse = onUseProfile,
                onEdit = {
                    editingProfile = it
                    addingProfile = true
                },
                onDelete = { id ->
                    profiles.firstOrNull { it.id == id }?.let { profile ->
                        pendingDeletion = PendingToolDeletion.Profile(profile)
                    }
                },
            )
            ToolSection.KEYS -> KeysSection(
                selectedKeys = extraKeys,
                onSetVisible = onSetKeyVisible,
                onReplace = onReplaceKey,
                onMove = onMoveKey,
                onReset = onResetKeys,
                onSave = onSaveKeys,
            )
            ToolSection.SNIPPETS -> SnippetsSection(
                snippets = snippets,
                onAdd = { addingSnippet = true },
                onSend = onSendSnippet,
                onEdit = {
                    editingSnippet = it
                    addingSnippet = true
                },
                onDelete = { id ->
                    snippets.firstOrNull { it.id == id }?.let { snippet ->
                        pendingDeletion = PendingToolDeletion.Snippet(snippet)
                    }
                },
            )
            ToolSection.IDENTITIES -> IdentitiesSection(
                identities = identities,
                onImport = onImportIdentity,
                onReimport = onReimportIdentity,
                onDelete = { id ->
                    identities.firstOrNull { it.id == id }?.let { identity ->
                        pendingDeletion = PendingToolDeletion.Identity(identity)
                    }
                },
            )
            ToolSection.SECURITY -> KnownHostsSection(
                knownHosts = knownHosts,
                onForgetKnownHost = { host, algorithm ->
                    knownHosts.firstOrNull {
                        it.host == host && it.algorithm == algorithm
                    }?.let { knownHost ->
                        pendingDeletion = PendingToolDeletion.KnownHost(knownHost)
                    }
                },
            )
            ToolSection.MOSH -> MoshExtensionSection(
                state = moshExtension,
                onRefresh = onRefreshMoshExtension,
            )
            ToolSection.TERMINAL -> error("Terminal settings use SettingsDestination")
            ToolSection.ABOUT -> AboutSection()
            ToolSection.DEVELOPER -> DeveloperSettingsSection(
                onOpenRendererLab = onOpenRendererLab,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        AdaptivePrimaryNavigation(
            selected = destination,
            onWorkspace = onOpenWorkspace,
            onConnections = onOpenConnections,
            onSettings = onOpenSettings,
            modifier = Modifier.fillMaxSize(),
        ) { contentModifier, expanded ->
            val destinationLabel = stringResource(destination.labelResId)
            val backDescription = stringResource(
                R.string.navigation_back_from,
                destinationLabel,
            )
            val screenDescription = stringResource(
                R.string.navigation_destination_screen,
                destinationLabel,
            )
            Column(
                modifier = contentModifier
                    .fillMaxWidth(),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.semantics {
                        contentDescription = backDescription
                    },
                ) {
                    ConnectionsGlyphIcon(
                        glyph = ConnectionsGlyph.BACK,
                        modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = destinationLabel,
                        modifier = Modifier.semantics {
                            contentDescription = screenDescription
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when (section) {
                            ToolSection.ABOUT -> stringResource(R.string.ui_about_summary)
                            ToolSection.DEVELOPER -> stringResource(R.string.ui_local_diagnostics_for_debug_builds)
                            ToolSection.MOSH -> stringResource(R.string.ui_optional_transport_extension_status)
                            else -> stringResource(R.string.ui_local_privacy_summary)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (expanded) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ExpandedToolSectionList(
                            sections = availableSections,
                            selected = section,
                            onSelected = { section = it },
                            modifier = Modifier
                                .width(224.dp)
                                .fillMaxHeight()
                                .testTag(ExpandedToolSectionListTestTag),
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(ExpandedToolDetailTestTag),
                        ) {
                            sectionContent()
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .testTag(CompactToolTabsTestTag),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableSections.forEach { item ->
                            FilterChip(
                                selected = section == item,
                                onClick = { section = item },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        sectionContent()
                    }
                }
            }
        }
    }

    if (addingProfile) {
        ProfileEditorDialog(
            profile = editingProfile,
            onDismiss = {
                addingProfile = false
                editingProfile = null
            },
            onSave = { label, host, port, username ->
                onSaveProfile(label, host, port, username, editingProfile?.id)
                addingProfile = false
                editingProfile = null
            },
        )
    }
    if (addingSnippet) {
        SnippetEditorDialog(
            snippet = editingSnippet,
            onDismiss = {
                addingSnippet = false
                editingSnippet = null
            },
            onSave = { label, command, appendEnter ->
                onSaveSnippet(label, command, appendEnter, editingSnippet?.id)
                addingSnippet = false
                editingSnippet = null
            },
        )
    }
    pendingDeletion?.let { deletion ->
        ToolDeletionConfirmationDialog(
            deletion = deletion,
            onDismiss = { pendingDeletion = null },
            onConfirm = {
                when (deletion) {
                    is PendingToolDeletion.Profile -> onDeleteProfile(deletion.profile.id)
                    is PendingToolDeletion.Identity -> onDeleteIdentity(deletion.identity.id)
                    is PendingToolDeletion.KnownHost -> onForgetKnownHost(
                        deletion.knownHost.host,
                        deletion.knownHost.algorithm,
                    )
                    is PendingToolDeletion.Snippet -> onDeleteSnippet(deletion.snippet.id)
                }
                pendingDeletion = null
            },
        )
    }
}

private fun ToolSection.toSettingsCategory(): SettingsCategory? = when (this) {
    ToolSection.TERMINAL -> SettingsCategory.TERMINAL
    ToolSection.KEYS -> SettingsCategory.KEYBOARD
    ToolSection.IDENTITIES -> SettingsCategory.SSH_KEYS
    ToolSection.SNIPPETS -> SettingsCategory.SNIPPETS
    ToolSection.SECURITY -> SettingsCategory.SECURITY
    ToolSection.MOSH -> SettingsCategory.MOSH
    ToolSection.ABOUT -> SettingsCategory.ABOUT
    ToolSection.DEVELOPER -> SettingsCategory.DEVELOPER
    ToolSection.PROFILES -> null
}

@Composable
internal fun MoshExtensionSection(
    state: MoshExtensionUiState,
    onRefresh: () -> Unit,
) {
    var showInstallationHelp by remember(state.kind) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag(MoshExtensionSectionTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mosh_extension_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = state.statusLabel.resolve(),
            modifier = Modifier.testTag(MoshExtensionStatusTestTag),
            style = MaterialTheme.typography.titleMedium,
            color = when (state.kind) {
                MoshExtensionUiKind.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
                MoshExtensionUiKind.AVAILABLE -> MaterialTheme.colorScheme.primary
                MoshExtensionUiKind.ABSENT,
                MoshExtensionUiKind.DISABLED,
                MoshExtensionUiKind.INCOMPATIBLE,
                -> MaterialTheme.colorScheme.tertiary
                MoshExtensionUiKind.UNTRUSTED,
                MoshExtensionUiKind.ERROR,
                -> MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = state.summary.resolve(),
            style = MaterialTheme.typography.bodyLarge,
        )
        state.details.forEach { detail ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = detail.label.resolve(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = detail.value.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.mosh_signature_verification),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.verificationMessage.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.actionLabel?.let { label ->
            Button(
                onClick = onRefresh,
                modifier = Modifier.testTag(MoshExtensionRefreshTestTag),
            ) {
                Text(label.resolve())
            }
        }
        state.installationHelpLabel?.let { label ->
            TextButton(
                onClick = { showInstallationHelp = true },
                modifier = Modifier.testTag(MoshExtensionInstallationHelpTestTag),
            ) {
                Text(label.resolve())
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.mosh_ssh_fallback),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.mosh_ssh_fallback_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.mosh_free_software_source),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.mosh_free_software_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.mosh_trademark_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showInstallationHelp) {
        MoshInstallationHelpDialog(onDismiss = { showInstallationHelp = false })
    }
}

@Composable
private fun MoshInstallationHelpDialog(onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(MoshExtensionInstallationHelpDialogTestTag),
        title = { Text(stringResource(R.string.mosh_installation_help_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.mosh_installation_help_matching_apk),
                )
                Text(
                    stringResource(R.string.mosh_installation_help_debug),
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.mosh_installation_help_distribution),
                )
                Text(
                    stringResource(R.string.mosh_installation_help_retry),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(MoshExtensionInstallationHelpCloseTestTag),
            ) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

internal const val MoshExtensionSectionTestTag = "mosh-extension-section"
internal const val MoshExtensionStatusTestTag = "mosh-extension-status"
internal const val MoshExtensionRefreshTestTag = "mosh-extension-refresh"
internal const val MoshExtensionInstallationHelpTestTag = "mosh-extension-installation-help"
internal const val MoshExtensionInstallationHelpDialogTestTag =
    "mosh-extension-installation-help-dialog"
internal const val MoshExtensionInstallationHelpCloseTestTag =
    "mosh-extension-installation-help-close"

@Composable
private fun ExpandedToolSectionList(
    sections: List<ToolSection>,
    selected: ToolSection,
    onSelected: (ToolSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sections.forEach { item ->
            Surface(
                onClick = { onSelected(item) },
                modifier = Modifier.fillMaxWidth(),
                color = if (selected == item) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
                contentColor = if (selected == item) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(item.labelRes),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected == item) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ToolDeletionConfirmationDialog(
    deletion: PendingToolDeletion,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title: String
    val message: String
    val confirmLabel: String
    when (deletion) {
        is PendingToolDeletion.Profile -> {
            title = stringResource(R.string.connections_delete_title, deletion.profile.label)
            message = if (deletion.profile.hasSavedPassword) {
                stringResource(R.string.ui_this_removes_the_saved_host_and_its_device_encrypted_password_active_sessions_are_not_disc)
            } else {
                stringResource(R.string.ui_this_removes_the_saved_host_active_sessions_are_not_disconnected)
            }
            confirmLabel = stringResource(R.string.connections_delete_host)
        }
        is PendingToolDeletion.Identity -> {
            title = stringResource(R.string.connections_delete_title, deletion.identity.label)
            message = stringResource(R.string.ui_this_permanently_removes_the_device_encrypted_private_key_saved_hosts_that_use_it_may_no_l)
            confirmLabel = stringResource(R.string.connections_delete_key)
        }
        is PendingToolDeletion.KnownHost -> {
            title = stringResource(R.string.settings_forget_known_host_title, deletion.knownHost.host)
            message = stringResource(R.string.ui_the_next_connection_must_verify_this_host_again_only_accept_it_if_the_new_fingerprint_is_e)
            confirmLabel = stringResource(R.string.settings_forget_known_host_action)
        }
        is PendingToolDeletion.Snippet -> {
            title = stringResource(R.string.connections_delete_title, deletion.snippet.label)
            message = stringResource(R.string.connections_delete_snippet_detail)
            confirmLabel = stringResource(R.string.connections_delete_snippet)
        }
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun IdentitiesSection(
    identities: List<SavedSshIdentity>,
    onImport: () -> Unit,
    onReimport: (String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(title = stringResource(R.string.ui_ssh_private_keys), action = stringResource(R.string.connections_import_key), onAction = onImport)
            Text(
                text = stringResource(R.string.ui_imported_through_android_s_document_picker_encrypted_on_this_device_and_never_exported_by_),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (identities.isEmpty()) item { EmptyLine(stringResource(R.string.ui_no_private_keys_imported)) }
        items(identities, key = { it.id }) { identity ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(identity.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${identity.keyType} · ${identity.fingerprint}" +
                            if (!identity.isAvailable) {
                                stringResource(R.string.ui_key_unavailable_suffix)
                            } else if (identity.passphraseRequired) {
                                stringResource(R.string.ui_key_passphrase_suffix)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!identity.isAvailable) {
                    TextButton(
                        onClick = { onReimport(requireNotNull(identity.recoveryToken)) },
                        enabled = identity.recoveryToken != null,
                    ) {
                        Text(stringResource(R.string.ui_re_import))
                    }
                }
                val deleteKeyDescription = stringResource(R.string.ui_delete_1_s_private_key, identity.label)
                IconButton(
                    onClick = { onDelete(identity.id) },
                    modifier = Modifier.semantics {
                        contentDescription = deleteKeyDescription
                    },
                ) {
                    ConnectionsGlyphIcon(
                        glyph = ConnectionsGlyph.CLOSE,
                        modifier = Modifier.size(MaterialTheme.iconMetrics.compact),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
        }
    }
}

@Composable
private fun KnownHostsSection(
    knownHosts: List<KnownHostSummary>,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text(
                stringResource(R.string.settings_known_hosts_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
            )
            Text(
                stringResource(R.string.ui_host_verification_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (knownHosts.isEmpty()) item { EmptyLine(stringResource(R.string.ui_no_ssh_host_keys_trusted_yet)) }
        items(knownHosts, key = { "${it.host}:${it.algorithm}" }) { knownHost ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(knownHost.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${knownHost.algorithm} · ${knownHost.sha256Fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onForgetKnownHost(knownHost.host, knownHost.algorithm) }) {
                    Text(stringResource(R.string.ssh_connect_forget))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
        }
    }
}

@Composable
private fun ProfilesSection(
    profiles: List<SavedSshProfile>,
    onAdd: () -> Unit,
    onUse: (SavedSshProfile) -> Unit,
    onEdit: (SavedSshProfile) -> Unit,
    onDelete: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(
                title = stringResource(R.string.ui_saved_ssh_hosts),
                action = stringResource(R.string.connections_add_host),
                onAction = onAdd,
            )
        }
        if (profiles.isEmpty()) {
            item { EmptyLine(stringResource(R.string.ui_no_saved_hosts_yet)) }
        }
        items(profiles, key = { it.id }) { profile ->
            ToolRow(
                title = profile.label,
                detail = "${profile.username}@${profile.host}:${profile.port}" +
                    when {
                        profile.hasSavedPassword -> stringResource(R.string.ui_password_saved_suffix)
                        profile.connectionCompatibility == SavedHostConnectionCompatibility.MOSH_UNAVAILABLE ->
                            stringResource(R.string.ui_mosh_unavailable_suffix)
                        profile.connectionCompatibility ==
                            SavedHostConnectionCompatibility.PRIVATE_KEY_REQUIRES_FULL_UI ->
                            stringResource(R.string.ui_private_key_login_unavailable_suffix)
                        profile.connectionCompatibility ==
                            SavedHostConnectionCompatibility.KEYBOARD_INTERACTIVE_UNAVAILABLE ->
                            stringResource(R.string.ui_interactive_login_unavailable_suffix)
                        profile.connectionCompatibility ==
                            SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI ->
                            stringResource(R.string.ui_connection_options_unavailable_suffix)
                        else -> ""
                    },
                primaryAction = if (profile.canConnectFromCompatibilityUi) stringResource(R.string.ui_use) else stringResource(R.string.ui_unavailable),
                primaryEnabled = profile.canConnectFromCompatibilityUi,
                onPrimary = { onUse(profile) },
                onEdit = { onEdit(profile) },
                onDelete = { onDelete(profile.id) },
                deleteDescription = stringResource(R.string.ui_delete_profile_description, profile.label),
            )
        }
    }
}

@Composable
private fun SnippetsSection(
    snippets: List<CommandSnippet>,
    onAdd: () -> Unit,
    onSend: (Long) -> Unit,
    onEdit: (CommandSnippet) -> Unit,
    onDelete: (Long) -> Unit,
    managementOnly: Boolean = false,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(title = stringResource(R.string.ui_command_snippets), action = stringResource(R.string.connections_add_snippet), onAction = onAdd)
            if (!managementOnly) {
                Text(
                    text = stringResource(R.string.ui_sending_is_always_explicit_avoid_storing_passwords_or_tokens_in_commands),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        if (snippets.isEmpty()) {
            item { EmptyLine(stringResource(R.string.ui_no_snippets_yet)) }
        }
        items(snippets, key = { it.id }) { snippet ->
            val deleteDescription = stringResource(R.string.ui_delete_snippet_description, snippet.label)
            if (managementOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onEdit(snippet) }
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(snippet.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            snippet.command.replace('\n', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { onDelete(snippet.id) },
                        modifier = Modifier.semantics {
                            contentDescription = deleteDescription
                        },
                    ) {
                        ConnectionsGlyphIcon(ConnectionsGlyph.CLOSE, Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            } else ToolRow(
                title = snippet.label,
                detail = snippet.command.replace('\n', ' ').take(80) + if (snippet.appendEnter) "  ↵" else "",
                primaryAction = if (snippet.sendsImmediately) stringResource(R.string.terminal_send) else stringResource(R.string.ui_insert_unavailable),
                primaryEnabled = snippet.sendsImmediately,
                onPrimary = { onSend(snippet.id) },
                onEdit = { onEdit(snippet) },
                onDelete = { onDelete(snippet.id) },
                deleteDescription = stringResource(R.string.ui_delete_snippet_description, snippet.label),
                monospaceDetail = true,
            )
        }
    }
}

@Composable
private fun KeysSection(
    selectedKeys: List<TerminalExtraKey>,
    onSetVisible: (TerminalExtraKey, Boolean) -> Unit,
    onReplace: (TerminalExtraKey, TerminalExtraKey) -> Unit,
    onMove: (TerminalExtraKey, Int) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    var focusedKey by remember { mutableStateOf<TerminalExtraKey?>(null) }
    val groups = listOf(
        KeyPickerGroup(
            label = stringResource(R.string.ui_modifiers),
            keys = listOf(TerminalExtraKey.CTRL, TerminalExtraKey.ALT),
        ),
        KeyPickerGroup(
            label = stringResource(R.string.ui_navigation),
            keys = listOf(
                TerminalExtraKey.ESC,
                TerminalExtraKey.TAB,
                TerminalExtraKey.ENTER,
                TerminalExtraKey.BACKSPACE,
                TerminalExtraKey.INSERT,
                TerminalExtraKey.HOME,
                TerminalExtraKey.END,
                TerminalExtraKey.PAGE_UP,
                TerminalExtraKey.PAGE_DOWN,
                TerminalExtraKey.DELETE,
            ),
        ),
        KeyPickerGroup(
            label = stringResource(R.string.ui_arrows),
            keys = listOf(
                TerminalExtraKey.UP,
                TerminalExtraKey.DOWN,
                TerminalExtraKey.LEFT,
                TerminalExtraKey.RIGHT,
            ),
        ),
        KeyPickerGroup(
            label = stringResource(R.string.ui_ctrl_shortcuts),
            keys = listOf(
                TerminalExtraKey.CTRL_C,
                TerminalExtraKey.CTRL_D,
                TerminalExtraKey.CTRL_Z,
                TerminalExtraKey.CTRL_A,
                TerminalExtraKey.CTRL_B,
                TerminalExtraKey.CTRL_E,
                TerminalExtraKey.CTRL_R,
                TerminalExtraKey.CTRL_W,
                TerminalExtraKey.CTRL_L,
                TerminalExtraKey.CTRL_U,
                TerminalExtraKey.CTRL_K,
            ),
        ),
        KeyPickerGroup(
            label = stringResource(R.string.ui_symbols),
            keys = listOf(
                TerminalExtraKey.SLASH,
                TerminalExtraKey.PIPE,
                TerminalExtraKey.DASH,
                TerminalExtraKey.TILDE,
                TerminalExtraKey.BACKTICK,
                TerminalExtraKey.BACKSLASH,
                TerminalExtraKey.COLON,
                TerminalExtraKey.SEMICOLON,
                TerminalExtraKey.AT,
                TerminalExtraKey.HASH,
                TerminalExtraKey.DOLLAR,
                TerminalExtraKey.EQUALS,
                TerminalExtraKey.SPACE,
                TerminalExtraKey.EXCLAMATION,
                TerminalExtraKey.QUESTION,
                TerminalExtraKey.ASTERISK,
                TerminalExtraKey.PLUS,
                TerminalExtraKey.UNDERSCORE,
                TerminalExtraKey.PERIOD,
                TerminalExtraKey.COMMA,
                TerminalExtraKey.LEFT_PAREN,
                TerminalExtraKey.RIGHT_PAREN,
                TerminalExtraKey.LEFT_BRACKET,
                TerminalExtraKey.RIGHT_BRACKET,
                TerminalExtraKey.LEFT_BRACE,
                TerminalExtraKey.RIGHT_BRACE,
                TerminalExtraKey.SINGLE_QUOTE,
                TerminalExtraKey.DOUBLE_QUOTE,
                TerminalExtraKey.LESS_THAN,
                TerminalExtraKey.GREATER_THAN,
                TerminalExtraKey.AMPERSAND,
                TerminalExtraKey.CARET,
                TerminalExtraKey.PERCENT,
            ),
        ),
        KeyPickerGroup(
            label = stringResource(R.string.ui_function_keys),
            keys = listOf(
                TerminalExtraKey.F1,
                TerminalExtraKey.F2,
                TerminalExtraKey.F3,
                TerminalExtraKey.F4,
                TerminalExtraKey.F5,
                TerminalExtraKey.F6,
                TerminalExtraKey.F7,
                TerminalExtraKey.F8,
                TerminalExtraKey.F9,
                TerminalExtraKey.F10,
                TerminalExtraKey.F11,
                TerminalExtraKey.F12,
            ),
        ),
        KeyPickerGroup(
            label = stringResource(R.string.ui_special),
            keys = listOf(TerminalExtraKey.HIDE_KEYBOARD),
        ),
    )

    LaunchedEffect(selectedKeys) {
        if (focusedKey !in selectedKeys) focusedKey = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.ui_terminal_keys), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
                Button(onClick = onSave) { Text(stringResource(R.string.host_editor_save)) }
            }
            Text(
                text = stringResource(R.string.ui_tap_an_option_to_add_it_to_change_a_key_tap_it_in_the_live_deck_then_tap_its_replacement_b),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        item {
            Text(
                text = stringResource(R.string.ui_live_deck_1_d_keys, selectedKeys.size),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val columnCount = (selectedKeys.size + 1) / 2
                    KeyDeckPreviewRow(
                        keys = selectedKeys.take(columnCount),
                        columnCount = columnCount,
                        focusedKey = focusedKey,
                        onFocus = { focusedKey = it },
                    )
                    KeyDeckPreviewRow(
                        keys = selectedKeys.drop(columnCount),
                        columnCount = columnCount,
                        focusedKey = focusedKey,
                        onFocus = { focusedKey = it },
                    )
                }
            }
            focusedKey?.let { key ->
                val selectedIndex = selectedKeys.indexOf(key)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_selected_slot_1_d_2_s, selectedIndex + 1, key.label),
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.ui_tap_a_new_option_below_to_replace_this_key),
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = selectedIndex > 0,
                            onClick = { onMove(key, -1) },
                        ) {
                            ConnectionsGlyphIcon(
                                glyph = ConnectionsGlyph.BACK,
                                modifier = Modifier.size(MaterialTheme.iconMetrics.compact),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.ui_move_left))
                        }
                        TextButton(onClick = { onSetVisible(key, false) }) { Text(stringResource(R.string.project_remove)) }
                        TextButton(
                            enabled = selectedIndex in 0 until selectedKeys.lastIndex,
                            onClick = { onMove(key, 1) },
                        ) {
                            Text(stringResource(R.string.ui_move_right))
                            Spacer(Modifier.width(4.dp))
                            ConnectionsGlyphIcon(
                                glyph = ConnectionsGlyph.BACK,
                                modifier = Modifier
                                    .size(MaterialTheme.iconMetrics.compact)
                                    .graphicsLayer { rotationZ = 180f },
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.ui_available_keys),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        groups.forEach { group ->
            item(key = group.label) {
                Text(
                    text = group.label,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 9.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.keys.chunked(4).forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowKeys.forEach { key ->
                                KeyPickerButton(
                                    key = key,
                                    selectedIndex = selectedKeys.indexOf(key),
                                    onClick = {
                                        if (key in selectedKeys) {
                                            focusedKey = key
                                        } else {
                                            val keyToReplace = focusedKey
                                            if (keyToReplace == null) {
                                                onSetVisible(key, true)
                                            } else {
                                                onReplace(keyToReplace, key)
                                                focusedKey = key
                                            }
                                        }
                                    },
                                )
                            }
                            repeat(4 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private data class KeyPickerGroup(
    val label: String,
    val keys: List<TerminalExtraKey>,
)

@Composable
internal fun KeyDeckPreviewRow(
    keys: List<TerminalExtraKey>,
    columnCount: Int,
    focusedKey: TerminalExtraKey?,
    onFocus: (TerminalExtraKey) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        keys.forEach { key ->
            val focused = key == focusedKey
            val editKeyDescription = stringResource(R.string.ui_edit_1_s_terminal_key, key.label)
            Surface(
                onClick = { onFocus(key) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = editKeyDescription },
                shape = RoundedCornerShape(8.dp),
                color = if (focused) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                border = BorderStroke(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = key.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        repeat(columnCount - keys.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun RowScope.KeyPickerButton(
    key: TerminalExtraKey,
    selectedIndex: Int,
    onClick: () -> Unit,
) {
    val selected = selectedIndex >= 0
    val keyDescription = if (selected) {
        stringResource(R.string.ui_key_selected_description, key.label, selectedIndex + 1)
    } else {
        stringResource(R.string.ui_add_key_description, key.label)
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = keyDescription
            },
        shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = key.label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected) {
                Text(
                    text = "  ${selectedIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SectionAction(title: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
    )
}

@Composable
private fun ToolRow(
    title: String,
    detail: String,
    primaryAction: String,
    primaryEnabled: Boolean = true,
    onPrimary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    deleteDescription: String,
    monospaceDetail: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (monospaceDetail) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.project_edit)) }
        TextButton(onClick = onPrimary, enabled = primaryEnabled) { Text(primaryAction) }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = deleteDescription },
        ) {
            ConnectionsGlyphIcon(
                glyph = ConnectionsGlyph.CLOSE,
                modifier = Modifier.size(MaterialTheme.iconMetrics.compact),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
}

@Composable
private fun ProfileEditorDialog(
    profile: SavedSshProfile?,
    onDismiss: () -> Unit,
    onSave: (label: String, host: String, port: String, username: String) -> Unit,
) {
    var label by remember(profile?.id) { mutableStateOf(profile?.label.orEmpty()) }
    var host by remember(profile?.id) { mutableStateOf(profile?.host.orEmpty()) }
    var port by remember(profile?.id) { mutableStateOf(profile?.port?.toString() ?: "22") }
    var username by remember(profile?.id) { mutableStateOf(profile?.username.orEmpty()) }
    val valid = label.isNotBlank() && label.none(Char::isISOControl) &&
        host.isNotBlank() && host.none(Char::isWhitespace) &&
        username.isNotBlank() && username.none { it.isWhitespace() || it.isISOControl() } &&
        port.toIntOrNull()?.let { it in 1..65_535 } == true
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) stringResource(R.string.ui_save_ssh_host) else stringResource(R.string.ui_edit_ssh_host)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(label, { label = it.take(UserSettings.MAX_LABEL_LENGTH) }, label = { Text(stringResource(R.string.session_detail_name)) }, singleLine = true)
                OutlinedTextField(host, { host = it.take(UserSettings.MAX_HOST_LENGTH) }, label = { Text(stringResource(R.string.connections_field_host)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(username, { username = it.take(UserSettings.MAX_USERNAME_LENGTH) }, label = { Text(stringResource(R.string.host_editor_username)) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.host_editor_port)) }, modifier = Modifier.weight(0.48f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
                Text(
                    stringResource(R.string.ui_passwords_are_stored_only_from_the_connection_dialog_when_you_explicitly_opt_in),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(label, host, port, username) }) { Text(stringResource(R.string.host_editor_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SnippetEditorDialog(
    snippet: CommandSnippet?,
    onDismiss: () -> Unit,
    onSave: (label: String, command: String, appendEnter: Boolean) -> Unit,
) {
    var label by remember(snippet?.id) { mutableStateOf(snippet?.label.orEmpty()) }
    var command by remember(snippet?.id) { mutableStateOf(snippet?.command.orEmpty()) }
    var appendEnter by remember(snippet?.id) { mutableStateOf(snippet?.appendEnter ?: true) }
    val sequenceValid = runCatching { com.yanjiyu.terminalspike.settings.SnippetSequence.parse(command) }.isSuccess
    val valid = sequenceValid && label.isNotBlank() && label.none(Char::isISOControl) && command.isNotBlank() &&
        command.none { it.isISOControl() && it !in "\r\n\t" }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (snippet == null) stringResource(R.string.ui_new_command_snippet) else stringResource(R.string.ui_edit_command_snippet)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(label, { label = it.take(UserSettings.MAX_LABEL_LENGTH) }, label = { Text(stringResource(R.string.session_detail_name)) }, singleLine = true)
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(UserSettings.MAX_SNIPPET_LENGTH) },
                    label = { Text(stringResource(R.string.connections_field_command)) },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { appendEnter = !appendEnter },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = appendEnter, onCheckedChange = { appendEnter = it })
                    Text(stringResource(R.string.ui_press_enter_after_sending))
                }
                Text(stringResource(R.string.snippet_sequence_help), style = MaterialTheme.typography.bodySmall)
                if (!sequenceValid) Text(stringResource(R.string.snippet_sequence_invalid))
                Text(stringResource(R.string.ui_stored_encrypted_do_not_save_passwords_or_access_tokens_in_snippets), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(label, command, appendEnter) }) { Text(stringResource(R.string.host_editor_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
