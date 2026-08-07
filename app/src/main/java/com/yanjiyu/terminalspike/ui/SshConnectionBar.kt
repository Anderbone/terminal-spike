package com.yanjiyu.terminalspike.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.HostKeyPrompt
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey

@Composable
fun SessionChrome(
    sessions: List<SessionTabUi>,
    activeSessionId: Long,
    notice: String?,
    canAddSession: Boolean,
    profiles: List<SavedSshProfile>,
    identities: List<SavedSshIdentity>,
    knownHosts: List<KnownHostSummary>,
    snippets: List<CommandSnippet>,
    extraKeys: List<TerminalExtraKey>,
    settingsReady: Boolean,
    onSelectSession: (Long) -> Unit,
    onCloseSession: (Long) -> Unit,
    onConnect: (
        host: String,
        port: String,
        username: String,
        password: String,
        identityId: Long?,
        passphrase: String,
        saveProfile: Boolean,
        savedPasswordProfileId: Long?,
        savePassword: Boolean,
    ) -> Unit,
    onDisconnect: (Long) -> Unit,
    onHostKeyAnswer: (sessionId: Long, accept: Boolean) -> Unit,
    onSaveProfile: (label: String, host: String, port: String, username: String, existingId: Long?) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onForgetSavedPassword: (Long) -> Unit,
    onImportIdentity: (Uri) -> Unit,
    onDeleteIdentity: (Long) -> Unit,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
    onSaveSnippet: (label: String, command: String, appendEnter: Boolean, existingId: Long?) -> Unit,
    onDeleteSnippet: (Long) -> Unit,
    onSendSnippet: (Long) -> Unit,
    onSetKeyVisible: (TerminalExtraKey, Boolean) -> Unit,
    onMoveKey: (TerminalExtraKey, Int) -> Unit,
    onResetKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConnectDialog by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var initialProfile by remember { mutableStateOf<SavedSshProfile?>(null) }
    val identityImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportIdentity(uri)
    }
    val active = sessions.first { it.id == activeSessionId }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "TERM",
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 4.dp),
            )
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sessions.forEach { session ->
                    SessionTab(
                        session = session,
                        selected = session.id == activeSessionId,
                        onSelect = { onSelectSession(session.id) },
                        onClose = if (session.isBenchmark) null else ({ onCloseSession(session.id) }),
                    )
                }
            }
            TextButton(
                onClick = { showTools = true },
                enabled = settingsReady,
                modifier = Modifier.semantics { contentDescription = "Open local tools" },
            ) {
                Text("TOOLS", maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    initialProfile = null
                    showConnectDialog = true
                },
                enabled = canAddSession,
                modifier = Modifier.semantics { contentDescription = "New SSH session" },
            ) {
                Text("+ SSH", maxLines = 1)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(active.connectionState)
            Text(
                text = notice ?: sessionStatus(active),
                modifier = Modifier.weight(1f),
                color = if (notice != null || active.connectionState is ConnectionState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (
                !active.isBenchmark &&
                active.connectionState !is ConnectionState.Disconnected &&
                active.connectionState !is ConnectionState.Failed
            ) {
                TextButton(onClick = { onDisconnect(active.id) }) { Text("Disconnect") }
            }
        }
    }

    if (showConnectDialog) {
        SshConnectDialog(
            profiles = profiles,
            identities = identities,
            initialProfile = initialProfile,
            settingsReady = settingsReady,
            onDismiss = { showConnectDialog = false },
            onConnect = {
                    host, port, username, password, identityId, passphrase, saveProfile,
                    savedPasswordProfileId, savePassword,
                ->
                showConnectDialog = false
                onConnect(
                    host,
                    port,
                    username,
                    password,
                    identityId,
                    passphrase,
                    saveProfile,
                    savedPasswordProfileId,
                    savePassword,
                )
            },
            onForgetSavedPassword = onForgetSavedPassword,
        )
    }

    if (showTools) {
        TerminalToolsSheet(
            profiles = profiles,
            identities = identities,
            knownHosts = knownHosts,
            snippets = snippets,
            extraKeys = extraKeys,
            onDismiss = { showTools = false },
            onUseProfile = { profile ->
                initialProfile = profile
                showTools = false
                showConnectDialog = true
            },
            onSaveProfile = onSaveProfile,
            onDeleteProfile = onDeleteProfile,
            onImportIdentity = {
                identityImportLauncher.launch(arrayOf("application/x-pem-file", "application/octet-stream", "text/plain"))
            },
            onDeleteIdentity = onDeleteIdentity,
            onForgetKnownHost = onForgetKnownHost,
            onSaveSnippet = onSaveSnippet,
            onDeleteSnippet = onDeleteSnippet,
            onSendSnippet = { id ->
                onSendSnippet(id)
                showTools = false
            },
            onSetKeyVisible = onSetKeyVisible,
            onMoveKey = onMoveKey,
            onResetKeys = onResetKeys,
        )
    }

    val promptSession = sessions.firstOrNull { it.connectionState is ConnectionState.AwaitingApproval }
    val prompt = (promptSession?.connectionState as? ConnectionState.AwaitingApproval)?.prompt as? HostKeyPrompt
    if (promptSession != null && prompt != null) {
        AlertDialog(
            onDismissRequest = { onHostKeyAnswer(promptSession.id, false) },
            title = { Text("Trust this SSH host?") },
            text = {
                SelectionContainer {
                    Text(
                        "Host: ${prompt.host}\n" +
                            "Key: ${prompt.algorithm}\n" +
                            "Fingerprint: ${prompt.sha256Fingerprint}\n\n" +
                            "Compare this fingerprint with the server administrator before trusting it.",
                    )
                }
            },
            confirmButton = {
                Button(onClick = { onHostKeyAnswer(promptSession.id, true) }) {
                    Text("Trust and connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { onHostKeyAnswer(promptSession.id, false) }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionTab(
    session: SessionTabUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) bringIntoViewRequester.bringIntoView()
    }
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        label = "session-tab",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .bringIntoViewRequester(bringIntoViewRequester)
            .clickable(onClick = onSelect)
            .padding(start = 9.dp, end = if (onClose == null) 9.dp else 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(session.connectionState)
        Text(
            text = session.title,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (session.isBenchmark) FontFamily.Default else FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp).semantics {
                    contentDescription = "Close ${session.title} session"
                },
            ) {
                Text("×", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val target = when (state) {
        ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        ConnectionState.Connecting, is ConnectionState.AwaitingApproval -> MaterialTheme.colorScheme.tertiary
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val colour by animateColorAsState(targetValue = target, label = "connection-state")
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(colour))
}

private fun sessionStatus(session: SessionTabUi): String {
    if (session.isBenchmark) return "Native renderer lab · up to four SSH sessions"
    return when (val state = session.connectionState) {
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.Connecting -> "Connecting securely…"
        ConnectionState.Connected -> "Connected · xterm-256color · ${session.title}"
        is ConnectionState.AwaitingApproval -> "Verify connection identity to continue"
        is ConnectionState.Failed -> state.message
    }
}

@Composable
private fun SshConnectDialog(
    profiles: List<SavedSshProfile>,
    identities: List<SavedSshIdentity>,
    initialProfile: SavedSshProfile?,
    settingsReady: Boolean,
    onDismiss: () -> Unit,
    onConnect: (
        host: String,
        port: String,
        username: String,
        password: String,
        identityId: Long?,
        passphrase: String,
        saveProfile: Boolean,
        savedPasswordProfileId: Long?,
        savePassword: Boolean,
    ) -> Unit,
    onForgetSavedPassword: (Long) -> Unit,
) {
    var host by remember(initialProfile?.id) { mutableStateOf(initialProfile?.host.orEmpty()) }
    var port by remember(initialProfile?.id) { mutableStateOf(initialProfile?.port?.toString() ?: "22") }
    var username by remember(initialProfile?.id) { mutableStateOf(initialProfile?.username.orEmpty()) }
    var password by remember(initialProfile?.id) { mutableStateOf("") }
    var selectedIdentityId by remember { mutableStateOf<Long?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var selectedProfileId by remember(initialProfile?.id) { mutableStateOf(initialProfile?.id) }
    var saveProfile by remember(initialProfile?.id) { mutableStateOf(initialProfile != null) }
    var savePassword by remember(initialProfile?.id) { mutableStateOf(false) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val canUseSavedPassword = selectedIdentityId == null && selectedProfile?.hasSavedPassword == true &&
        password.isEmpty() && selectedProfile.host.equals(host, ignoreCase = true) &&
        selectedProfile.port.toString() == port && selectedProfile.username == username
    val validPort = port.toIntOrNull()?.let { it in 1..65_535 } == true
    val valid = host.isNotBlank() && host.none(Char::isWhitespace) && validPort &&
        username.isNotBlank() && username.none { it.isWhitespace() || it.isISOControl() } &&
        (selectedIdentityId != null || password.isNotEmpty() || canUseSavedPassword)

    fun dismissAndClearSecrets() {
        password = ""
        passphrase = ""
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismissAndClearSecrets,
        title = { Text("New SSH session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profiles.isNotEmpty()) {
                    Text("Saved hosts", style = MaterialTheme.typography.labelMedium)
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
                                    password = ""
                                    passphrase = ""
                                    selectedIdentityId = null
                                    selectedProfileId = profile.id
                                    saveProfile = true
                                    savePassword = false
                                },
                                label = { Text(profile.label, maxLines = 1) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it.take(UserSettings.MAX_HOST_LENGTH)
                        selectedProfileId = null
                        savePassword = false
                    },
                    label = { Text("Host") },
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
                        label = { Text("Username") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it.filter(Char::isDigit).take(5)
                            selectedProfileId = null
                            savePassword = false
                        },
                        label = { Text("Port") },
                        modifier = Modifier.weight(0.48f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                if (identities.isNotEmpty()) {
                    Text("Authentication", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = selectedIdentityId == null,
                            onClick = {
                                selectedIdentityId = null
                                passphrase = ""
                            },
                            label = { Text("Password") },
                        )
                        identities.forEach { identity ->
                            FilterChip(
                                selected = selectedIdentityId == identity.id,
                                onClick = {
                                    selectedIdentityId = identity.id
                                    password = ""
                                },
                                label = { Text(identity.label, maxLines = 1) },
                            )
                        }
                    }
                }
                if (selectedIdentityId == null) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it.take(MAX_PASSWORD_LENGTH)
                            if (password.isEmpty()) savePassword = false
                        },
                        label = { Text(if (canUseSavedPassword) "Password saved on device" else "Password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (canUseSavedPassword) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Encrypted password will be used.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onForgetSavedPassword(requireNotNull(selectedProfile).id) }) {
                                Text("Forget")
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = settingsReady && password.isNotEmpty()) {
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
                            enabled = settingsReady && password.isNotEmpty(),
                        )
                        Column {
                            Text("Save password on this device")
                            Text(
                                "Keystore encrypted · not backed up · usable while the app is unlocked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val identity = identities.firstOrNull { it.id == selectedIdentityId }
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.take(MAX_PASSWORD_LENGTH) },
                        label = {
                            Text(
                                if (identity?.passphraseRequired == true) {
                                    "Key passphrase"
                                } else {
                                    "Key passphrase (if any)"
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Text(
                        "The passphrase is used for this connection only and is never saved.",
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
                        Text("Save host details")
                        Text(
                            "Host, port and username only — never the password",
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
                    val submittedPassword = password
                    val submittedPassphrase = passphrase
                    password = ""
                    passphrase = ""
                    onConnect(
                        host,
                        port,
                        username,
                        submittedPassword,
                        selectedIdentityId,
                        submittedPassphrase,
                        saveProfile,
                        selectedProfile?.id.takeIf { canUseSavedPassword },
                        savePassword,
                    )
                },
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismissAndClearSecrets) { Text("Cancel") }
        },
    )
}

private const val MAX_PASSWORD_LENGTH = 1_024
