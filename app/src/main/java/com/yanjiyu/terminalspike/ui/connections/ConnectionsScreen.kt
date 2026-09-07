package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.ui.AdaptivePrimaryNavigation
import com.yanjiyu.terminalspike.ui.AppDestination

/**
 * Every action surfaced by [ConnectionsScreen] is explicit. Nullable catalog actions are omitted
 * from the UI, so an integration that cannot perform an operation never presents a fake control.
 */
@Immutable
internal data class ConnectionsCallbacks(
    val onTabSelected: (ConnectionsTab) -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onHostSortSelected: (HostSort) -> Unit,
    val onFavouritesOnlyChanged: (Boolean) -> Unit,
    val onHostGroupSelected: (String?) -> Unit,
    val onClearHostFilters: () -> Unit,
    val onRetry: () -> Unit,
    val onAddHost: (() -> Unit)? = null,
    val onConnectHost: ((String) -> Unit)? = null,
    val onOpenSftp: ((String) -> Unit)? = null,
    val onEditHost: ((String) -> Unit)? = null,
    val onDeleteHost: ((String) -> Unit)? = null,
    val onImportKey: (() -> Unit)? = null,
    val onGenerateKey: (() -> Unit)? = null,
    val onCopyPublicKey: ((String) -> Unit)? = null,
    val onViewKeyFingerprint: ((String) -> Unit)? = null,
    val onRenameKey: ((String) -> Unit)? = null,
    val onDeleteKey: ((String) -> Unit)? = null,
    val onAddSnippet: (() -> Unit)? = null,
    val onInsertSnippet: ((String) -> Unit)? = null,
    val onRunSnippet: ((String) -> Unit)? = null,
    val onCopySnippet: ((String) -> Unit)? = null,
    val onEditSnippet: ((String) -> Unit)? = null,
    val onDeleteSnippet: ((String) -> Unit)? = null,
    val onSaveHost: (suspend (HostEditorSubmission) -> Result<Unit>)? = null,
    val onResolveHostEditorDraft: ((String, HostEditorDraft) -> HostEditorDraft)? = null,
    val onRetainHostEditorDraft: ((String, HostEditorDraft) -> Unit)? = null,
    val onClearHostEditorDraft: ((String) -> Unit)? = null,
    val onTestHost: ((HostEditorSubmission) -> Long)? = null,
    val onAnswerTestHostIdentity: ((Long, Long, HostIdentityDecision) -> Boolean)? = null,
    val onAnswerTestKeyboardInteractive: ((Long, Long, List<CharArray>) -> Boolean)? = null,
    val onCancelTestKeyboardInteractive: ((Long, Long) -> Boolean)? = null,
    val onCancelHostTest: ((Long?) -> Unit)? = null,
    val onConnectCatalogHost: ((HostConnectRequest) -> Unit)? = null,
    val onSaveSnippetModel: (suspend (Snippet) -> Result<Unit>)? = null,
    val onGenerateKeyRequest: (suspend (KeyGenerationRequest) -> Result<Unit>)? = null,
    val onRenameKeyMetadata: (suspend (String, String, String?) -> Result<Unit>)? = null,
    val onOpenMoshStatus: (() -> Unit)? = null,
    val nearbySshDiscoveryControllerFactory: NearbySshDiscoveryControllerFactory? = null,
)

private sealed interface PendingConnectionsEditor {
    data class Host(val persistentId: String?) : PendingConnectionsEditor
    data class Snippet(val persistentId: String?) : PendingConnectionsEditor
    data object GenerateKey : PendingConnectionsEditor
    data class RenameKey(val persistentId: String) : PendingConnectionsEditor
    data class KeyFingerprint(val persistentId: String) : PendingConnectionsEditor
    data class ConnectHost(val persistentId: String) : PendingConnectionsEditor
}

private fun PendingConnectionsEditor.saveToken(): String = when (this) {
    is PendingConnectionsEditor.Host -> "host:${persistentId.orEmpty()}"
    is PendingConnectionsEditor.Snippet -> "snippet:${persistentId.orEmpty()}"
    PendingConnectionsEditor.GenerateKey -> "generate-key"
    is PendingConnectionsEditor.RenameKey -> "rename-key:$persistentId"
    is PendingConnectionsEditor.KeyFingerprint -> "key-fingerprint:$persistentId"
    is PendingConnectionsEditor.ConnectHost -> "connect-host:$persistentId"
}

private fun decodePendingConnectionsEditor(token: String): PendingConnectionsEditor? = when {
    token == "generate-key" -> PendingConnectionsEditor.GenerateKey
    token.startsWith("host:") -> PendingConnectionsEditor.Host(
        token.removePrefix("host:").takeIf(String::isNotEmpty),
    )
    token.startsWith("snippet:") -> PendingConnectionsEditor.Snippet(
        token.removePrefix("snippet:").takeIf(String::isNotEmpty),
    )
    token.startsWith("rename-key:") -> token.removePrefix("rename-key:")
        .takeIf(String::isNotEmpty)
        ?.let { PendingConnectionsEditor.RenameKey(it) }
    token.startsWith("key-fingerprint:") -> token.removePrefix("key-fingerprint:")
        .takeIf(String::isNotEmpty)
        ?.let { PendingConnectionsEditor.KeyFingerprint(it) }
    token.startsWith("connect-host:") -> token.removePrefix("connect-host:")
        .takeIf(String::isNotEmpty)
        ?.let { PendingConnectionsEditor.ConnectHost(it) }
    else -> null
}

private fun PendingConnectionsEditor.existsIn(catalog: ConnectionsEditorCatalog): Boolean = when (this) {
    is PendingConnectionsEditor.Host -> persistentId == null ||
        catalog.hosts.any { it.draft.persistentId == persistentId }
    is PendingConnectionsEditor.Snippet -> persistentId == null ||
        catalog.snippets.any { it.id == persistentId }
    PendingConnectionsEditor.GenerateKey -> true
    is PendingConnectionsEditor.RenameKey -> catalog.keys.any { it.persistentId == persistentId }
    is PendingConnectionsEditor.KeyFingerprint -> catalog.keys.any { it.persistentId == persistentId }
    is PendingConnectionsEditor.ConnectHost ->
        catalog.hosts.any { it.draft.persistentId == persistentId }
}

private sealed interface PendingCatalogDeletion {
    val id: String
    val name: String

    data class Host(
        override val id: String,
        override val name: String,
    ) : PendingCatalogDeletion

    data class Key(
        override val id: String,
        override val name: String,
        val referenceCount: Int,
    ) : PendingCatalogDeletion

    data class Snippet(
        override val id: String,
        override val name: String,
    ) : PendingCatalogDeletion
}

@Composable
internal fun ConnectionsScreen(
    state: ConnectionsUiState,
    callbacks: ConnectionsCallbacks,
    onOpenWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    initialHostEditorId: String? = null,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    safeContentInsets: WindowInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Vertical)
        .union(WindowInsets.displayCutout),
    nowEpochMillis: Long = System.currentTimeMillis(),
    moshAvailable: Boolean = false,
    displayedTab: ConnectionsTab = state.selectedTab,
    primaryDestination: AppDestination = AppDestination.CONNECTIONS,
) {
    val context = LocalContext.current
    val screenDescription = stringResource(R.string.connections_screen_description)
    val defaultNearbySshDiscoveryFactory = remember(context) {
        androidNearbySshDiscoveryControllerFactory(context)
    }
    val nearbySshDiscoveryFactory = callbacks.nearbySshDiscoveryControllerFactory
        ?: defaultNearbySshDiscoveryFactory
    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKeyId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSnippetId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletion by remember { mutableStateOf<PendingCatalogDeletion?>(null) }
    var pendingSnippetExecution by remember { mutableStateOf<SnippetRowUi?>(null) }
    var pendingEditorToken by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingEditor = pendingEditorToken?.let(::decodePendingConnectionsEditor)
    val readyEditorCatalog = (state.loadState as? ConnectionsLoadState.Ready)?.editorCatalog
    val editorCatalog = readyEditorCatalog ?: ConnectionsEditorCatalog()
    val displayedState = state.copy(selectedTab = displayedTab)

    LaunchedEffect(initialHostEditorId) {
        if (initialHostEditorId != null) {
            pendingEditorToken = PendingConnectionsEditor.Host(initialHostEditorId).saveToken()
        }
    }

    LaunchedEffect(pendingEditorToken, readyEditorCatalog) {
        val token = pendingEditorToken ?: return@LaunchedEffect
        val editor = decodePendingConnectionsEditor(token) ?: run {
            pendingEditorToken = null
            return@LaunchedEffect
        }
        val catalog = readyEditorCatalog ?: return@LaunchedEffect
        if (!editor.existsIn(catalog)) {
            if (editor is PendingConnectionsEditor.Host) {
                callbacks.onClearHostEditorDraft?.invoke(token)
            }
            pendingEditorToken = null
        }
    }

    val requestSnippetRun: (SnippetRowUi) -> Unit = { snippet ->
        if (snippet.requiresExecutionConfirmation) {
            pendingSnippetExecution = snippet
        } else {
            callbacks.onRunSnippet?.invoke(snippet.id)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        AdaptivePrimaryNavigation(
            selected = primaryDestination,
            onWorkspace = onOpenWorkspace,
            onConnections = onOpenTerminal,
            onSettings = onOpenSettings,
            modifier = Modifier.fillMaxSize(),
            safeContentInsets = safeContentInsets,
        ) { contentModifier, navigationExpanded ->
            BoxWithConstraints(modifier = contentModifier) {
                val extraTextScale = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
                val fullHeaderMinimumHeight = CompactConnectionsFullHeaderBaseHeight +
                    CompactConnectionsFullHeaderTextScaleAllowance * extraTextScale
                val showFullHeader = maxHeight >= fullHeaderMinimumHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .testTag(ConnectionsScreenTestTag)
                        .semantics { contentDescription = screenDescription },
                ) {
                    ConnectionsHeader(
                        selectedTab = displayedTab,
                        searchQuery = state.searchQuery,
                        hostSort = state.hostSort,
                        hostFilters = state.hostFilters,
                        hostGroups = (state.loadState as? ConnectionsLoadState.Ready)?.hostGroups.orEmpty(),
                        catalogReady = state.loadState is ConnectionsLoadState.Ready,
                        onNavigateBack = onNavigateBack,
                        callbacks = callbacks.copy(
                            onAddHost = callbacks.onSaveHost?.let {
                                { pendingEditorToken = PendingConnectionsEditor.Host(null).saveToken() }
                            } ?: callbacks.onAddHost,
                            onGenerateKey = callbacks.onGenerateKeyRequest?.let {
                                { pendingEditorToken = PendingConnectionsEditor.GenerateKey.saveToken() }
                            } ?: callbacks.onGenerateKey,
                            onAddSnippet = callbacks.onSaveSnippetModel?.let {
                                { pendingEditorToken = PendingConnectionsEditor.Snippet(null).saveToken() }
                            } ?: callbacks.onAddSnippet,
                        ),
                        showTitle = showFullHeader,
                        showSearch = showFullHeader,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (val presentation = displayedState.presentation()) {
                            is ConnectionsPresentationUi.Loading -> ConnectionsLoadingState()
                            is ConnectionsPresentationUi.Error -> ConnectionsErrorState(
                                message = presentation.message,
                                onRetry = callbacks.onRetry,
                            )
                            is ConnectionsPresentationUi.Ready -> ConnectionsReadyContent(
                                rows = presentation.rows,
                                navigationExpanded = navigationExpanded,
                                nowEpochMillis = nowEpochMillis,
                                selectedHostId = selectedHostId,
                                selectedKeyId = selectedKeyId,
                                selectedSnippetId = selectedSnippetId,
                                onSelectHost = { selectedHostId = it },
                                onSelectKey = { selectedKeyId = it },
                                onSelectSnippet = { selectedSnippetId = it },
                                onConnectHost = callbacks.onConnectCatalogHost?.let { connect ->
                                    { id ->
                                        val seed = editorCatalog.hosts.firstOrNull {
                                            it.draft.persistentId == id
                                        }
                                        val key = seed?.draft?.keyIdentityId?.let { keyId ->
                                            editorCatalog.keys.firstOrNull { it.persistentId == keyId }
                                        }
                                        val requiresSecret = when (seed?.draft?.authenticationMethod) {
                                            HostAuthenticationMethod.PASSWORD ->
                                                !seed.savedSecretAvailable
                                            HostAuthenticationMethod.KEYBOARD_INTERACTIVE -> false
                                            HostAuthenticationMethod.PRIVATE_KEY ->
                                                key?.passphraseProtected == true && !seed.savedSecretAvailable
                                            null -> true
                                        }
                                        val moshBlocked = seed?.draft?.protocol ==
                                            com.yanjiyu.terminalspike.core.model.ConnectionProtocol.MOSH &&
                                            !moshAvailable
                                        if (requiresSecret || moshBlocked) {
                                            pendingEditorToken = PendingConnectionsEditor.ConnectHost(id).saveToken()
                                        } else {
                                            connect(HostConnectRequest(id))
                                        }
                                    }
                                } ?: callbacks.onConnectHost,
                                onOpenSftp = callbacks.onOpenSftp,
                                onEditHost = callbacks.onSaveHost?.let {
                                    { id -> pendingEditorToken = PendingConnectionsEditor.Host(id).saveToken() }
                                } ?: callbacks.onEditHost,
                                onDeleteHost = callbacks.onDeleteHost?.let {
                                    { host -> pendingDeletion = PendingCatalogDeletion.Host(host.id, host.displayName) }
                                },
                                onCopyPublicKey = callbacks.onCopyPublicKey,
                                onViewKeyFingerprint = if (editorCatalog.keys.isNotEmpty()) {
                                    { id ->
                                        pendingEditorToken = PendingConnectionsEditor.KeyFingerprint(id).saveToken()
                                    }
                                } else {
                                    callbacks.onViewKeyFingerprint
                                },
                                onRenameKey = callbacks.onRenameKeyMetadata?.let {
                                    { id -> pendingEditorToken = PendingConnectionsEditor.RenameKey(id).saveToken() }
                                } ?: callbacks.onRenameKey,
                                onDeleteKey = callbacks.onDeleteKey?.let {
                                    { key ->
                                        val referenceCount = editorCatalog.keys
                                            .firstOrNull { it.persistentId == key.id }
                                            ?.referenceCount
                                            ?: 0
                                        pendingDeletion = PendingCatalogDeletion.Key(
                                            id = key.id,
                                            name = key.name,
                                            referenceCount = referenceCount,
                                        )
                                    }
                                },
                                onInsertSnippet = callbacks.onInsertSnippet,
                                onRunSnippet = callbacks.onRunSnippet?.let { requestSnippetRun },
                                onCopySnippet = callbacks.onCopySnippet,
                                onEditSnippet = callbacks.onSaveSnippetModel?.let {
                                    { id -> pendingEditorToken = PendingConnectionsEditor.Snippet(id).saveToken() }
                                } ?: callbacks.onEditSnippet,
                                onDeleteSnippet = callbacks.onDeleteSnippet?.let {
                                    { snippet ->
                                        pendingDeletion = PendingCatalogDeletion.Snippet(snippet.id, snippet.name)
                                    }
                                },
                                onEmptyAction = when (displayedTab) {
                                    ConnectionsTab.HOSTS -> null
                                    ConnectionsTab.KEYS -> callbacks.onImportKey
                                        ?: callbacks.onGenerateKeyRequest?.let {
                                            {
                                                pendingEditorToken =
                                                    PendingConnectionsEditor.GenerateKey.saveToken()
                                            }
                                        }
                                        ?: callbacks.onGenerateKey
                                    ConnectionsTab.SNIPPETS -> callbacks.onSaveSnippetModel?.let {
                                        { pendingEditorToken = PendingConnectionsEditor.Snippet(null).saveToken() }
                                    } ?: callbacks.onAddSnippet
                                },
                                onClearNoResults = {
                                    callbacks.onSearchQueryChanged("")
                                    callbacks.onClearHostFilters()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeletion?.let { deletion ->
        CatalogDeletionConfirmationDialog(
            deletion = deletion,
            onDismiss = { pendingDeletion = null },
            onConfirm = {
                when (deletion) {
                    is PendingCatalogDeletion.Host -> callbacks.onDeleteHost?.invoke(deletion.id)
                    is PendingCatalogDeletion.Key -> if (deletion.referenceCount == 0) {
                        callbacks.onDeleteKey?.invoke(deletion.id)
                    }
                    is PendingCatalogDeletion.Snippet -> callbacks.onDeleteSnippet?.invoke(deletion.id)
                }
                pendingDeletion = null
            },
        )
    }

    pendingSnippetExecution?.let { snippet ->
        SnippetExecutionConfirmationDialog(
            snippet = snippet,
            onDismiss = { pendingSnippetExecution = null },
            onConfirm = {
                callbacks.onRunSnippet?.invoke(snippet.id)
                pendingSnippetExecution = null
            },
        )
    }

    when (val editor = pendingEditor) {
        null -> Unit
        is PendingConnectionsEditor.Host -> {
            val token = pendingEditorToken
            val catalog = readyEditorCatalog
            if (token != null && catalog != null) {
                val seed = editor.persistentId?.let { id ->
                    catalog.hosts.firstOrNull { it.draft.persistentId == id }
                }
                val catalogInitial = seed?.draft ?: catalog.newHostDraft()
                if (editor.persistentId == null || seed != null) {
                    val initial = callbacks.onResolveHostEditorDraft
                        ?.invoke(token, catalogInitial)
                        ?: catalogInitial
                    val nearbySshDiscoveryController = remember(token, nearbySshDiscoveryFactory) {
                        if (editor.persistentId == null) nearbySshDiscoveryFactory.create() else null
                    }
                    HostEditorDialog(
                        editorToken = token,
                        initial = initial,
                        savedSecretAvailable = seed?.savedSecretAvailable == true,
                        catalog = catalog,
                        moshAvailable = moshAvailable,
                        onDraftChanged = { draft ->
                            callbacks.onRetainHostEditorDraft?.invoke(token, draft)
                        },
                        onDismiss = {
                            callbacks.onClearHostEditorDraft?.invoke(token)
                            pendingEditorToken = null
                        },
                        onDelete = editor.persistentId?.let { persistentId ->
                            callbacks.onDeleteHost?.let {
                                {
                                    pendingDeletion = PendingCatalogDeletion.Host(
                                        id = persistentId,
                                        name = initial.displayName,
                                    )
                                    pendingEditorToken = null
                                }
                            }
                        },
                        onSave = { submission ->
                            callbacks.onSaveHost?.invoke(submission) ?: unavailableEditorOperation()
                        },
                        onTest = callbacks.onTestHost,
                        testState = state.hostConnectionTest,
                        onAnswerHostIdentity = callbacks.onAnswerTestHostIdentity,
                        onAnswerKeyboardInteractive = callbacks.onAnswerTestKeyboardInteractive,
                        onCancelKeyboardInteractive = callbacks.onCancelTestKeyboardInteractive,
                        onCancelTest = callbacks.onCancelHostTest,
                        onOpenMoshStatus = callbacks.onOpenMoshStatus,
                        nearbySshDiscoveryController = nearbySshDiscoveryController,
                    )
                }
            }
        }
        is PendingConnectionsEditor.Snippet -> {
            val catalog = readyEditorCatalog
            if (catalog != null) {
                val existing = editor.persistentId
                    ?.let { id -> catalog.snippets.firstOrNull { it.id == id } }
                if (editor.persistentId == null || existing != null) {
                    SnippetEditorDialog(
                        initial = existing?.toEditorDraft() ?: SnippetEditorDraft(),
                        onDismiss = { pendingEditorToken = null },
                        onSave = { snippet ->
                            callbacks.onSaveSnippetModel?.invoke(snippet)
                                ?: unavailableEditorOperation()
                        },
                    )
                }
            }
        }
        PendingConnectionsEditor.GenerateKey -> if (readyEditorCatalog != null) {
            GenerateKeyDialog(
                onDismiss = { pendingEditorToken = null },
                onGenerate = { request ->
                    callbacks.onGenerateKeyRequest?.invoke(request) ?: unavailableEditorOperation()
                },
            )
        }
        is PendingConnectionsEditor.RenameKey -> readyEditorCatalog?.keys
            ?.firstOrNull { it.persistentId == editor.persistentId }
            ?.let { key ->
                RenameKeyDialog(
                    key = key,
                    onDismiss = { pendingEditorToken = null },
                    onRename = { id, name, comment ->
                        callbacks.onRenameKeyMetadata?.invoke(id, name, comment)
                            ?: unavailableEditorOperation()
                    },
                )
            }
        is PendingConnectionsEditor.KeyFingerprint -> readyEditorCatalog?.keys
            ?.firstOrNull { it.persistentId == editor.persistentId }
            ?.let { key ->
                KeyFingerprintDialog(key = key, onDismiss = { pendingEditorToken = null })
            }
        is PendingConnectionsEditor.ConnectHost -> readyEditorCatalog?.let { catalog ->
            catalog.hosts
                .firstOrNull { it.draft.persistentId == editor.persistentId }
                ?.let { host ->
                    HostAuthenticationPromptDialog(
                        host = host,
                        key = host.draft.keyIdentityId?.let { keyId ->
                            catalog.keys.firstOrNull { it.persistentId == keyId }
                        },
                        moshAvailable = moshAvailable,
                        onDismiss = { pendingEditorToken = null },
                        onConnect = { request -> callbacks.onConnectCatalogHost?.invoke(request) },
                        onConnectWithSsh = {
                            request -> callbacks.onConnectCatalogHost?.invoke(request)
                        },
                    )
                }
        }
    }
}

@Composable
private fun CatalogDeletionConfirmationDialog(
    deletion: PendingCatalogDeletion,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title: String
    val detail: String
    val action: String?
    when (deletion) {
        is PendingCatalogDeletion.Host -> {
            title = stringResource(R.string.connections_delete_title, deletion.name)
            detail = stringResource(R.string.connections_delete_host_detail)
            action = stringResource(R.string.connections_delete_host)
        }
        is PendingCatalogDeletion.Key -> {
            if (deletion.referenceCount > 0) {
                title = stringResource(R.string.connections_key_in_use_title, deletion.name)
                detail = pluralStringResource(
                    R.plurals.connections_key_in_use_detail,
                    deletion.referenceCount,
                    deletion.referenceCount,
                )
                action = null
            } else {
                title = stringResource(R.string.connections_delete_title, deletion.name)
                detail = stringResource(R.string.connections_delete_key_detail)
                action = stringResource(R.string.connections_delete_key)
            }
        }
        is PendingCatalogDeletion.Snippet -> {
            title = stringResource(R.string.connections_delete_title, deletion.name)
            detail = stringResource(R.string.connections_delete_snippet_detail)
            action = stringResource(R.string.connections_delete_snippet)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            if (action == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            } else {
                Button(
                    onClick = onConfirm,
                    modifier = if (deletion is PendingCatalogDeletion.Host) {
                        Modifier.testTag(HostEditorConfirmDeleteTestTag)
                    } else {
                        Modifier
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(action)
                }
            }
        },
        dismissButton = {
            if (action != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun SnippetExecutionConfirmationDialog(
    snippet: SnippetRowUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_run_snippet_title, snippet.name)) },
        text = {
            Text(stringResource(R.string.connections_run_snippet_detail))
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.connections_run_snippet)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val CompactConnectionsFullHeaderBaseHeight = 320.dp
private val CompactConnectionsFullHeaderTextScaleAllowance = 80.dp

private val SnippetRowUi.requiresExecutionConfirmation: Boolean
    get() = ('\n' in command || '\r' in command) &&
        (tapAction == SnippetTapAction.INSERT || confirmMultilineExecution)

private fun unavailableEditorOperation(): Result<Unit> = Result.failure(
    IllegalStateException("The catalog editor operation is unavailable."),
)

internal data class CatalogCounts(
    val hosts: Int,
    val keys: Int,
    val snippets: Int,
)

private val ConnectionsLoadState.catalogCounts: CatalogCounts
    get() = when (this) {
        ConnectionsLoadState.Loading, is ConnectionsLoadState.Error -> CatalogCounts(0, 0, 0)
        is ConnectionsLoadState.Ready -> CatalogCounts(hosts.size, keys.size, snippets.size)
    }

internal const val ConnectionsScreenTestTag = "connections-screen"
internal const val ConnectionsHeaderTestTag = "connections-header"
internal const val ConnectionsTabsTestTag = "connections-tabs"
internal const val ConnectionsCompactListTestTag = "connections-compact-list"
internal const val ConnectionsExpandedListTestTag = "connections-expanded-list"
internal const val ConnectionsExpandedDetailTestTag = "connections-expanded-detail"
internal const val ConnectionsLoadingTestTag = "connections-loading"
internal const val ConnectionsErrorTestTag = "connections-error"
internal const val ConnectionsEmptyTestTag = "connections-empty"
