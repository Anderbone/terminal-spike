package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.core.data.repository.SshKeyGenerationAlgorithm
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.WipeableSecretInput
import com.yanjiyu.terminalspike.ui.WipeableSecretInputState
import com.yanjiyu.terminalspike.ui.resolve
import com.yanjiyu.terminalspike.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HostEditorDialog(
    initial: HostEditorDraft,
    editorToken: String = "host:${initial.persistentId.orEmpty()}",
    savedSecretAvailable: Boolean,
    catalog: ConnectionsEditorCatalog,
    moshAvailable: Boolean,
    onDraftChanged: ((HostEditorDraft) -> Unit)? = null,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: suspend (HostEditorSubmission) -> Result<Unit>,
    onTest: ((HostEditorSubmission) -> Long)?,
    testState: HostConnectionTestUiState = HostConnectionTestUiState.Idle,
    onAnswerHostIdentity: ((Long, Long, HostIdentityDecision) -> Boolean)? = null,
    onAnswerKeyboardInteractive: ((Long, Long, List<CharArray>) -> Boolean)? = null,
    onCancelKeyboardInteractive: ((Long, Long) -> Boolean)? = null,
    onCancelTest: ((Long?) -> Unit)? = null,
    onOpenMoshStatus: (() -> Unit)?,
    nearbySshDiscoveryController: NearbySshDiscoveryController? = null,
) {
    // Endpoint, username, and startup-command text must never enter SavedState. The production
    // screen supplies an in-memory ViewModel draft keyed by [editorToken] for Activity recreation.
    var draft by remember(editorToken) {
        mutableStateOf(initial)
    }
    var testedDraft by remember(editorToken) {
        mutableStateOf(initial)
    }
    val secretState = remember(editorToken) { WipeableSecretInputState() }
    var transientSecretWasEntered by rememberSaveable(editorToken) {
        mutableStateOf(false)
    }
    val initialSavePassword = savedSecretAvailable || initial.persistentId == null
    var savePassword by rememberSaveable(editorToken) {
        mutableStateOf(initialSavePassword)
    }
    var errors by remember { mutableStateOf(HostEditorErrors()) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var discardConfirmation by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var openMoshStatusAfterDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailure by remember { mutableStateOf<UiText?>(null) }
    var testOperationToken by remember(editorToken) {
        mutableStateOf(testState.operationToken)
    }
    var replacementReviewToken by remember(editorToken) {
        mutableStateOf<Long?>(null)
    }
    val nearbySshDiscoveryState = nearbySshDiscoveryController
        ?.state
        ?.collectAsState()
        ?.value
        ?: NearbySshDiscoveryState.Idle
    val scope = rememberCoroutineScope()
    val editorScrollState = rememberScrollState()
    val visibleTestState = testState.takeIf { state ->
        state.operationToken == testOperationToken && draft == testedDraft
    } ?: HostConnectionTestUiState.Idle
    val testing = visibleTestState is HostConnectionTestUiState.Running ||
        visibleTestState is HostConnectionTestUiState.AwaitingHostIdentity ||
        visibleTestState is HostConnectionTestUiState.AwaitingKeyboardInteractive
    val testResult = (visibleTestState as? HostConnectionTestUiState.Complete)?.result
    val isDirty = draft != initial || secretState.hasValue || savePassword != initialSavePassword
    val transientSecretNeedsReentry = transientSecretWasEntered && !secretState.hasValue
    val secretReentryMessage = when (draft.authenticationMethod) {
        HostAuthenticationMethod.PASSWORD -> uiText(R.string.host_editor_secret_reenter_password)
        HostAuthenticationMethod.PRIVATE_KEY ->
            uiText(R.string.host_editor_secret_reenter_passphrase)
        HostAuthenticationMethod.KEYBOARD_INTERACTIVE ->
            uiText(R.string.host_editor_secret_reenter_response)
    }

    LaunchedEffect(saveFailure, transientSecretNeedsReentry) {
        if (saveFailure != null || transientSecretNeedsReentry) editorScrollState.scrollTo(0)
    }

    if (nearbySshDiscoveryController != null) {
        DisposableEffect(nearbySshDiscoveryController) {
            onDispose { nearbySshDiscoveryController.close() }
        }
    }

    LaunchedEffect(visibleTestState) {
        val changed = (visibleTestState as? HostConnectionTestUiState.AwaitingHostIdentity)
            ?.prompt as? HostIdentityPrompt.Changed
        if (replacementReviewToken != changed?.promptToken) replacementReviewToken = null
    }

    fun cancelOwnedTest() {
        testOperationToken?.let { token -> onCancelTest?.invoke(token) }
        testOperationToken = null
        replacementReviewToken = null
    }

    fun updateDraft(next: HostEditorDraft) {
        if (next == draft) return
        if (testOperationToken != null) cancelOwnedTest()
        draft = next
        onDraftChanged?.invoke(next)
    }

    LaunchedEffect(nearbySshDiscoveryState) {
        val selected = nearbySshDiscoveryState as? NearbySshDiscoveryState.Selected
            ?: return@LaunchedEffect
        updateDraft(draft.prefillFromNearbySsh(selected.endpoint))
    }

    fun updateSavePassword(next: Boolean) {
        if (next == savePassword) return
        if (testOperationToken != null) cancelOwnedTest()
        savePassword = next
    }

    val editorControlsEnabled = !testing && !saving

    fun requestDismiss() {
        if (saving) return
        nearbySshDiscoveryController?.cancel()
        openMoshStatusAfterDiscard = false
        if (isDirty) {
            discardConfirmation = true
        } else {
            cancelOwnedTest()
            secretState.wipe()
            onDismiss()
        }
    }

    fun submissionOrNull(forTest: Boolean): HostEditorSubmission? {
        val validation = validateHostEditor(draft, catalog.keys.mapTo(mutableSetOf()) { it.persistentId })
        var nextErrors = validation.errors
        val authenticationError = when (draft.authenticationMethod) {
            HostAuthenticationMethod.PASSWORD -> when {
                transientSecretNeedsReentry ->
                    uiText(R.string.host_editor_secret_reenter_password)
                forTest && !secretState.hasValue && !savedSecretAvailable ->
                    uiText(R.string.host_editor_password_test_required)
                !forTest && savePassword && !secretState.hasValue && !savedSecretAvailable ->
                    uiText(R.string.host_editor_password_save_required)
                !forTest && savePassword && !secretState.hasValue && savedSecretAvailable &&
                    authenticationEndpointChanged(initial, draft) ->
                    uiText(R.string.host_editor_password_endpoint_changed)
                else -> nextErrors.authentication
            }
            HostAuthenticationMethod.PRIVATE_KEY -> if (forTest && transientSecretNeedsReentry) {
                uiText(R.string.host_editor_secret_reenter_passphrase)
            } else nextErrors.authentication
            HostAuthenticationMethod.KEYBOARD_INTERACTIVE -> when {
                forTest && transientSecretNeedsReentry ->
                    uiText(R.string.host_editor_secret_reenter_response)
                else -> nextErrors.authentication
            }
        }
        nextErrors = nextErrors.copy(authentication = authenticationError)
        errors = nextErrors
        if (
            nextErrors.group != null ||
            nextErrors.tag != null ||
            nextErrors.startupCommand != null ||
            nextErrors.keepalive != null ||
            nextErrors.moshPort != null ||
            nextErrors.moshServerCommand != null ||
            nextErrors.moshLocale != null
        ) {
            advancedExpanded = true
        }
        val value = validation.value?.takeIf { nextErrors.isEmpty } ?: return null
        return HostEditorSubmission(
            value = value,
            secret = secretState.takeChars(),
            savePassword = draft.authenticationMethod == HostAuthenticationMethod.PASSWORD &&
                savePassword,
        )
    }

    fun toggleConnectionTest() {
        if (testing) {
            cancelOwnedTest()
            return
        }
        // A new test replaces the exact prior completed presentation.
        cancelOwnedTest()
        val submission = submissionOrNull(forTest = true) ?: return
        testedDraft = draft
        try {
            testOperationToken = onTest?.invoke(submission)
        } catch (_: Exception) {
            submission.wipe()
            saveFailure = uiText(R.string.host_editor_test_start_failed)
        }
    }

    fun saveHost() {
        val submission = submissionOrNull(forTest = false) ?: return
        saving = true
        saveFailure = null
        scope.launch {
            val result = try {
                onSave(submission)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                submission.wipe()
            }
            if (result.isSuccess) {
                secretState.wipe()
                transientSecretWasEntered = false
                cancelOwnedTest()
                onDismiss()
            } else {
                saveFailure = uiText(R.string.host_editor_save_failed)
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = ::requestDismiss,
        modifier = Modifier.testTag(HostEditorDialogTestTag),
        title = {
            Text(
                stringResource(
                    if (initial.persistentId == null) {
                        R.string.host_editor_add_title
                    } else {
                        R.string.host_editor_edit_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(editorScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                saveFailure?.let {
                    Text(
                        it.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(EditorPersistenceErrorTestTag),
                    )
                }
                if (transientSecretNeedsReentry) {
                    Text(
                        secretReentryMessage.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(EditorSecretReentryTestTag),
                    )
                }
                Text(
                    stringResource(R.string.host_editor_connection_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.host_editor_protocol),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConnectionProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = draft.protocol == protocol,
                            onClick = { updateDraft(draft.copy(protocol = protocol)) },
                            enabled = editorControlsEnabled,
                            label = {
                                Text(
                                    if (protocol == ConnectionProtocol.SSH) {
                                        "SSH"
                                    } else {
                                        stringResource(R.string.protocol_mosh)
                                    },
                                )
                            },
                        )
                    }
                }
                if (draft.protocol == ConnectionProtocol.MOSH && !moshAvailable) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.host_editor_mosh_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        onOpenMoshStatus?.let { open ->
                            TextButton(
                                onClick = {
                                    if (isDirty) {
                                        openMoshStatusAfterDiscard = true
                                        discardConfirmation = true
                                    } else {
                                        onDismiss()
                                        open()
                                    }
                                },
                                enabled = !saving && !testing,
                            ) { Text(stringResource(R.string.host_editor_view_mosh_status)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.hostname,
                    onValueChange = { updateDraft(draft.copy(hostname = it.take(253))) },
                    modifier = Modifier.fillMaxWidth().testTag(HostEditorHostnameTestTag),
                    label = { Text(stringResource(R.string.host_editor_hostname)) },
                    supportingText = errors.hostname?.let { message ->
                        ({ Text(message.resolve()) })
                    },
                    isError = errors.hostname != null,
                    enabled = editorControlsEnabled,
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.username,
                        onValueChange = { updateDraft(draft.copy(username = it.take(64))) },
                        modifier = Modifier.weight(1f).testTag(HostEditorUsernameTestTag),
                        label = { Text(stringResource(R.string.host_editor_username)) },
                        supportingText = errors.username?.let { message ->
                            ({ Text(message.resolve()) })
                        },
                        isError = errors.username != null,
                        enabled = editorControlsEnabled,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.port,
                        onValueChange = { value ->
                            updateDraft(
                                draft.copy(port = value.filter(Char::isDigit).take(5)),
                            )
                        },
                        modifier = Modifier.weight(0.48f).testTag(HostEditorPortTestTag),
                        label = { Text(stringResource(R.string.host_editor_port)) },
                        supportingText = errors.port?.let { message ->
                            ({ Text(message.resolve()) })
                        },
                        isError = errors.port != null,
                        enabled = editorControlsEnabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                Text(
                    stringResource(R.string.host_editor_authentication),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HostAuthenticationMethod.entries.forEach { method ->
                        FilterChip(
                            selected = draft.authenticationMethod == method,
                            enabled = editorControlsEnabled &&
                                (method != HostAuthenticationMethod.PRIVATE_KEY || catalog.keys.isNotEmpty()),
                            onClick = {
                                updateDraft(
                                    draft.copy(
                                        authenticationMethod = method,
                                        keyIdentityId = if (
                                            method == HostAuthenticationMethod.PRIVATE_KEY
                                        ) {
                                            draft.keyIdentityId
                                                ?: catalog.keys.firstOrNull()?.persistentId
                                        } else {
                                            null
                                        },
                                    ),
                                )
                                secretState.wipe()
                                transientSecretWasEntered = false
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (method) {
                                            HostAuthenticationMethod.PASSWORD ->
                                                R.string.host_editor_auth_password
                                            HostAuthenticationMethod.PRIVATE_KEY ->
                                                R.string.host_editor_auth_private_key
                                            HostAuthenticationMethod.KEYBOARD_INTERACTIVE ->
                                                R.string.host_editor_auth_interactive
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                if (draft.authenticationMethod == HostAuthenticationMethod.PRIVATE_KEY) {
                    SimpleOptionMenu(
                        label = stringResource(R.string.host_editor_ssh_key),
                        selectedId = draft.keyIdentityId,
                        options = catalog.keys.map { CatalogProfileOption(it.persistentId, it.name) },
                        onSelected = { updateDraft(draft.copy(keyIdentityId = it)) },
                        enabled = editorControlsEnabled,
                    )
                }
                if (draft.authenticationMethod != HostAuthenticationMethod.PRIVATE_KEY) {
                    val label = if (draft.authenticationMethod == HostAuthenticationMethod.PASSWORD) {
                        stringResource(R.string.host_editor_auth_password)
                    } else {
                        stringResource(R.string.host_editor_interactive_response)
                    }
                    WipeableSecretInput(
                        state = secretState,
                        label = label,
                        testTag = HostEditorSecretTestTag,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = editorControlsEnabled,
                        supportingText = when {
                            draft.authenticationMethod ==
                                HostAuthenticationMethod.KEYBOARD_INTERACTIVE ->
                                stringResource(R.string.host_editor_secret_session_only)
                            savedSecretAvailable && !secretState.hasValue ->
                                stringResource(R.string.host_editor_retain_saved_password)
                            else -> stringResource(R.string.host_editor_password_storage_condition)
                        },
                        onPresenceChanged = { present ->
                            if (present) {
                                if (testOperationToken != null) cancelOwnedTest()
                                transientSecretWasEntered = true
                            }
                        },
                    )
                    if (draft.authenticationMethod == HostAuthenticationMethod.PASSWORD) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = savePassword,
                                onCheckedChange = ::updateSavePassword,
                                modifier = Modifier.testTag(HostEditorSavePasswordTestTag),
                                enabled = editorControlsEnabled,
                            )
                            Column {
                                Text(stringResource(R.string.host_editor_save_password))
                                Text(
                                    stringResource(R.string.host_editor_save_password_detail),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                } else {
                    WipeableSecretInput(
                        state = secretState,
                        label = stringResource(R.string.host_editor_key_passphrase_test),
                        testTag = HostEditorSecretTestTag,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = editorControlsEnabled,
                        supportingText = stringResource(
                            R.string.host_editor_key_passphrase_not_stored,
                        ),
                        onPresenceChanged = { present ->
                            if (present) {
                                if (testOperationToken != null) cancelOwnedTest()
                                transientSecretWasEntered = true
                            }
                        },
                    )
                }
                errors.authentication
                    ?.takeUnless { transientSecretNeedsReentry && it == secretReentryMessage }
                    ?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = { updateDraft(draft.copy(displayName = it.take(96))) },
                    modifier = Modifier.fillMaxWidth().testTag(HostEditorNameTestTag),
                    label = { Text(stringResource(R.string.host_editor_connection_name_optional)) },
                    supportingText = errors.displayName?.let { message ->
                        ({ Text(message.resolve()) })
                    },
                    isError = errors.displayName != null,
                    enabled = editorControlsEnabled,
                    singleLine = true,
                )

                OutlinedButton(
                    onClick = { advancedExpanded = !advancedExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editorControlsEnabled,
                ) {
                    Text(stringResource(if (advancedExpanded) R.string.host_editor_hide_advanced else R.string.host_editor_show_advanced))
                }
                if (advancedExpanded) {
                    nearbySshDiscoveryController?.let { controller ->
                        val discoveryActive =
                            nearbySshDiscoveryState is NearbySshDiscoveryState.Searching ||
                                nearbySshDiscoveryState is NearbySshDiscoveryState.Resolving
                        OutlinedButton(
                            onClick = {
                                if (discoveryActive) controller.cancel() else controller.start()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(HostEditorNearbySshButtonTestTag),
                            enabled = !saving && !testing,
                        ) {
                            Text(
                                stringResource(
                                    if (discoveryActive) {
                                        R.string.connections_nearby_ssh_cancel
                                    } else {
                                        R.string.connections_nearby_ssh_detect
                                    },
                                ),
                            )
                        }
                        NearbySshInlineStatus(nearbySshDiscoveryState)
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.host_editor_profiles_heading),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SimpleOptionMenu(
                        label = stringResource(R.string.host_editor_terminal_profile),
                        selectedId = draft.terminalProfileId,
                        options = catalog.terminalProfiles,
                        onSelected = { updateDraft(draft.copy(terminalProfileId = it)) },
                        enabled = editorControlsEnabled,
                    )
                    SimpleOptionMenu(
                        label = stringResource(R.string.host_editor_keyboard_profile),
                        selectedId = draft.keyboardProfileId,
                        options = catalog.keyboardProfiles,
                        onSelected = { updateDraft(draft.copy(keyboardProfileId = it)) },
                        enabled = editorControlsEnabled,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.isFavourite,
                            onCheckedChange = { updateDraft(draft.copy(isFavourite = it)) },
                            enabled = editorControlsEnabled,
                        )
                        Text(stringResource(R.string.host_editor_favourite))
                    }
                    OutlinedTextField(
                        value = draft.group,
                        onValueChange = { updateDraft(draft.copy(group = it.take(64))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.host_editor_group_optional)) },
                        supportingText = errors.group?.let { message ->
                            ({ Text(message.resolve()) })
                        },
                        isError = errors.group != null,
                        enabled = editorControlsEnabled,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.tag,
                        onValueChange = { updateDraft(draft.copy(tag = it.take(48))) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.host_editor_tag_optional)) },
                        supportingText = errors.tag?.let { message ->
                            ({ Text(message.resolve()) })
                        },
                        isError = errors.tag != null,
                        enabled = editorControlsEnabled,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.startupCommand,
                        onValueChange = {
                            updateDraft(draft.copy(startupCommand = it.take(4096)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.host_editor_startup_command_optional))
                        },
                        supportingText = errors.startupCommand?.let { message ->
                            ({ Text(message.resolve()) })
                        },
                        isError = errors.startupCommand != null,
                        enabled = editorControlsEnabled,
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text(stringResource(R.string.host_editor_keepalive_override), style = MaterialTheme.typography.labelMedium)
                    EnumChips(
                        values = HostKeepaliveMode.entries,
                        selected = draft.keepaliveMode,
                        label = {
                            stringResource(when (it) {
                                HostKeepaliveMode.INHERIT -> R.string.host_editor_option_inherit
                                HostKeepaliveMode.OFF -> R.string.host_editor_option_off
                                HostKeepaliveMode.CUSTOM -> R.string.host_editor_option_custom
                            })
                        },
                        onSelected = { updateDraft(draft.copy(keepaliveMode = it)) },
                        enabled = editorControlsEnabled,
                    )
                    if (draft.keepaliveMode == HostKeepaliveMode.CUSTOM) {
                        OutlinedTextField(
                            value = draft.keepaliveSeconds,
                            onValueChange = { value ->
                                updateDraft(
                                    draft.copy(
                                        keepaliveSeconds = value.filter(Char::isDigit).take(4),
                                    ),
                                )
                            },
                            label = { Text(stringResource(R.string.host_editor_keepalive_seconds)) },
                            supportingText = errors.keepalive?.let { message -> ({ Text(message.resolve()) }) },
                            isError = errors.keepalive != null,
                            enabled = editorControlsEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                    Text(stringResource(R.string.host_editor_reconnect_override), style = MaterialTheme.typography.labelMedium)
                    EnumChips(
                        values = HostReconnectMode.entries,
                        selected = draft.reconnectMode,
                        label = {
                            stringResource(when (it) {
                                HostReconnectMode.INHERIT -> R.string.host_editor_option_inherit
                                HostReconnectMode.OFF -> R.string.host_editor_option_off
                                HostReconnectMode.AUTOMATIC -> R.string.host_editor_option_automatic
                            })
                        },
                        onSelected = { updateDraft(draft.copy(reconnectMode = it)) },
                        enabled = editorControlsEnabled,
                    )
                    if (draft.protocol == ConnectionProtocol.MOSH) {
                        Text(stringResource(R.string.host_editor_mosh_udp_ports), style = MaterialTheme.typography.labelMedium)
                        EnumChips(
                            values = MoshPortMode.entries,
                            selected = draft.moshPortMode,
                            label = {
                                stringResource(when (it) {
                                    MoshPortMode.AUTOMATIC -> R.string.host_editor_option_automatic
                                    MoshPortMode.SINGLE -> R.string.host_editor_option_single
                                    MoshPortMode.RANGE -> R.string.host_editor_option_range
                                })
                            },
                            onSelected = { updateDraft(draft.copy(moshPortMode = it)) },
                            enabled = editorControlsEnabled,
                        )
                        when (draft.moshPortMode) {
                            MoshPortMode.AUTOMATIC -> Unit
                            MoshPortMode.SINGLE -> OutlinedTextField(
                                value = draft.moshPort,
                                onValueChange = { value ->
                                    updateDraft(
                                        draft.copy(
                                            moshPort = value.filter(Char::isDigit).take(5),
                                        ),
                                    )
                                },
                                label = { Text(stringResource(R.string.host_editor_udp_port)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = editorControlsEnabled,
                                singleLine = true,
                            )
                            MoshPortMode.RANGE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = draft.moshRangeFirst,
                                    onValueChange = { value ->
                                        updateDraft(
                                            draft.copy(
                                                moshRangeFirst = value.filter(Char::isDigit).take(5),
                                            ),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.host_editor_udp_first)) },
                                    enabled = editorControlsEnabled,
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = draft.moshRangeLast,
                                    onValueChange = { value ->
                                        updateDraft(
                                            draft.copy(
                                                moshRangeLast = value.filter(Char::isDigit).take(5),
                                            ),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.host_editor_udp_last)) },
                                    enabled = editorControlsEnabled,
                                    singleLine = true,
                                )
                            }
                        }
                        errors.moshPort?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(
                            value = draft.moshServerCommand,
                            onValueChange = {
                                updateDraft(draft.copy(moshServerCommand = it.take(512)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.host_editor_mosh_executable)) },
                            supportingText = {
                                Text(stringResource(R.string.host_editor_mosh_executable_detail))
                            },
                            isError = errors.moshServerCommand != null,
                            enabled = editorControlsEnabled,
                            singleLine = true,
                        )
                        errors.moshServerCommand?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(
                            value = draft.moshLocale,
                            onValueChange = {
                                updateDraft(draft.copy(moshLocale = it.take(64)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.host_editor_mosh_locale)) },
                            supportingText = {
                                Text(stringResource(R.string.host_editor_mosh_locale_detail))
                            },
                            isError = errors.moshLocale != null,
                            enabled = editorControlsEnabled,
                            singleLine = true,
                        )
                        errors.moshLocale?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                        Text(stringResource(R.string.host_editor_mosh_fallback), style = MaterialTheme.typography.labelMedium)
                        EnumChips(
                            values = MoshFallbackPolicy.entries,
                            selected = draft.moshFallbackPolicy,
                            label = {
                                stringResource(when (it) {
                                    MoshFallbackPolicy.NEVER -> R.string.host_editor_option_never
                                    MoshFallbackPolicy.ASK -> R.string.host_editor_option_ask
                                    MoshFallbackPolicy.AUTOMATIC -> R.string.host_editor_option_automatic
                                })
                            },
                            onSelected = { updateDraft(draft.copy(moshFallbackPolicy = it)) },
                            enabled = editorControlsEnabled,
                        )
                        Text(
                            stringResource(R.string.host_editor_mosh_fallback_detail),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                testResult?.let { result -> HostConnectionTestSummary(result) }
                if (onDelete != null) {
                    TextButton(
                        onClick = { deleteConfirmation = true },
                        enabled = !testing && !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(HostEditorDeleteTestTag),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.connections_delete_host))
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (onTest != null) {
                    TextButton(
                        onClick = ::toggleConnectionTest,
                        enabled = !saving,
                        modifier = Modifier.testTag(HostEditorTestConnectionTestTag),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (testing) {
                                    R.string.host_editor_cancel_test
                                } else {
                                    R.string.host_editor_test_connection
                                },
                            ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = ::requestDismiss,
                    enabled = !saving,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = ::saveHost,
                    enabled = !testing && !saving,
                    modifier = Modifier.testTag(HostEditorSaveTestTag),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(
                        stringResource(
                            if (saving) R.string.host_editor_saving else R.string.host_editor_save,
                        ),
                    )
                }
            }
        },
        dismissButton = {},
    )

    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = {
                Text(stringResource(R.string.connections_delete_title, initial.displayName))
            },
            text = { Text(stringResource(R.string.connections_delete_host_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        cancelOwnedTest()
                        secretState.wipe()
                        transientSecretWasEntered = false
                        deleteConfirmation = false
                        onDelete?.invoke()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag(HostEditorConfirmDeleteTestTag),
                ) {
                    Text(stringResource(R.string.connections_delete_host))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    when (val discovery = nearbySshDiscoveryState) {
        is NearbySshDiscoveryState.Searching -> if (
            discovery.pickerMode == NearbySshPickerMode.IN_APP
        ) {
            NearbySshDiscoveryDialog(
                services = discovery.services,
                scanning = true,
                failure = null,
                onSelect = { nearbySshDiscoveryController?.select(it) },
                onRetry = { nearbySshDiscoveryController?.start() },
                onDismiss = { nearbySshDiscoveryController?.cancel() },
            )
        }
        is NearbySshDiscoveryState.Results -> NearbySshDiscoveryDialog(
            services = discovery.services,
            scanning = false,
            failure = null,
            onSelect = { nearbySshDiscoveryController?.select(it) },
            onRetry = { nearbySshDiscoveryController?.start() },
            onDismiss = { nearbySshDiscoveryController?.cancel() },
        )
        is NearbySshDiscoveryState.Unavailable -> if (
            discovery.pickerMode == NearbySshPickerMode.IN_APP
        ) {
            NearbySshDiscoveryDialog(
                services = emptyList(),
                scanning = false,
                failure = discovery.failure,
                onSelect = {},
                onRetry = { nearbySshDiscoveryController?.start() },
                onDismiss = { nearbySshDiscoveryController?.cancel() },
            )
        }
        NearbySshDiscoveryState.Idle,
        is NearbySshDiscoveryState.Resolving,
        is NearbySshDiscoveryState.Selected,
        -> Unit
    }

    if (discardConfirmation) {
        AlertDialog(
            onDismissRequest = { discardConfirmation = false },
            title = { Text(stringResource(R.string.host_editor_discard_title)) },
            text = { Text(stringResource(R.string.host_editor_discard_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        val openStatus = openMoshStatusAfterDiscard
                        cancelOwnedTest()
                        secretState.wipe()
                        transientSecretWasEntered = false
                        discardConfirmation = false
                        openMoshStatusAfterDiscard = false
                        onDismiss()
                        if (openStatus) onOpenMoshStatus?.invoke()
                    },
                ) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        discardConfirmation = false
                        openMoshStatusAfterDiscard = false
                    },
                ) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }

    when (val pending = visibleTestState) {
        is HostConnectionTestUiState.AwaitingHostIdentity -> when (val prompt = pending.prompt) {
            is HostIdentityPrompt.FirstContact -> HostTestFirstContactDialog(
                prompt = prompt,
                onDecision = { decision ->
                    onAnswerHostIdentity?.invoke(
                        pending.operationToken,
                        prompt.promptToken,
                        decision,
                    )
                },
                onCancel = ::cancelOwnedTest,
            )
            is HostIdentityPrompt.Changed -> HostTestChangedIdentityDialog(
                prompt = prompt,
                replacementConfirmation = replacementReviewToken == prompt.promptToken,
                onReviewReplacement = { replacementReviewToken = prompt.promptToken },
                onBack = { replacementReviewToken = null },
                onReplace = {
                    replacementReviewToken = null
                    onAnswerHostIdentity?.invoke(
                        pending.operationToken,
                        prompt.promptToken,
                        HostIdentityDecision.ReplaceSavedKey,
                    )
                },
                onReject = {
                    onAnswerHostIdentity?.invoke(
                        pending.operationToken,
                        prompt.promptToken,
                        HostIdentityDecision.Reject,
                    )
                },
                onCancel = ::cancelOwnedTest,
            )
        }
        is HostConnectionTestUiState.AwaitingKeyboardInteractive ->
            HostTestKeyboardInteractiveDialog(
                challenge = pending.challenge,
                onSubmit = { responses ->
                    val callback = onAnswerKeyboardInteractive
                    val accepted = if (callback == null) {
                        responses.forEach { it.fill('\u0000') }
                        false
                    } else {
                        try {
                            callback(
                                pending.operationToken,
                                pending.challenge.challengeToken,
                                responses,
                            )
                        } catch (_: Exception) {
                            responses.forEach { it.fill('\u0000') }
                            false
                        }
                    }
                    if (!accepted) cancelOwnedTest()
                },
                onCancelChallenge = {
                    val accepted = onCancelKeyboardInteractive?.invoke(
                        pending.operationToken,
                        pending.challenge.challengeToken,
                    ) == true
                    if (!accepted) cancelOwnedTest()
                },
                onCancelTest = ::cancelOwnedTest,
            )
        HostConnectionTestUiState.Idle,
        is HostConnectionTestUiState.Running,
        is HostConnectionTestUiState.Complete,
        -> Unit
    }
}

@Composable
private fun NearbySshInlineStatus(state: NearbySshDiscoveryState) {
    val message = when (state) {
        NearbySshDiscoveryState.Idle -> null
        is NearbySshDiscoveryState.Searching -> if (
            state.pickerMode == NearbySshPickerMode.SYSTEM
        ) {
            stringResource(R.string.connections_nearby_ssh_system_picker)
        } else {
            null
        }
        is NearbySshDiscoveryState.Results -> null
        is NearbySshDiscoveryState.Resolving -> stringResource(
            R.string.connections_nearby_ssh_resolving,
            state.displayName,
        )
        is NearbySshDiscoveryState.Selected -> stringResource(
            R.string.connections_nearby_ssh_selected,
            state.endpoint.displayName,
        )
        is NearbySshDiscoveryState.Unavailable -> stringResource(state.failure.messageResource())
    }
    message?.let {
        Text(
            text = it,
            color = if (state is NearbySshDiscoveryState.Unavailable) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(HostEditorNearbySshStatusTestTag),
        )
    }
}

@Composable
private fun NearbySshDiscoveryDialog(
    services: List<NearbySshServiceCandidate>,
    scanning: Boolean,
    failure: NearbySshDiscoveryFailure?,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(NearbySshDiscoveryDialogTestTag),
        title = { Text(stringResource(R.string.connections_nearby_ssh_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (scanning) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(stringResource(R.string.connections_nearby_ssh_scanning))
                    }
                }
                failure?.let {
                    Text(
                        text = stringResource(it.messageResource()),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                services.forEach { service ->
                    OutlinedButton(
                        onClick = { onSelect(service.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(NearbySshServiceTestTagPrefix + service.id),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(service.displayName)
                            Text(
                                text = stringResource(R.string.connections_nearby_ssh_service_summary),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.connections_nearby_ssh_prefill_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.connections_nearby_ssh_close))
            }
        },
        dismissButton = if (!scanning) {
            {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.connections_nearby_ssh_retry))
                }
            }
        } else {
            null
        },
    )
}

private fun NearbySshDiscoveryFailure.messageResource(): Int = when (this) {
    NearbySshDiscoveryFailure.NO_RESULTS -> R.string.connections_nearby_ssh_no_results
    NearbySshDiscoveryFailure.NO_SELECTION -> R.string.connections_nearby_ssh_no_selection
    NearbySshDiscoveryFailure.TIMED_OUT -> R.string.connections_nearby_ssh_timed_out
    NearbySshDiscoveryFailure.PERMISSION_DENIED -> R.string.connections_nearby_ssh_permission_denied
    NearbySshDiscoveryFailure.START_FAILED -> R.string.connections_nearby_ssh_start_failed
    NearbySshDiscoveryFailure.RESOLVE_FAILED -> R.string.connections_nearby_ssh_resolve_failed
    NearbySshDiscoveryFailure.NO_USABLE_ADDRESS -> R.string.connections_nearby_ssh_no_address
}

@Composable
private fun HostTestFirstContactDialog(
    prompt: HostIdentityPrompt.FirstContact,
    onDecision: (HostIdentityDecision) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(HostTestIdentityDialogTestTag),
        title = { Text(stringResource(R.string.host_test_first_contact_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.host_test_first_contact_detail))
                HostTestFingerprintDetails(
                    endpoint = prompt.endpoint,
                    algorithm = prompt.algorithm,
                    previousFingerprint = null,
                    newFingerprint = prompt.newFingerprint,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDecision(HostIdentityDecision.TrustOnce) }) {
                    Text(stringResource(R.string.ssh_trust_once))
                }
                Button(onClick = { onDecision(HostIdentityDecision.TrustAndSave) }) {
                    Text(stringResource(R.string.ssh_trust_and_save))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDecision(HostIdentityDecision.Reject) }) {
                    Text(stringResource(R.string.reject))
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(HostTestCancelTestTag),
                ) { Text(stringResource(R.string.host_editor_cancel_test)) }
            }
        },
    )
}

@Composable
private fun HostTestChangedIdentityDialog(
    prompt: HostIdentityPrompt.Changed,
    replacementConfirmation: Boolean,
    onReviewReplacement: () -> Unit,
    onBack: () -> Unit,
    onReplace: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(HostTestIdentityDialogTestTag),
        title = {
            Text(
                if (replacementConfirmation) {
                    stringResource(R.string.host_test_changed_confirm_title)
                } else {
                    stringResource(R.string.host_test_changed_title)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (replacementConfirmation) {
                        stringResource(R.string.host_test_changed_confirm_detail)
                    } else {
                        stringResource(R.string.host_test_changed_detail)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
                HostTestFingerprintDetails(
                    endpoint = prompt.endpoint,
                    algorithm = prompt.algorithm,
                    previousFingerprint = prompt.previousFingerprint,
                    newFingerprint = prompt.newFingerprint,
                )
            }
        },
        confirmButton = {
            Button(onClick = if (replacementConfirmation) onReplace else onReviewReplacement) {
                Text(
                    stringResource(
                        if (replacementConfirmation) {
                            R.string.host_test_replace_saved_key
                        } else {
                            R.string.host_test_review_replacement
                        },
                    ),
                )
            }
        },
        dismissButton = {
            if (replacementConfirmation) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            } else {
                Row {
                    TextButton(onClick = onReject) {
                        Text(stringResource(R.string.reject))
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag(HostTestCancelTestTag),
                    ) { Text(stringResource(R.string.host_editor_cancel_test)) }
                }
            }
        },
    )
}

@Composable
private fun HostTestFingerprintDetails(
    endpoint: String,
    algorithm: String,
    previousFingerprint: String?,
    newFingerprint: String,
) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.host_test_fingerprint_endpoint, endpoint),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.host_test_fingerprint_algorithm, algorithm),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            previousFingerprint?.let {
                Text(
                    stringResource(R.string.host_test_fingerprint_saved, it),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.host_test_fingerprint_offered, newFingerprint),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HostTestKeyboardInteractiveDialog(
    challenge: KeyboardInteractiveChallenge,
    onSubmit: (List<CharArray>) -> Unit,
    onCancelChallenge: () -> Unit,
    onCancelTest: () -> Unit,
) {
    val fields = remember(challenge.challengeToken) {
        List(challenge.questions.size) { WipeableSecretInputState() }
    }
    fun wipeFields() = fields.forEach(WipeableSecretInputState::wipe)
    AlertDialog(
        onDismissRequest = {
            wipeFields()
            onCancelTest()
        },
        modifier = Modifier.testTag(HostTestKeyboardInteractiveDialogTestTag),
        title = {
            Text(
                challenge.name.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.host_test_interactive_title),
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                challenge.instruction.takeIf(String::isNotBlank)?.let { Text(it) }
                challenge.questions.forEachIndexed { index, question ->
                    WipeableSecretInput(
                        state = fields[index],
                        label = question.prompt.ifBlank {
                            stringResource(R.string.host_test_response, index + 1)
                        },
                        testTag = "$HostTestKeyboardInteractiveFieldTestTagPrefix$index",
                        masked = !question.echo,
                        supportingText = if (question.echo) {
                            stringResource(R.string.host_test_visible_response)
                        } else {
                            stringResource(R.string.host_test_hidden_response)
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(fields.map(WipeableSecretInputState::takeChars)) },
            ) { Text(stringResource(R.string.ssh_keyboard_interactive_continue)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    wipeFields()
                    onCancelChallenge()
                },
            ) { Text(stringResource(R.string.host_test_cancel_challenge)) }
        },
    )
}

@Composable
private fun HostConnectionTestSummary(result: HostConnectionTestResult) {
    val (title, detail, error) = when (result) {
        is HostConnectionTestResult.Success -> Triple(
            stringResource(R.string.host_test_passed),
            stringResource(
                if (result.startupCommandSkipped) {
                    R.string.host_test_passed_skipped_startup_detail
                } else {
                    R.string.host_test_passed_detail
                },
            ),
            false,
        )
        is HostConnectionTestResult.HostKeyApprovalRequired -> when (val prompt = result.prompt) {
            is HostIdentityPrompt.FirstContact -> Triple(
                stringResource(R.string.host_test_approval_required),
                stringResource(
                    R.string.host_test_approval_detail,
                    prompt.endpoint,
                    prompt.algorithm,
                    prompt.newFingerprint,
                ),
                false,
            )
            is HostIdentityPrompt.Changed -> Triple(
                stringResource(R.string.host_test_changed_title),
                stringResource(
                    R.string.host_test_changed_summary_detail,
                    prompt.endpoint,
                    prompt.algorithm,
                    prompt.previousFingerprint,
                    prompt.newFingerprint,
                ),
                true,
            )
        }
        is HostConnectionTestResult.KeyboardInteractiveRequired -> {
            val visibleResponse = stringResource(R.string.host_test_visible_short)
            val hiddenResponse = stringResource(R.string.host_test_hidden_short)
            val answerWithConnect = stringResource(R.string.host_test_answer_connect)
            Triple(
                stringResource(R.string.host_test_interactive_required),
                buildString {
                val challenge = result.challenge
                if (challenge.name.isNotBlank()) append(challenge.name).append('\n')
                if (challenge.instruction.isNotBlank()) {
                    append(challenge.instruction).append('\n')
                }
                challenge.questions.forEachIndexed { index, question ->
                    append(index + 1)
                    append(". ")
                    append(question.prompt)
                    append(" · ")
                    append(if (question.echo) visibleResponse else hiddenResponse)
                    if (index != challenge.questions.lastIndex) append('\n')
                }
                    append('\n')
                    append(answerWithConnect)
                },
                false,
            )
        }
        is HostConnectionTestResult.Failed -> Triple(
            stringResource(
                R.string.host_test_failed_stage,
                stringResource(result.stage.displayLabelResId),
            ),
            result.message.resolve(),
            true,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(HostEditorTestResultTestTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        SelectionContainer {
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SnippetEditorDialog(
    initial: SnippetEditorDraft,
    onDismiss: () -> Unit,
    onSave: suspend (com.yanjiyu.terminalspike.core.model.Snippet) -> Result<Unit>,
) {
    var draft by rememberSaveable(initial.persistentId, stateSaver = SnippetEditorDraftSaver) {
        mutableStateOf(initial)
    }
    var errors by remember { mutableStateOf(SnippetEditorErrors()) }
    var discardConfirmation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailure by remember { mutableStateOf<UiText?>(null) }
    val scope = rememberCoroutineScope()
    fun requestDismiss() {
        if (saving) return
        if (draft != initial) discardConfirmation = true else onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::requestDismiss,
        modifier = Modifier.testTag(SnippetEditorDialogTestTag),
        title = {
            Text(stringResource(if (initial.persistentId == null) R.string.snippet_editor_add_title else R.string.snippet_editor_edit_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(96)) },
                    modifier = Modifier.fillMaxWidth().testTag(SnippetEditorNameTestTag),
                    label = { Text(stringResource(R.string.snippet_editor_name)) },
                    supportingText = errors.name?.let { message -> ({ Text(message.resolve()) }) },
                    isError = errors.name != null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.group,
                    onValueChange = { draft = draft.copy(group = it.take(64)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.snippet_editor_group_optional)) },
                    supportingText = errors.group?.let { message -> ({ Text(message.resolve()) }) },
                    isError = errors.group != null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.command,
                    onValueChange = { draft = draft.copy(command = it.take(4096)) },
                    modifier = Modifier.fillMaxWidth().testTag(SnippetEditorCommandTestTag),
                    label = { Text(stringResource(R.string.snippet_editor_command)) },
                    supportingText = errors.command?.let { message -> ({ Text(message.resolve()) }) },
                    isError = errors.command != null,
                    minLines = 4,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(stringResource(R.string.snippet_editor_tap_action), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.tapAction == SnippetTapAction.INSERT,
                        onClick = { draft = draft.copy(tapAction = SnippetTapAction.INSERT) },
                        label = { Text(stringResource(R.string.snippet_editor_insert)) },
                    )
                    FilterChip(
                        selected = draft.tapAction == SnippetTapAction.SEND_IMMEDIATELY,
                        onClick = {
                            draft = draft.copy(
                                tapAction = SnippetTapAction.SEND_IMMEDIATELY,
                                confirmMultilineExecution = true,
                            )
                        },
                        label = { Text(stringResource(R.string.snippet_editor_run_immediately)) },
                    )
                }
                if (draft.tapAction == SnippetTapAction.SEND_IMMEDIATELY &&
                    draft.command.any { it == '\r' || it == '\n' }
                ) {
                    Text(
                        stringResource(R.string.snippet_editor_multiline_confirmation),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = draft.appendEnter,
                        onCheckedChange = { draft = draft.copy(appendEnter = it) },
                    )
                    Text(stringResource(R.string.snippet_editor_append_enter))
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = draft.isFavourite,
                        onCheckedChange = { draft = draft.copy(isFavourite = it) },
                    )
                    Text(stringResource(R.string.snippet_editor_favourite))
                }
                saveFailure?.let {
                    Text(
                        it.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(EditorPersistenceErrorTestTag),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validation = validateSnippetEditor(draft, System.currentTimeMillis())
                    errors = validation.errors
                    validation.snippet?.let { snippet ->
                        saving = true
                        saveFailure = null
                        scope.launch {
                            val result = try {
                                onSave(snippet)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                            if (result.isSuccess) {
                                onDismiss()
                            } else {
                                saveFailure = uiText(R.string.snippet_editor_save_failed)
                                saving = false
                            }
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.testTag(SnippetEditorSaveTestTag),
            ) { Text(stringResource(if (saving) R.string.host_editor_saving else R.string.snippet_editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = ::requestDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (discardConfirmation) {
        AlertDialog(
            onDismissRequest = { discardConfirmation = false },
            title = { Text(stringResource(R.string.snippet_editor_discard_title)) },
            text = { Text(stringResource(R.string.snippet_editor_discard_detail)) },
            confirmButton = {
                Button(onClick = onDismiss) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmation = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
internal fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: suspend (KeyGenerationRequest) -> Result<Unit>,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var algorithmName by rememberSaveable {
        mutableStateOf(SshKeyGenerationAlgorithm.ED25519.name)
    }
    val algorithm = SshKeyGenerationAlgorithm.valueOf(algorithmName)
    val passphrase = remember { WipeableSecretInputState() }
    val confirmPassphrase = remember { WipeableSecretInputState() }
    var transientPassphraseWasEntered by rememberSaveable { mutableStateOf(false) }
    var comment by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val transientPassphraseNeedsReentry = transientPassphraseWasEntered && !passphrase.hasValue
    fun dismissAndClear() {
        if (generating) return
        passphrase.wipe()
        confirmPassphrase.wipe()
        transientPassphraseWasEntered = false
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismissAndClear,
        modifier = Modifier.testTag(GenerateKeyDialogTestTag),
        title = { Text(stringResource(R.string.key_editor_generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(96) },
                    modifier = Modifier.fillMaxWidth().testTag(GenerateKeyNameTestTag),
                    label = { Text(stringResource(R.string.key_editor_friendly_name)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.key_editor_algorithm), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = algorithm == SshKeyGenerationAlgorithm.ED25519,
                        onClick = { algorithmName = SshKeyGenerationAlgorithm.ED25519.name },
                        label = { Text("Ed25519") },
                    )
                    FilterChip(
                        selected = algorithm == SshKeyGenerationAlgorithm.RSA_4096,
                        onClick = { algorithmName = SshKeyGenerationAlgorithm.RSA_4096.name },
                        label = { Text("RSA 4096") },
                    )
                }
                Text(
                    stringResource(R.string.key_editor_algorithm_guidance),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                WipeableSecretInput(
                    state = passphrase,
                    label = stringResource(R.string.key_editor_passphrase_optional),
                    testTag = GenerateKeyPassphraseTestTag,
                    modifier = Modifier.fillMaxWidth(),
                    onPresenceChanged = { present ->
                        if (present) transientPassphraseWasEntered = true
                    },
                )
                WipeableSecretInput(
                    state = confirmPassphrase,
                    label = stringResource(R.string.key_editor_confirm_passphrase),
                    testTag = GenerateKeyConfirmPassphraseTestTag,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(256) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.key_editor_comment_optional)) },
                    singleLine = true,
                )
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                if (transientPassphraseNeedsReentry) {
                    Text(
                        stringResource(R.string.key_editor_passphrase_cleared),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(EditorSecretReentryTestTag),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalizedName = name.trim()
                    error = when {
                        normalizedName.isEmpty() -> uiText(R.string.key_editor_name_required)
                        transientPassphraseNeedsReentry ->
                            uiText(R.string.key_editor_reenter_passphrase)
                        !passphrase.contentEquals(confirmPassphrase) -> uiText(R.string.key_editor_passphrases_mismatch)
                        else -> null
                    }
                    if (error == null) {
                        val request = KeyGenerationRequest(
                            name = normalizedName,
                            algorithm = algorithm,
                            passphrase = passphrase.takeChars(),
                            comment = comment.trim().takeIf(String::isNotEmpty),
                        )
                        confirmPassphrase.wipe()
                        generating = true
                        scope.launch {
                            val result = try {
                                onGenerate(request)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                Result.failure(failure)
                            } finally {
                                request.wipe()
                            }
                            if (result.isSuccess) {
                                passphrase.wipe()
                                confirmPassphrase.wipe()
                                transientPassphraseWasEntered = false
                                onDismiss()
                            } else {
                                error = uiText(R.string.key_editor_generate_failed)
                                generating = false
                            }
                        }
                    }
                },
                enabled = !generating,
                modifier = Modifier.testTag(GenerateKeySubmitTestTag),
            ) { Text(stringResource(if (generating) R.string.key_editor_generating else R.string.key_editor_generate)) }
        },
        dismissButton = {
            TextButton(onClick = ::dismissAndClear, enabled = !generating) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun RenameKeyDialog(
    key: KeyEditorSeed,
    onDismiss: () -> Unit,
    onRename: suspend (persistentId: String, name: String, comment: String?) -> Result<Unit>,
) {
    var name by rememberSaveable(key.persistentId) { mutableStateOf(key.name) }
    var comment by rememberSaveable(key.persistentId) { mutableStateOf(key.comment.orEmpty()) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.key_editor_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(96) },
                    label = { Text(stringResource(R.string.key_editor_friendly_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(256) },
                    label = { Text(stringResource(R.string.key_editor_comment_optional)) },
                    singleLine = true,
                )
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val normalized = name.trim()
                if (normalized.isEmpty()) {
                    error = uiText(R.string.key_editor_name_required)
                } else {
                    saving = true
                    error = null
                    scope.launch {
                        val result = try {
                            onRename(
                                key.persistentId,
                                normalized,
                                comment.trim().takeIf(String::isNotEmpty),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                        if (result.isSuccess) {
                            onDismiss()
                        } else {
                            error = uiText(R.string.key_editor_update_failed)
                            saving = false
                        }
                    }
                }
            }, enabled = !saving) { Text(stringResource(if (saving) R.string.host_editor_saving else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun KeyFingerprintDialog(key: KeyEditorSeed, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(key.algorithm, style = MaterialTheme.typography.labelMedium)
                Text(key.fingerprint, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun HostAuthenticationPromptDialog(
    host: HostEditorSeed,
    key: KeyEditorSeed?,
    moshAvailable: Boolean,
    onDismiss: () -> Unit,
    onConnect: (HostConnectRequest) -> Unit,
    onConnectWithSsh: (HostConnectRequest) -> Unit,
) {
    val secret = remember(host.draft.persistentId) { WipeableSecretInputState() }
    val requiresSecret = when (host.draft.authenticationMethod) {
        HostAuthenticationMethod.PASSWORD -> !host.savedSecretAvailable
        HostAuthenticationMethod.KEYBOARD_INTERACTIVE -> false
        HostAuthenticationMethod.PRIVATE_KEY -> key?.passphraseProtected == true &&
            !host.savedSecretAvailable
    }
    fun dismissAndWipe() {
        secret.wipe()
        onDismiss()
    }
    fun submit(forceSsh: Boolean) {
        val request = HostConnectRequest(
            persistentHostId = requireNotNull(host.draft.persistentId),
            secret = secret.takeChars(),
            forceSsh = forceSsh,
        )
        try {
            if (forceSsh) onConnectWithSsh(request) else onConnect(request)
        } catch (error: Exception) {
            request.wipe()
            throw error
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismissAndWipe,
        title = { Text(stringResource(R.string.connections_connect_to, host.draft.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (host.draft.protocol == ConnectionProtocol.MOSH && !moshAvailable) {
                    Text(
                        stringResource(R.string.host_connect_mosh_unavailable),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (requiresSecret) {
                    WipeableSecretInput(
                        state = secret,
                        label = when (host.draft.authenticationMethod) {
                            HostAuthenticationMethod.PASSWORD -> stringResource(R.string.host_editor_auth_password)
                            HostAuthenticationMethod.PRIVATE_KEY -> stringResource(R.string.host_connect_key_passphrase)
                            HostAuthenticationMethod.KEYBOARD_INTERACTIVE -> stringResource(R.string.host_connect_interactive_response)
                        },
                        testTag = HostConnectSecretTestTag,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    stringResource(R.string.host_connect_secret_guidance),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            if (host.draft.protocol != ConnectionProtocol.MOSH || moshAvailable) {
                Button(
                    onClick = {
                        submit(forceSsh = false)
                    },
                    enabled = !requiresSecret || secret.hasValue,
                ) { Text(stringResource(R.string.host_connect_action)) }
            }
        },
        dismissButton = {
            Row {
                if (host.draft.protocol == ConnectionProtocol.MOSH) {
                    TextButton(
                        onClick = {
                            submit(forceSsh = true)
                        },
                        enabled = !requiresSecret || secret.hasValue,
                    ) { Text(stringResource(R.string.host_connect_with_ssh)) }
                }
                TextButton(onClick = ::dismissAndWipe) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun SimpleOptionMenu(
    label: String,
    selectedId: String?,
    options: List<CatalogProfileOption>,
    onSelected: (String?) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected?.name ?: stringResource(R.string.default_option))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.default_option)) },
                onClick = {
                    expanded = false
                    onSelected(null)
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        expanded = false
                        onSelected(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> EnumChips(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                enabled = enabled,
                label = { Text(label(value)) },
            )
        }
    }
}

private fun authenticationEndpointChanged(initial: HostEditorDraft, current: HostEditorDraft): Boolean =
    !initial.hostname.trim().equals(current.hostname.trim(), ignoreCase = true) ||
        initial.port.trim() != current.port.trim() ||
        initial.username.trim() != current.username.trim()

private val ConnectionTestStage.displayLabelResId: Int
    get() = when (this) {
        ConnectionTestStage.DNS -> R.string.host_test_stage_dns
        ConnectionTestStage.TCP -> R.string.host_test_stage_tcp
        ConnectionTestStage.SSH_NEGOTIATION -> R.string.host_test_stage_negotiation
        ConnectionTestStage.HOST_KEY -> R.string.host_test_stage_host_key
        ConnectionTestStage.AUTHENTICATION -> R.string.host_test_stage_authentication
        ConnectionTestStage.SHELL -> R.string.host_test_stage_shell
    }

private val SnippetEditorDraftSaver: Saver<SnippetEditorDraft, Any> = listSaver(
    save = { draft ->
        listOf(
            draft.persistentId.orEmpty(),
            draft.name,
            draft.group,
            draft.command,
            draft.tapAction.name,
            draft.appendEnter,
            draft.confirmMultilineExecution,
            draft.isFavourite,
        )
    },
    restore = { values ->
        SnippetEditorDraft(
            persistentId = (values[0] as String).takeIf(String::isNotEmpty),
            name = values[1] as String,
            group = values[2] as String,
            command = values[3] as String,
            tapAction = enumValueOrDefault(values[4] as String, SnippetTapAction.INSERT),
            appendEnter = values[5] as Boolean,
            confirmMultilineExecution = values[6] as Boolean,
            isFavourite = values[7] as Boolean,
        )
    },
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

internal const val HostEditorDialogTestTag = "host-editor-dialog"
internal const val HostEditorDeleteTestTag = "host-editor-delete"
internal const val HostEditorConfirmDeleteTestTag = "host-editor-confirm-delete"
internal const val HostEditorNameTestTag = "host-editor-name"
internal const val HostEditorHostnameTestTag = "host-editor-hostname"
internal const val HostEditorUsernameTestTag = "host-editor-username"
internal const val HostEditorPortTestTag = "host-editor-port"
internal const val HostEditorSecretTestTag = "host-editor-secret"
internal const val HostEditorSavePasswordTestTag = "host-editor-save-password"
internal const val HostConnectSecretTestTag = "host-connect-secret"
internal const val HostEditorSaveTestTag = "host-editor-save"
internal const val HostEditorTestConnectionTestTag = "host-editor-test-connection"
internal const val HostEditorTestResultTestTag = "host-editor-test-result"
internal const val HostEditorNearbySshButtonTestTag = "host-editor-nearby-ssh"
internal const val HostEditorNearbySshStatusTestTag = "host-editor-nearby-ssh-status"
internal const val NearbySshDiscoveryDialogTestTag = "nearby-ssh-discovery-dialog"
internal const val NearbySshServiceTestTagPrefix = "nearby-ssh-service-"
internal const val HostTestIdentityDialogTestTag = "host-test-identity-dialog"
internal const val HostTestCancelTestTag = "host-test-cancel"
internal const val HostTestKeyboardInteractiveDialogTestTag = "host-test-keyboard-interactive-dialog"
internal const val HostTestKeyboardInteractiveFieldTestTagPrefix =
    "host-test-keyboard-interactive-field-"
internal const val EditorPersistenceErrorTestTag = "editor-persistence-error"
internal const val EditorSecretReentryTestTag = "editor-secret-reentry"
internal const val GenerateKeyPassphraseTestTag = "generate-key-passphrase"
internal const val GenerateKeyConfirmPassphraseTestTag = "generate-key-confirm-passphrase"
internal const val GenerateKeySubmitTestTag = "generate-key-submit"
internal const val GenerateKeyNameTestTag = "generate-key-name"
internal const val SnippetEditorDialogTestTag = "snippet-editor-dialog"
internal const val SnippetEditorNameTestTag = "snippet-editor-name"
internal const val SnippetEditorCommandTestTag = "snippet-editor-command"
internal const val SnippetEditorSaveTestTag = "snippet-editor-save"
internal const val GenerateKeyDialogTestTag = "generate-key-dialog"
