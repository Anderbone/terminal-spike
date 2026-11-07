package com.yanjiyu.terminalspike.ui.guide

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R

/** Optional detailed documentation stays outside the visual guide and first-install flow. */
@Composable
internal fun GuideReferenceScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var expandedReference by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("user-guide-index"), contentPadding = PaddingValues(20.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.guide_previous)) }
            Text(stringResource(R.string.guide_more_help), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 16.dp).semantics { heading() })
        }
        GuideReferenceTopic.entries.forEach { topic ->
            val expanded = expandedReference == topic.name
            item(key = topic.name) {
                val state = stringResource(if (expanded) R.string.guide_reference_expanded else R.string.guide_reference_collapsed)
                Column(Modifier.fillMaxWidth().testTag("guide-reference-${topic.name}")
                    .clickable(role = Role.Button) { expandedReference = if (expanded) null else topic.name }
                    .semantics { stateDescription = state }.padding(vertical = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(topic.label), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                }
            }
            if (expanded) {
                items(topic.tips, key = { "${topic.name}-$it" }) { GuideReferenceTip(topic, it) }
                items(topic.links, key = { "${topic.name}-${it.first}" }) { (label, url) ->
                    TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(label)) }
                }
            }
        }
    }
}

@Composable
private fun GuideReferenceTip(topic: GuideReferenceTopic, @StringRes tip: Int) {
    val text = stringResource(tip)
    val command = tip in topic.commandSamples
    val code = command || tip in topic.codeSamples
    val copyable = code || tip == topic.agentInstructions
    val context = LocalContext.current
    val clipboardLabel = stringResource(topic.label)
    var copied by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 12.dp)) {
        if (copyable) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge,
                    fontFamily = if (code) FontFamily.Monospace else null)
            }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(clipboardLabel, text))
                copied = true
            }, modifier = Modifier.testTag("guide-reference-copy-$tip")) {
                Text(stringResource(when {
                    copied -> R.string.guide_copied
                    command -> R.string.guide_copy_command
                    code -> R.string.guide_copy_settings
                    else -> R.string.guide_copy_agent_instructions
                }))
            }
        } else Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
