package com.yanjiyu.terminalspike.ui.settings

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.settings.AppLanguage
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import java.util.Locale

internal val LocalSettingsSearchAnchor = compositionLocalOf<Int?> { null }

@Composable
internal fun rememberSettingsSearchResults(query: String): List<SettingsSearchEntry> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(query, configuration) {
        val resources = listOf(context.resources) + listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE).map { locale ->
            context.createConfigurationContext(Configuration(configuration).apply { setLocale(locale) }).resources
        }
        val categories = SettingsCategory.entries.filter {
            it in userFacingSettingsCategories && (BuildConfig.DEBUG || it != SettingsCategory.DEVELOPER)
        }.map { SettingsSearchEntry(it, it.titleRes, it.searchTerms, null) }
        searchSettings(query, settingsSearchEntries + categories) { id -> resources.map { it.getString(id) } }
    }
}

@Composable
internal fun SettingsSearchResults(
    results: List<SettingsSearchEntry>,
    onSelected: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.testTag("settings-search-results"), contentPadding = PaddingValues(20.dp, 16.dp)) {
        if (results.isEmpty()) item {
            Text(stringResource(R.string.settings_no_results_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_no_results_message), style = MaterialTheme.typography.bodyMedium)
        }
        items(results, key = SettingsSearchEntry::id) { entry ->
            Surface(
                onClick = { onSelected(entry) },
                modifier = Modifier.fillMaxWidth().testTag("settings-search-result-${entry.titleRes}"),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(entry.titleRes), style = MaterialTheme.typography.titleSmall)
                    if (entry.titleRes != entry.category.titleRes) {
                        Text(
                            stringResource(R.string.settings_search_result_path, stringResource(entry.category.titleRes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

/** Build the lazy item index from the actual page content, including conditional rows. */
private class IndexedSettingsOptions : LazyListScope {
    private var count = 0
    val anchors = mutableMapOf<Any, Int>()
    val blocks = mutableListOf<LazyListScope.() -> Unit>()

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        if (key != null) anchors[key] = count
        count++
        blocks += { item(key, contentType, content) }
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        this.count += count
        blocks += { items(count, key, contentType, itemContent) }
    }
}

@Composable
internal fun SearchableSettingsOptions(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    val anchor = LocalSettingsSearchAnchor.current
    val indexed = IndexedSettingsOptions().apply(content)
    val targetIndex = anchor?.let { indexed.anchors[it] }
    val listState = rememberLazyListState()
    LaunchedEffect(anchor, targetIndex) {
        if (targetIndex != null) listState.scrollToItem(targetIndex)
    }
    LazyColumn(modifier, state = listState, contentPadding = contentPadding, verticalArrangement = verticalArrangement) {
        val delegate = this
        val highlighted = object : LazyListScope by delegate {
            override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
                delegate.item(key, contentType) {
                    if (key != null && key == anchor) {
                        val itemScope = this
                        Column(
                            Modifier.fillMaxWidth().background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                MaterialTheme.shapes.small,
                            ).testTag("settings-search-target"),
                        ) { content(itemScope) }
                    } else content()
                }
            }
        }
        indexed.blocks.forEach { it(highlighted) }
    }
}

@Composable
internal fun SettingsSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    searchDescription: String = stringResource(R.string.settings_search_label),
    clearDescription: String = stringResource(R.string.settings_clear_search),
    searchTestTag: String = SettingsSearchTestTag,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            BasicTextField(
                value = query,
                enabled = enabled,
                onValueChange = { onQueryChange(it.take(80)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(searchTestTag).semantics { contentDescription = searchDescription },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { input ->
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = if (query.isEmpty()) 12.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.SEARCH,
                            modifier = Modifier.size(18.dp),
                        )
                        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    searchDescription,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            input()
                        }
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.size(48.dp).semantics { contentDescription = clearDescription },
                            ) {
                                ConnectionsGlyphIcon(ConnectionsGlyph.CLOSE, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
            )
        }
        AppLanguageMenu()
    }
}

@Composable
private fun AppLanguageMenu() {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    val selected = AppLanguage.selected(context)
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.settings_language_accessible)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp).testTag("settings-language-menu")
                .semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_language_globe), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "" to R.string.settings_language_system_bilingual,
                "zh" to R.string.settings_language_chinese,
                "en" to R.string.settings_language_english,
            ).forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (selected == tag) Text("✓") },
                    enabled = activity != null,
                    onClick = {
                        expanded = false
                        activity?.let { AppLanguage.select(it, tag) }
                    },
                    modifier = Modifier.testTag("app-language-" + tag.ifEmpty { "system" })
                        .semantics { this.selected = selected == tag },
                )
            }
        }
    }
}

internal const val SettingsSearchTestTag = "settings-search"
