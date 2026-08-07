package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey

private enum class ToolSection(val label: String) {
    PROFILES("Hosts"),
    IDENTITIES("Security"),
    KEYS("Keys"),
    SNIPPETS("Snippets"),
}

@Composable
fun TerminalToolsSheet(
    profiles: List<SavedSshProfile>,
    identities: List<SavedSshIdentity>,
    knownHosts: List<KnownHostSummary>,
    snippets: List<CommandSnippet>,
    extraKeys: List<TerminalExtraKey>,
    onDismiss: () -> Unit,
    onUseProfile: (SavedSshProfile) -> Unit,
    onSaveProfile: (label: String, host: String, port: String, username: String, existingId: Long?) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onImportIdentity: () -> Unit,
    onDeleteIdentity: (Long) -> Unit,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
    onSaveSnippet: (label: String, command: String, appendEnter: Boolean, existingId: Long?) -> Unit,
    onDeleteSnippet: (Long) -> Unit,
    onSendSnippet: (Long) -> Unit,
    onSetKeyVisible: (TerminalExtraKey, Boolean) -> Unit,
    onMoveKey: (TerminalExtraKey, Int) -> Unit,
    onResetKeys: () -> Unit,
) {
    var section by remember { mutableStateOf(ToolSection.PROFILES) }
    var addingProfile by remember { mutableStateOf(false) }
    var addingSnippet by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<SavedSshProfile?>(null) }
    var editingSnippet by remember { mutableStateOf<CommandSnippet?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp)) {
                    Text(
                        text = "Local tools",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Text(
                        text = "Encrypted on this device · passwords are saved only when you opt in",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ToolSection.entries.forEach { item ->
                            FilterChip(
                                selected = section == item,
                                onClick = { section = item },
                                label = { Text(item.label) },
                            )
                        }
                    }
                    when (section) {
                        ToolSection.PROFILES -> ProfilesSection(
                            profiles = profiles,
                            onAdd = { addingProfile = true },
                            onUse = onUseProfile,
                            onEdit = {
                                editingProfile = it
                                addingProfile = true
                            },
                            onDelete = onDeleteProfile,
                        )
                        ToolSection.KEYS -> KeysSection(
                            selectedKeys = extraKeys,
                            onSetVisible = onSetKeyVisible,
                            onMove = onMoveKey,
                            onReset = onResetKeys,
                        )
                        ToolSection.SNIPPETS -> SnippetsSection(
                            snippets = snippets,
                            onAdd = { addingSnippet = true },
                            onSend = onSendSnippet,
                            onEdit = {
                                editingSnippet = it
                                addingSnippet = true
                            },
                            onDelete = onDeleteSnippet,
                        )
                        ToolSection.IDENTITIES -> IdentitiesSection(
                            identities = identities,
                            knownHosts = knownHosts,
                            onImport = onImportIdentity,
                            onDelete = onDeleteIdentity,
                            onForgetKnownHost = onForgetKnownHost,
                        )
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
}

@Composable
private fun IdentitiesSection(
    identities: List<SavedSshIdentity>,
    knownHosts: List<KnownHostSummary>,
    onImport: () -> Unit,
    onDelete: (Long) -> Unit,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(title = "SSH private keys", action = "Import key", onAction = onImport)
            Text(
                text = "Imported through Android's document picker, encrypted on this device, and never exported by the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (identities.isEmpty()) item { EmptyLine("No private keys imported.") }
        items(identities, key = { it.id }) { identity ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(identity.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${identity.keyType} · ${identity.fingerprint}" +
                            if (identity.passphraseRequired) " · passphrase required" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { onDelete(identity.id) },
                    modifier = Modifier.semantics {
                        contentDescription = "Delete ${identity.label} private key"
                    },
                ) { Text("×") }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
        }
        item {
            Text(
                "Trusted host keys",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
            )
        }
        if (knownHosts.isEmpty()) item { EmptyLine("No SSH host keys trusted yet.") }
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
                    Text("Forget")
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
                title = "Saved SSH hosts",
                action = "Add host",
                onAction = onAdd,
            )
        }
        if (profiles.isEmpty()) {
            item { EmptyLine("No saved hosts yet.") }
        }
        items(profiles, key = { it.id }) { profile ->
            ToolRow(
                title = profile.label,
                detail = "${profile.username}@${profile.host}:${profile.port}" +
                    if (profile.hasSavedPassword) " · password saved" else "",
                primaryAction = "Use",
                onPrimary = { onUse(profile) },
                onEdit = { onEdit(profile) },
                onDelete = { onDelete(profile.id) },
                deleteDescription = "Delete ${profile.label} profile",
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
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(title = "Command snippets", action = "Add snippet", onAction = onAdd)
            Text(
                text = "Sending is always explicit. Avoid storing passwords or tokens in commands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (snippets.isEmpty()) {
            item { EmptyLine("No snippets yet.") }
        }
        items(snippets, key = { it.id }) { snippet ->
            ToolRow(
                title = snippet.label,
                detail = snippet.command.replace('\n', ' ').take(80) + if (snippet.appendEnter) "  ↵" else "",
                primaryAction = "Send",
                onPrimary = { onSend(snippet.id) },
                onEdit = { onEdit(snippet) },
                onDelete = { onDelete(snippet.id) },
                deleteDescription = "Delete ${snippet.label} snippet",
                monospaceDetail = true,
            )
        }
    }
}

@Composable
private fun KeysSection(
    selectedKeys: List<TerminalExtraKey>,
    onSetVisible: (TerminalExtraKey, Boolean) -> Unit,
    onMove: (TerminalExtraKey, Int) -> Unit,
    onReset: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(title = "Extra-key bar", action = "Reset", onAction = onReset)
            Text(
                text = "Choose visible keys and arrange their left-to-right order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        items(TerminalExtraKey.entries, key = { it.name }) { key ->
            val selectedIndex = selectedKeys.indexOf(key)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetVisible(key, selectedIndex < 0) }
                    .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selectedIndex >= 0,
                    onCheckedChange = { onSetVisible(key, it) },
                )
                Text(key.label, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(
                    enabled = selectedIndex > 0,
                    onClick = { onMove(key, -1) },
                ) { Text("↑") }
                TextButton(
                    enabled = selectedIndex >= 0 && selectedIndex < selectedKeys.lastIndex,
                    onClick = { onMove(key, 1) },
                ) { Text("↓") }
            }
            HorizontalDivider()
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
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onPrimary) { Text(primaryAction) }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = deleteDescription },
        ) { Text("×") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Save SSH host" else "Edit SSH host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it.take(UserSettings.MAX_LABEL_LENGTH) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(host, { host = it.take(UserSettings.MAX_HOST_LENGTH) }, label = { Text("Host") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(username, { username = it.take(UserSettings.MAX_USERNAME_LENGTH) }, label = { Text("Username") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, modifier = Modifier.weight(0.48f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
                Text(
                    "Passwords are stored only from the connection dialog when you explicitly opt in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(label, host, port, username) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    val valid = label.isNotBlank() && label.none(Char::isISOControl) && command.isNotBlank() &&
        command.none { it.isISOControl() && it !in "\r\n\t" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (snippet == null) "New command snippet" else "Edit command snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it.take(UserSettings.MAX_LABEL_LENGTH) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(UserSettings.MAX_SNIPPET_LENGTH) },
                    label = { Text("Command") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { appendEnter = !appendEnter },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = appendEnter, onCheckedChange = { appendEnter = it })
                    Text("Press Enter after sending")
                }
                Text("Stored encrypted. Do not save passwords or access tokens in snippets.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(label, command, appendEnter) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
