package com.yanjiyu.terminalspike.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
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
