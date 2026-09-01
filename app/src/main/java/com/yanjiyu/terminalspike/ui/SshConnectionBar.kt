package com.yanjiyu.terminalspike.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.TMUX_NEW_SESSION_SELECTION
import com.yanjiyu.terminalspike.connection.TmuxAvailability
import com.yanjiyu.terminalspike.connection.TmuxSessionPrompt
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import com.yanjiyu.terminalspike.ui.theme.iconMetrics
import com.yanjiyu.terminalspike.ui.theme.spacing

internal const val TerminalSessionStripTestTag = "terminal-session-strip"
internal const val TerminalSessionTabTestTagPrefix = "terminal-session-tab-"
internal const val NewTerminalSessionTestTag = "new-terminal-session"
internal const val TerminalChromeTitleTestTag = "terminal-chrome-title"
internal const val TerminalChromeBackTestTag = "terminal-chrome-back"
internal const val KeyboardInteractiveDialogTestTag = "keyboard-interactive-dialog"
internal const val KeyboardInteractiveFieldTestTagPrefix = "keyboard-interactive-field-"
internal const val TmuxSessionDialogTestTag = "tmux-session-dialog"

@Composable
fun SessionChrome(
    sessions: List<SessionTabUi>,
    activeSessionId: Long,
    notice: String?,
    canAddSession: Boolean,
    settingsReady: Boolean,
    onSelectSession: (Long) -> Unit,
    onDuplicateSession: (Long) -> Unit,
    onCloseSession: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onSessionActions: ((Long) -> Unit)? = null,
    previewLinesForSession: (Long) -> List<String> = { emptyList() },
    onHostIdentityAnswer: (
        sessionId: Long,
        promptToken: Long,
        decision: HostIdentityDecision,
    ) -> Unit,
    onNavigateBack: () -> Unit,
    backDestinationLabel: String = "previous screen",
    onNewSession: () -> Unit,
    onOpenConnections: () -> Unit,
    onKeyboardInteractiveAnswer: (
        sessionId: Long,
        challengeToken: Long,
        responses: List<CharArray>,
    ) -> Unit = { _, _, responses -> responses.forEach { it.fill('\u0000') } },
    onKeyboardInteractiveCancel: (
        sessionId: Long,
        challengeToken: Long,
    ) -> Unit = { _, _ -> },
    onTmuxSessionAnswer: (
        sessionId: Long,
        promptToken: Long,
        tmuxSessionId: String?,
    ) -> Unit = { _, _, _ -> },
    onTmuxSessionDelete: (
        sessionId: Long,
        promptToken: Long,
        tmuxSessionId: String,
    ) -> Unit = { _, _, _ -> },
    showLocalTerminalSession: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val newSessionDescription = stringResource(R.string.session_add_saved_connection_description)
    val backDescription = stringResource(
        R.string.terminal_predictive_back_destination,
        backDestinationLabel,
    )
    require(sessions.any { it.id == activeSessionId }) { "The active terminal session must exist." }
    val visibleSessions = if (showLocalTerminalSession) sessions else sessions.filterNot(SessionTabUi::isLocalTerminal)
    var replacementConfirmationToken by remember { mutableStateOf<Long?>(null) }
    var appSessionSwitcherVisible by remember { mutableStateOf(false) }
    val visibleSessionIds = visibleSessions.map(SessionTabUi::id)
    val appSessionPreviews = remember(appSessionSwitcherVisible, visibleSessionIds) {
        if (appSessionSwitcherVisible) {
            visibleSessionIds.associateWith(previewLinesForSession)
        } else {
            emptyMap()
        }
    }
    val appSessionSwitcherDescription = stringResource(
        R.string.app_session_switcher_button_description,
    )

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                if (visibleSessions.isEmpty()) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(MaterialTheme.iconMetrics.minimumTouchTarget)
                            .testTag(TerminalChromeBackTestTag),
                    ) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.BACK,
                            modifier = Modifier
                                .size(MaterialTheme.iconMetrics.standard)
                                .semantics { contentDescription = backDescription },
                        )
                    }
                }
                if (visibleSessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.navigation_terminal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TerminalChromeTitleTestTag)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TerminalSessionStripTestTag),
                    ) {
                        visibleSessions.forEach { session ->
                            SessionTab(
                                session = session,
                                selected = session.id == activeSessionId,
                                modifier = Modifier.weight(1f),
                                onSelect = { onSelectSession(session.id) },
                                onDuplicate = if (!session.isLocalTerminal && canAddSession) {
                                    { onDuplicateSession(session.id) }
                                } else {
                                    null
                                },
                                onClose = if (session.isLocalTerminal) {
                                    null
                                } else {
                                    { onCloseSession(session.id) }
                                },
                                onActions = if (session.isLocalTerminal) {
                                    null
                                } else {
                                    onSessionActions?.let { actions -> { actions(session.id) } }
                                },
                            )
                        }
                    }
                }
                if (visibleSessions.isNotEmpty()) {
                    IconButton(
                        onClick = { appSessionSwitcherVisible = true },
                        modifier = Modifier
                            .size(MaterialTheme.iconMetrics.minimumTouchTarget)
                            .semantics {
                                contentDescription = appSessionSwitcherDescription
                            },
                    ) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.TABS,
                            modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                        )
                    }
                }
                IconButton(
                    onClick = onNewSession,
                    enabled = canAddSession && settingsReady,
                    modifier = Modifier
                        .size(MaterialTheme.iconMetrics.minimumTouchTarget)
                        .testTag(NewTerminalSessionTestTag)
                        .semantics { contentDescription = newSessionDescription },
                ) {
                    ConnectionsGlyphIcon(
                        glyph = ConnectionsGlyph.ADD,
                        modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                    )
                }
            }
            if (visibleSessions.isEmpty() && !notice.isNullOrBlank()) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(
                        start = MaterialTheme.spacing.large,
                        end = MaterialTheme.spacing.large,
                        bottom = MaterialTheme.spacing.small,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (appSessionSwitcherVisible) {
        AppSessionSwitcherDialog(
            sessions = visibleSessions,
            previews = appSessionPreviews,
            activeSessionId = activeSessionId,
            canAddSession = canAddSession && settingsReady,
            onDismiss = { appSessionSwitcherVisible = false },
            onSelect = onSelectSession,
            onClose = onCloseSession,
            onNewSession = onNewSession,
        )
    }

    // Approval dialogs belong to a specific terminal tab. Showing an arbitrary background
    // session's prompt can cover the active session's prompt and leave both connections waiting.
    val promptSession = activeApprovalSession(sessions, activeSessionId)
    val pendingPrompt = (promptSession?.connectionState as? ConnectionState.AwaitingApproval)?.prompt
    val hostPrompt = pendingPrompt as? HostIdentityPrompt
    val keyboardInteractive = pendingPrompt as? KeyboardInteractiveChallenge
    val tmuxPrompt = pendingPrompt as? TmuxSessionPrompt
    LaunchedEffect(hostPrompt?.promptToken) {
        if (replacementConfirmationToken != hostPrompt?.promptToken) {
            replacementConfirmationToken = null
        }
    }
    if (promptSession != null && hostPrompt != null) {
        HostIdentityDialog(
            prompt = hostPrompt,
            replacementConfirmation = replacementConfirmationToken == hostPrompt.promptToken,
            onReviewReplacement = { replacementConfirmationToken = hostPrompt.promptToken },
            onBackFromReplacement = { replacementConfirmationToken = null },
            onDecision = { decision ->
                replacementConfirmationToken = null
                onHostIdentityAnswer(promptSession.id, hostPrompt.promptToken, decision)
            },
        )
    }
    if (promptSession != null && keyboardInteractive != null) {
        KeyboardInteractiveDialog(
            challenge = keyboardInteractive,
            onSubmit = { responses ->
                onKeyboardInteractiveAnswer(
                    promptSession.id,
                    keyboardInteractive.challengeToken,
                    responses,
                )
            },
            onCancel = {
                onKeyboardInteractiveCancel(
                    promptSession.id,
                    keyboardInteractive.challengeToken,
                )
            },
        )
    }
    if (promptSession != null && tmuxPrompt != null) {
        TmuxSessionDialog(
            prompt = tmuxPrompt,
            onOpenShell = {
                onTmuxSessionAnswer(promptSession.id, tmuxPrompt.promptToken, null)
            },
            onStartNew = {
                onTmuxSessionAnswer(
                    promptSession.id,
                    tmuxPrompt.promptToken,
                    TMUX_NEW_SESSION_SELECTION,
                )
            },
            onAttach = { tmuxSessionId ->
                onTmuxSessionAnswer(promptSession.id, tmuxPrompt.promptToken, tmuxSessionId)
            },
            onDelete = { tmuxSessionId ->
                onTmuxSessionDelete(promptSession.id, tmuxPrompt.promptToken, tmuxSessionId)
            },
        )
    }
}

internal fun activeApprovalSession(
    sessions: List<SessionTabUi>,
    activeSessionId: Long,
): SessionTabUi? = sessions.firstOrNull { session ->
    session.id == activeSessionId && session.connectionState is ConnectionState.AwaitingApproval
}

@Composable
private fun TmuxSessionDialog(
    prompt: TmuxSessionPrompt,
    onOpenShell: () -> Unit,
    onStartNew: () -> Unit,
    onAttach: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleteTarget by remember(prompt.promptToken, prompt.sessions) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onOpenShell,
        modifier = Modifier.testTag(TmuxSessionDialogTestTag),
        title = { Text(stringResource(R.string.tmux_selector_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            prompt.availability == TmuxAvailability.NOT_INSTALLED ->
                                R.string.tmux_selector_not_installed
                            prompt.availability == TmuxAvailability.CHECK_FAILED ->
                                R.string.tmux_selector_check_failed
                            prompt.sessions.isEmpty() -> R.string.tmux_selector_empty
                            else -> R.string.tmux_selector_summary
                        },
                    ),
                )
                if (prompt.deleteFailed) {
                    Text(
                        stringResource(R.string.tmux_selector_delete_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                prompt.sessions.forEach { session ->
                    Surface(
                        onClick = { onAttach(session.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(vertical = 5.dp),
                            ) {
                                Text(
                                    text = session.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.tmux_selector_session_metadata,
                                        session.windowCount,
                                        session.attachedClientCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { deleteTarget = session.id }) {
                                Text(stringResource(R.string.tmux_selector_delete))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (prompt.availability == TmuxAvailability.AVAILABLE) {
                Button(onClick = onStartNew) {
                    Text(stringResource(R.string.tmux_selector_start_new))
                }
            } else {
                TextButton(onClick = onOpenShell) {
                    Text(stringResource(R.string.tmux_selector_open_shell))
                }
            }
        },
        dismissButton = if (prompt.availability == TmuxAvailability.AVAILABLE) {
            {
                TextButton(onClick = onOpenShell) {
                    Text(stringResource(R.string.tmux_selector_open_shell))
                }
            }
        } else {
            null
        },
    )
    val selected = prompt.sessions.firstOrNull { it.id == deleteTarget }
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.tmux_selector_delete_title)) },
            text = { Text(stringResource(R.string.tmux_selector_delete_message, selected.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        onDelete(selected.id)
                    },
                ) { Text(stringResource(R.string.tmux_selector_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun KeyboardInteractiveDialog(
    challenge: KeyboardInteractiveChallenge,
    onSubmit: (List<CharArray>) -> Unit,
    onCancel: () -> Unit,
) {
    val fields = remember(challenge.challengeToken) {
        List(challenge.questions.size) { WipeableSecretInputState() }
    }

    fun wipeFields() = fields.forEach(WipeableSecretInputState::wipe)

    DisposableEffect(challenge.challengeToken) {
        onDispose(::wipeFields)
    }

    AlertDialog(
        onDismissRequest = {
            wipeFields()
            onCancel()
        },
        modifier = Modifier.testTag(KeyboardInteractiveDialogTestTag),
        title = {
            Text(challenge.name.ifBlank { stringResource(R.string.ssh_keyboard_interactive_title) })
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (challenge.instruction.isNotBlank()) {
                    SelectionContainer { Text(challenge.instruction) }
                }
                challenge.questions.forEachIndexed { index, question ->
                    WipeableSecretInput(
                        state = fields[index],
                        label = question.prompt,
                        testTag = "$KeyboardInteractiveFieldTestTagPrefix$index",
                        modifier = Modifier.fillMaxWidth(),
                        masked = !question.echo,
                        maxCharacters = MAX_KBI_RESPONSE_UTF16_CHARS,
                        supportingText = stringResource(
                            if (question.echo) {
                                R.string.ssh_keyboard_interactive_visible_response
                            } else {
                                R.string.ssh_keyboard_interactive_hidden_response
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val responses = fields.map(WipeableSecretInputState::takeChars)
                    try {
                        onSubmit(responses)
                    } catch (_: Exception) {
                        responses.forEach { it.fill('\u0000') }
                        onCancel()
                    }
                },
            ) { Text(stringResource(R.string.ssh_keyboard_interactive_continue)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    wipeFields()
                    onCancel()
                },
            ) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private const val MAX_KBI_RESPONSE_UTF16_CHARS = 1_024

@Composable
private fun HostIdentityDialog(
    prompt: HostIdentityPrompt,
    replacementConfirmation: Boolean,
    onReviewReplacement: () -> Unit,
    onBackFromReplacement: () -> Unit,
    onDecision: (HostIdentityDecision) -> Unit,
) {
    when (prompt) {
        is HostIdentityPrompt.FirstContact -> AlertDialog(
            onDismissRequest = { onDecision(HostIdentityDecision.Reject) },
            title = { Text(stringResource(R.string.ssh_trust_first_contact_title)) },
            text = {
                HostIdentityDetails(
                    message = stringResource(R.string.ssh_trust_first_contact_message),
                    endpoint = prompt.endpoint,
                    algorithm = prompt.algorithm,
                    previousFingerprint = null,
                    newFingerprint = prompt.newFingerprint,
                )
            },
            confirmButton = {
                Button(onClick = { onDecision(HostIdentityDecision.TrustAndSave) }) {
                    Text(stringResource(R.string.ssh_trust_and_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onDecision(HostIdentityDecision.TrustOnce) }) {
                        Text(stringResource(R.string.ssh_trust_once))
                    }
                    TextButton(onClick = { onDecision(HostIdentityDecision.Reject) }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )

        is HostIdentityPrompt.Changed -> if (replacementConfirmation) {
            AlertDialog(
                onDismissRequest = onBackFromReplacement,
                title = { Text(stringResource(R.string.ssh_replace_host_key_title)) },
                text = {
                    HostIdentityDetails(
                        message = stringResource(R.string.ssh_replace_host_key_message),
                        endpoint = prompt.endpoint,
                        algorithm = prompt.algorithm,
                        previousFingerprint = prompt.previousFingerprint,
                        newFingerprint = prompt.newFingerprint,
                    )
                },
                confirmButton = {
                    Button(onClick = { onDecision(HostIdentityDecision.ReplaceSavedKey) }) {
                        Text(stringResource(R.string.ssh_replace_saved_key))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onBackFromReplacement) {
                        Text(stringResource(R.string.ssh_replace_host_key_back))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { onDecision(HostIdentityDecision.Reject) },
                title = { Text(stringResource(R.string.ssh_host_key_changed_title)) },
                text = {
                    HostIdentityDetails(
                        message = stringResource(R.string.ssh_host_key_changed_message),
                        endpoint = prompt.endpoint,
                        algorithm = prompt.algorithm,
                        previousFingerprint = prompt.previousFingerprint,
                        newFingerprint = prompt.newFingerprint,
                    )
                },
                confirmButton = {
                    Button(onClick = onReviewReplacement) {
                        Text(stringResource(R.string.ssh_review_key_replacement))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDecision(HostIdentityDecision.Reject) }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun HostIdentityDetails(
    message: String,
    endpoint: String,
    algorithm: String,
    previousFingerprint: String?,
    newFingerprint: String,
) {
    val endpointLine = stringResource(R.string.ssh_host_endpoint, endpoint)
    val algorithmLine = stringResource(R.string.ssh_host_key_algorithm, algorithm)
    val previousLine = previousFingerprint?.let { fingerprint ->
        stringResource(R.string.ssh_previous_fingerprint, fingerprint)
    }
    val newLine = stringResource(R.string.ssh_new_fingerprint, newFingerprint)
    SelectionContainer {
        Text(
            buildString {
                append(message)
                append("\n\n")
                append(endpointLine)
                append('\n')
                append(algorithmLine)
                append('\n')
                if (previousLine != null) {
                    append(previousLine)
                    append('\n')
                }
                append(newLine)
            },
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SessionTab(
    session: SessionTabUi,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onDuplicate: (() -> Unit)?,
    onClose: (() -> Unit)?,
    onActions: (() -> Unit)?,
) {
    val tabTitle = session.displayedTerminalTabTitle()
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        label = "session-tab",
    )
    val stateDescription = session.connectionState.accessibilityLabel()
    val closeDescription = onClose?.let {
        stringResource(R.string.session_close_tab_description, tabTitle)
    }
    Surface(
        modifier = modifier
            .heightIn(min = 28.dp)
            .testTag("$TerminalSessionTabTestTagPrefix${session.id}")
            .combinedClickable(
                onClickLabel = "Open $tabTitle terminal",
                onClick = onSelect,
                onDoubleClick = onDuplicate,
                onLongClickLabel = onActions?.let { "Session actions for $tabTitle" },
                onLongClick = onActions,
            )
            .semantics {
                contentDescription = buildString {
                    append(tabTitle)
                    append(" terminal tab, ")
                    append(stateDescription)
                    if (selected) append(", selected")
                }
            },
        shape = RoundedCornerShape(8.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tabTitle,
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontFamily = if (session.isLocalTerminal) FontFamily.Default else FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onClose != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onClose)
                        .clearAndSetSemantics {
                            contentDescription = requireNotNull(closeDescription)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ConnectionsGlyphIcon(
                        glyph = ConnectionsGlyph.CLOSE,
                        modifier = Modifier.size(MaterialTheme.iconMetrics.compact),
                    )
                }
            }
        }
    }
}

/**
 * Remote programs may publish a standard OSC 0/2 terminal title. tmux does this when its
 * `set-titles` option is enabled, so prefer that live title over the fixed connection label.
 * The stock tmux title is compacted to its leading session name; custom titles remain intact.
 */
internal fun SessionTabUi.displayedTerminalTabTitle(): String {
    if (isLocalTerminal) return title
    val liveTitle = terminalTitle?.trim()?.takeIf(String::isNotEmpty) ?: return title
    return TMUX_DEFAULT_TITLE.matchEntire(liveTitle)?.groupValues?.get(1) ?: liveTitle
}

private val TMUX_DEFAULT_TITLE = Regex(
    pattern = "^([^:]{1,64}):\\d+:[^\\r\\n]+ - \".*\"(?: .*)?$",
)

private fun ConnectionState.accessibilityLabel(): String = when (this) {
    ConnectionState.Disconnected -> "disconnected"
    ConnectionState.Connecting -> "connecting"
    ConnectionState.Connected -> "connected"
    is ConnectionState.Reconnecting -> if (waitingForNetwork) {
        "waiting for network before reconnect attempt $attempt of $maxAttempts"
    } else {
        "reconnecting, attempt $attempt of $maxAttempts"
    }
    is ConnectionState.AwaitingApproval -> when (prompt) {
        is KeyboardInteractiveChallenge -> "awaiting interactive authentication"
        is TmuxSessionPrompt -> "awaiting tmux session selection"
        else -> "awaiting host approval"
    }
    is ConnectionState.Failed -> "connection failed"
}

@Composable
internal fun SshConnectDialog(
    profiles: List<SavedSshProfile>,
    identities: List<SavedSshIdentity>,
    initialProfile: SavedSshProfile?,
    settingsReady: Boolean,
    onDismiss: () -> Unit,
    onConnect: (
        host: String,
        port: String,
        username: String,
        password: CharArray,
        identityId: Long?,
        passphrase: CharArray,
        saveProfile: Boolean,
        selectedProfileId: Long?,
        savePassword: Boolean,
        connectionOptions: RemoteConnectionOptions,
        sessionName: String,
    ) -> Unit,
    onForgetSavedPassword: (Long) -> Unit,
    keyboardRuntimeCompatible: Boolean = true,
    initialSeed: SshConnectionSeed? = null,
    purpose: SshConnectPurpose = SshConnectPurpose.NEW,
    moshExtension: MoshExtensionUiState = com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus.Absent.toUiState(),
) {
    val initialSelectedProfileId = initialProfile?.id?.takeIf {
        initialSeed == null || initialSeed.matches(initialProfile)
    }
    val dialogStateKey = Triple(initialSeed, initialProfile?.id, purpose)
    var host by remember(dialogStateKey) {
        mutableStateOf(initialSeed?.host ?: initialProfile?.host.orEmpty())
    }
    var port by remember(dialogStateKey) {
        mutableStateOf((initialSeed?.port ?: initialProfile?.port ?: 22).toString())
    }
    var username by remember(dialogStateKey) {
        mutableStateOf(initialSeed?.username ?: initialProfile?.username.orEmpty())
    }
    val password = remember(dialogStateKey) { WipeableSecretInputState() }
    var selectedIdentityId by remember(dialogStateKey) { mutableStateOf<Long?>(null) }
    val passphrase = remember(dialogStateKey) { WipeableSecretInputState() }
    var selectedProfileId by remember(dialogStateKey) { mutableStateOf(initialSelectedProfileId) }
    var saveProfile by remember(dialogStateKey) { mutableStateOf(initialSelectedProfileId != null) }
    var savePassword by remember(dialogStateKey) { mutableStateOf(false) }
    var sessionName by remember(dialogStateKey) { mutableStateOf("") }
    val initialConnectionOptions = initialSeed?.connectionOptions
        ?: initialProfile?.connectionSeed()?.connectionOptions
        ?: RemoteConnectionOptions.SSH
    var protocol by remember(dialogStateKey) { mutableStateOf(initialConnectionOptions.protocol) }
    var moshUdpPortOrRange by remember(dialogStateKey) {
        mutableStateOf(
            initialConnectionOptions.moshPort?.toString()
                ?: initialConnectionOptions.moshPortRange?.let { "${it.first}:${it.last}" }
                .orEmpty(),
        )
    }
    var moshServerExecutable by remember(dialogStateKey) {
        mutableStateOf(initialConnectionOptions.moshServerCommand ?: DEFAULT_MOSH_SERVER_EXECUTABLE)
    }
    var confirmingForgetPasswordFor by remember { mutableStateOf<SavedSshProfile?>(null) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val moshAvailable = moshExtension.kind == MoshExtensionUiKind.AVAILABLE
    val parsedMoshOptions = if (protocol == ConnectionProtocol.MOSH) {
        parseMoshConnectionOptions(moshUdpPortOrRange, moshServerExecutable)
    } else {
        MoshOptionsParseResult(RemoteConnectionOptions.SSH, null)
    }
    val connectionOptions = parsedMoshOptions.options
    val canUseSavedPassword = selectedIdentityId == null &&
        selectedProfile?.canConnectFromQuickUi == true && selectedProfile.hasSavedPassword &&
        selectedProfile.connectionSeed().connectionOptions == connectionOptions &&
        !password.hasValue && selectedProfile.host.equals(host, ignoreCase = true) &&
        selectedProfile.port.toString() == port && selectedProfile.username == username
    val validPort = port.toIntOrNull()?.let { it in 1..65_535 } == true
    val valid = settingsReady &&
        selectedProfile?.canConnectFromQuickUi != false &&
        connectionOptions != null &&
        (protocol != ConnectionProtocol.MOSH || moshAvailable) &&
        host.isNotBlank() && host.none(Char::isWhitespace) && validPort &&
        username.isNotBlank() && username.none { it.isWhitespace() || it.isISOControl() } &&
        (selectedIdentityId != null || password.hasValue || canUseSavedPassword)

    fun dismissAndClearSecrets() {
        password.wipe()
        passphrase.wipe()
        onDismiss()
    }

    if (confirmingForgetPasswordFor == null) {
        AlertDialog(
            onDismissRequest = ::dismissAndClearSecrets,
            title = {
                Text(
                    when (purpose) {
                        SshConnectPurpose.NEW -> stringResource(R.string.ssh_connect_new_title, protocol.displayName())
                        SshConnectPurpose.RECONNECT -> stringResource(R.string.ssh_connect_reconnect_title, protocol.displayName())
                        SshConnectPurpose.DUPLICATE -> stringResource(R.string.ssh_connect_duplicate_title, protocol.displayName())
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                if (purpose != SshConnectPurpose.NEW) {
                    Text(
                        if (purpose == SshConnectPurpose.RECONNECT) {
                            stringResource(R.string.ssh_connect_reconnect_detail)
                        } else {
                            stringResource(R.string.ssh_connect_duplicate_detail)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.host_editor_protocol), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = protocol == ConnectionProtocol.SSH,
                        onClick = {
                            if (protocol != ConnectionProtocol.SSH) {
                                protocol = ConnectionProtocol.SSH
                                selectedProfileId = null
                                savePassword = false
                            }
                        },
                        label = { Text("SSH") },
                    )
                    FilterChip(
                        selected = protocol == ConnectionProtocol.MOSH,
                        enabled = moshAvailable,
                        onClick = {
                            if (protocol != ConnectionProtocol.MOSH) {
                                protocol = ConnectionProtocol.MOSH
                                selectedProfileId = null
                                savePassword = false
                            }
                        },
                        label = { Text("Mosh") },
                    )
                }
                if (!moshAvailable) {
                    Text(
                        stringResource(R.string.ssh_connect_mosh_status, moshExtension.statusLabel.resolve()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (protocol == ConnectionProtocol.MOSH) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (purpose == SshConnectPurpose.NEW && profiles.isNotEmpty()) {
                    Text(stringResource(R.string.ssh_connect_saved_hosts), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        profiles.forEach { profile ->
                            AssistChip(
                                onClick = {
                                    host = profile.host
                                    port = profile.port.toString()
                                    username = profile.username
                                    password.wipe()
                                    passphrase.wipe()
                                    selectedIdentityId = null
                                    selectedProfileId = profile.id
                                    val options = profile.connectionSeed().connectionOptions
                                    protocol = options.protocol
                                    moshUdpPortOrRange = options.moshPort?.toString()
                                        ?: options.moshPortRange?.let { "${it.first}:${it.last}" }
                                            .orEmpty()
                                    moshServerExecutable = options.moshServerCommand
                                        ?: DEFAULT_MOSH_SERVER_EXECUTABLE
                                    saveProfile = true
                                    savePassword = false
                                },
                                enabled = profile.canConnectFromQuickUi &&
                                    (profile.protocol != ConnectionProtocol.MOSH || moshAvailable),
                                label = {
                                    Text(
                                        if (!profile.canConnectFromQuickUi) {
                                            stringResource(R.string.ssh_connect_host_unavailable, profile.label)
                                        } else if (profile.protocol == ConnectionProtocol.MOSH && !moshAvailable) {
                                            stringResource(R.string.ssh_connect_host_mosh_unavailable, profile.label)
                                        } else {
                                            profile.label
                                        },
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
                if (selectedProfile?.canConnectFromQuickUi == false) {
                    Text(
                        stringResource(R.string.ssh_connect_saved_host_incompatible),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (purpose == SshConnectPurpose.NEW) {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it.take(UserSettings.MAX_LABEL_LENGTH) },
                        label = { Text(stringResource(R.string.ssh_connect_session_name_optional)) },
                        supportingText = {
                            Text(stringResource(R.string.ssh_connect_session_name_default))
                        },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it.take(UserSettings.MAX_HOST_LENGTH)
                        selectedProfileId = null
                        savePassword = false
                    },
                    label = { Text(stringResource(R.string.connections_field_host)) },
                    enabled = purpose == SshConnectPurpose.NEW,
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it.take(UserSettings.MAX_USERNAME_LENGTH)
                            selectedProfileId = null
                            savePassword = false
                        },
                        label = { Text(stringResource(R.string.host_editor_username)) },
                        modifier = Modifier.weight(1f),
                        enabled = purpose == SshConnectPurpose.NEW,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it.filter(Char::isDigit).take(5)
                            selectedProfileId = null
                            savePassword = false
                        },
                        label = { Text(stringResource(R.string.host_editor_port)) },
                        modifier = Modifier.weight(0.48f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = purpose == SshConnectPurpose.NEW,
                        singleLine = true,
                    )
                }
                if (protocol == ConnectionProtocol.MOSH) {
                    Text(stringResource(R.string.ssh_connect_mosh_server), style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = moshUdpPortOrRange,
                        onValueChange = { value ->
                            moshUdpPortOrRange = value
                                .filter { it.isDigit() || it == ':' }
                                .take(MAX_MOSH_PORT_INPUT_LENGTH)
                            selectedProfileId = null
                            savePassword = false
                        },
                        label = { Text(stringResource(R.string.ssh_connect_mosh_udp_optional)) },
                        supportingText = { Text(stringResource(R.string.ssh_connect_mosh_udp_example)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = moshServerExecutable,
                        onValueChange = { value ->
                            moshServerExecutable = value.take(MAX_MOSH_SERVER_EXECUTABLE_LENGTH)
                            selectedProfileId = null
                            savePassword = false
                        },
                        label = { Text(stringResource(R.string.ssh_connect_mosh_executable)) },
                        supportingText = {
                            Text(stringResource(R.string.ssh_connect_mosh_executable_detail))
                        },
                        singleLine = true,
                    )
                    parsedMoshOptions.error?.let { error ->
                        Text(
                            error.resolve(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (identities.isNotEmpty()) {
                    Text(stringResource(R.string.host_editor_authentication), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = selectedIdentityId == null,
                            onClick = {
                                selectedIdentityId = null
                                passphrase.wipe()
                            },
                            label = { Text(stringResource(R.string.host_editor_auth_password)) },
                        )
                        identities.forEach { identity ->
                            FilterChip(
                                selected = selectedIdentityId == identity.id,
                                enabled = identity.isAvailable,
                                onClick = {
                                    selectedIdentityId = identity.id
                                    password.wipe()
                                },
                                label = {
                                    Text(
                                        if (identity.isAvailable) {
                                            identity.label
                                        } else {
                                            stringResource(R.string.ssh_connect_identity_reimport, identity.label)
                                        },
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
                if (selectedIdentityId == null) {
                    WipeableSecretInput(
                        state = password,
                        label = stringResource(
                            if (canUseSavedPassword) R.string.ssh_connect_password_saved else R.string.host_editor_auth_password,
                        ),
                        testTag = SshConnectPasswordTestTag,
                        maxCharacters = MAX_PASSWORD_LENGTH,
                        onPresenceChanged = { present -> if (!present) savePassword = false },
                    )
                    if (canUseSavedPassword) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.ssh_connect_encrypted_password_used),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                confirmingForgetPasswordFor = requireNotNull(selectedProfile)
                            }) {
                                Text(stringResource(R.string.ssh_connect_forget))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = settingsReady && password.hasValue) {
                                savePassword = !savePassword
                                if (savePassword) saveProfile = true
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = savePassword,
                            onCheckedChange = { checked ->
                                savePassword = checked
                                if (checked) saveProfile = true
                            },
                            enabled = settingsReady && password.hasValue,
                        )
                        Column {
                            Text(stringResource(R.string.ssh_connect_save_password))
                            Text(
                                stringResource(R.string.ssh_connect_save_password_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val identity = identities.firstOrNull { it.id == selectedIdentityId }
                    WipeableSecretInput(
                        state = passphrase,
                        label = if (identity?.passphraseRequired == true) {
                            stringResource(R.string.host_connect_key_passphrase)
                        } else {
                            stringResource(R.string.ssh_connect_key_passphrase_optional)
                        },
                        testTag = SshConnectPassphraseTestTag,
                        maxCharacters = MAX_PASSWORD_LENGTH,
                    )
                    Text(
                        stringResource(R.string.ssh_connect_passphrase_detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = settingsReady) { saveProfile = !saveProfile },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = saveProfile,
                        onCheckedChange = { saveProfile = it },
                        enabled = settingsReady,
                    )
                    Column {
                        Text(stringResource(R.string.ssh_connect_save_host))
                        Text(
                            stringResource(R.string.ssh_connect_save_host_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            },
            confirmButton = {
                Button(
                    enabled = valid,
                    onClick = {
                        // Taking the password wipes the editor synchronously. Snapshot dependent
                        // options first so the empty-editor callback cannot turn a checked save
                        // request into a prompt-only host while this click is being dispatched.
                        val submittedSaveProfile = saveProfile
                        val submittedSavePassword = savePassword
                        val submittedPassword = password.takeChars()
                        val submittedPassphrase = passphrase.takeChars()
                        try {
                            onConnect(
                                host,
                                port,
                                username,
                                submittedPassword,
                                selectedIdentityId,
                                submittedPassphrase,
                                submittedSaveProfile,
                                selectedProfile?.id,
                                submittedSavePassword,
                                requireNotNull(connectionOptions),
                                sessionName,
                            )
                        } catch (error: Exception) {
                            submittedPassword.fill('\u0000')
                            submittedPassphrase.fill('\u0000')
                            throw error
                        }
                    },
                ) {
                    Text(
                        when (purpose) {
                            SshConnectPurpose.NEW -> stringResource(R.string.host_connect_action)
                            SshConnectPurpose.RECONNECT -> stringResource(R.string.ssh_connect_reconnect_action)
                            SshConnectPurpose.DUPLICATE -> stringResource(R.string.ssh_connect_duplicate_action)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = ::dismissAndClearSecrets) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    confirmingForgetPasswordFor?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmingForgetPasswordFor = null },
            title = { Text(stringResource(R.string.ssh_connect_forget_password_title)) },
            text = {
                Text(
                    stringResource(R.string.ssh_connect_forget_password_detail, profile.label),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingForgetPasswordFor = null
                        onForgetSavedPassword(profile.id)
                    },
                ) {
                    Text(stringResource(R.string.ssh_connect_forget_password_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForgetPasswordFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val MAX_PASSWORD_LENGTH = 1_024
internal const val SshConnectPasswordTestTag = "ssh-connect-password"
internal const val SshConnectPassphraseTestTag = "ssh-connect-passphrase"
private const val MAX_MOSH_PORT_INPUT_LENGTH = 11
private const val MAX_MOSH_SERVER_EXECUTABLE_LENGTH = 512

private fun ConnectionProtocol.displayName(): String = when (this) {
    ConnectionProtocol.SSH -> "SSH"
    ConnectionProtocol.MOSH -> "Mosh"
}
