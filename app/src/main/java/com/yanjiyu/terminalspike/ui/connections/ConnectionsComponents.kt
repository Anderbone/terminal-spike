package com.yanjiyu.terminalspike.ui.connections

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.quantityText
import com.yanjiyu.terminalspike.ui.resolve
import com.yanjiyu.terminalspike.ui.uiText
import com.yanjiyu.terminalspike.ui.theme.iconMetrics
import com.yanjiyu.terminalspike.ui.theme.motion
import com.yanjiyu.terminalspike.ui.theme.spacing
import com.yanjiyu.terminalspike.ui.theme.statusColors

private val ExpandedConnectionsMinimumContentWidth = 680.dp

@Composable
internal fun ConnectionsHeader(
    selectedTab: ConnectionsTab,
    searchQuery: String,
    hostSort: HostSort,
    hostFilters: HostFilters,
    hostGroups: List<String>,
    catalogReady: Boolean,
    onNavigateBack: (() -> Unit)?,
    callbacks: ConnectionsCallbacks,
    showTitle: Boolean = true,
    showSearch: Boolean = true,
) {
    var hostControlsExpanded by rememberSaveable { mutableStateOf(hostSort != HostSort.NAME || hostFilters.isActive) }
    val hasHostControls = selectedTab == ConnectionsTab.HOSTS
    val addActions = when (selectedTab) {
        ConnectionsTab.HOSTS -> listOfNotNull(
            callbacks.onAddHost?.let { CatalogAddAction(R.string.connections_add_host, it) },
        )
        ConnectionsTab.KEYS -> listOfNotNull(
            callbacks.onImportKey?.let { CatalogAddAction(R.string.connections_import_key, it) },
            callbacks.onGenerateKey?.let { CatalogAddAction(R.string.connections_generate_key, it) },
        )
        ConnectionsTab.SNIPPETS -> listOfNotNull(
            callbacks.onAddSnippet?.let { CatalogAddAction(R.string.connections_add_snippet, it) },
        )
    }
    val backDescription = stringResource(R.string.connections_back)
    val screenDescription = stringResource(R.string.connections_screen_description)
    val searchDescription = stringResource(selectedTab.searchLabelResId)
    val clearSearchDescription = stringResource(R.string.connections_clear_search)
    val hostControlsDescription = stringResource(
        if (hostControlsExpanded) {
            R.string.connections_hide_host_controls
        } else {
            R.string.connections_show_host_controls
        },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ConnectionsHeaderTestTag)
            .padding(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.large,
                top = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.small,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        if (showTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onNavigateBack?.let { navigateBack ->
                    IconButton(
                        onClick = navigateBack,
                        modifier = Modifier.semantics { contentDescription = backDescription },
                    ) {
                        ConnectionsGlyphIcon(
                            ConnectionsGlyph.BACK,
                            Modifier.size(MaterialTheme.iconMetrics.standard),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (onNavigateBack == null) {
                                MaterialTheme.spacing.extraSmall
                            } else {
                                0.dp
                            },
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.connections_title),
                        modifier = Modifier.semantics {
                            heading()
                            contentDescription = screenDescription
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.connections_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                ConnectionsAddButton(actions = addActions)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!showTitle) {
                ConnectionsAddButton(actions = addActions)
            }
            if (showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = callbacks.onSearchQueryChanged,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = searchDescription
                        },
                    enabled = catalogReady,
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    placeholder = { Text(searchDescription) },
                    leadingIcon = {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.SEARCH,
                            modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { callbacks.onSearchQueryChanged("") },
                                modifier = Modifier.semantics {
                                    contentDescription = clearSearchDescription
                                },
                            ) {
                                ConnectionsGlyphIcon(
                                    glyph = ConnectionsGlyph.CLOSE,
                                    modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            if (showSearch && hasHostControls) {
                val filterContainer by animateColorAsState(
                    targetValue = if (hostControlsExpanded || hostFilters.isActive || hostSort != HostSort.NAME) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    animationSpec = androidx.compose.animation.core.tween(MaterialTheme.motion.quickDurationMillis),
                    label = "host-filter-colour",
                )
                Surface(
                    onClick = { hostControlsExpanded = !hostControlsExpanded },
                    modifier = Modifier.semantics {
                        contentDescription = hostControlsDescription
                    },
                    color = filterContainer,
                    contentColor = if (hostControlsExpanded || hostFilters.isActive || hostSort != HostSort.NAME) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Box(
                        modifier = Modifier.size(MaterialTheme.iconMetrics.minimumTouchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.FILTER,
                            modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSearch && hasHostControls && hostControlsExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 3 }),
        ) {
            HostFilterControls(
                sort = hostSort,
                filters = hostFilters,
                availableGroups = hostGroups,
                onSortSelected = callbacks.onHostSortSelected,
                onFavouritesOnlyChanged = callbacks.onFavouritesOnlyChanged,
                onGroupSelected = callbacks.onHostGroupSelected,
                onClear = callbacks.onClearHostFilters,
            )
        }
    }
}

private data class CatalogAddAction(
    @StringRes val labelRes: Int,
    val onClick: () -> Unit,
)

@Composable
private fun ConnectionsAddButton(actions: List<CatalogAddAction>) {
    if (actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val buttonDescription = if (actions.size == 1) {
        stringResource(actions.single().labelRes)
    } else {
        stringResource(R.string.connections_add_item)
    }
    Box {
        FilledTonalButton(
            onClick = {
                if (actions.size == 1) actions.single().onClick() else expanded = true
            },
            modifier = Modifier.semantics {
                contentDescription = buttonDescription
            },
            contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.medium),
        ) {
            ConnectionsGlyphIcon(
                glyph = ConnectionsGlyph.ADD,
                modifier = Modifier.size(MaterialTheme.iconMetrics.compact),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.small))
            Text(stringResource(R.string.add))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.labelRes)) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun HostFilterControls(
    sort: HostSort,
    filters: HostFilters,
    availableGroups: List<String>,
    onSortSelected: (HostSort) -> Unit,
    onFavouritesOnlyChanged: (Boolean) -> Unit,
    onGroupSelected: (String?) -> Unit,
    onClear: () -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    val sortLabel = stringResource(sort.labelResId)
    val sortDescription = stringResource(R.string.connections_sort_hosts_by, sortLabel)
    val allGroupsLabel = stringResource(R.string.connections_all_groups)
    val groupDescription = stringResource(
        R.string.connections_filter_group,
        filters.group ?: allGroupsLabel,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .animateContentSize()
            .testTag(ConnectionsHostFiltersTestTag),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            OutlinedButton(
                onClick = { sortMenuExpanded = true },
                modifier = Modifier.semantics { contentDescription = sortDescription },
            ) {
                Text(stringResource(R.string.connections_sort_value, sortLabel))
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                HostSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelResId)) },
                        onClick = {
                            sortMenuExpanded = false
                            onSortSelected(option)
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = filters.favouritesOnly,
            onClick = { onFavouritesOnlyChanged(!filters.favouritesOnly) },
            label = { Text(stringResource(R.string.connections_favourites)) },
            leadingIcon = if (filters.favouritesOnly) {
                {
                    ConnectionsGlyphIcon(
                        ConnectionsGlyph.STAR,
                        Modifier.size(MaterialTheme.iconMetrics.compact),
                    )
                }
            } else {
                null
            },
        )
        if (availableGroups.isNotEmpty()) {
            Box {
                OutlinedButton(
                    onClick = { groupMenuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = groupDescription
                    },
                ) {
                    Text(filters.group ?: allGroupsLabel)
                }
                DropdownMenu(
                    expanded = groupMenuExpanded,
                    onDismissRequest = { groupMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(allGroupsLabel) },
                        onClick = {
                            groupMenuExpanded = false
                            onGroupSelected(null)
                        },
                    )
                    availableGroups.forEach { group ->
                        DropdownMenuItem(
                            text = { Text(group) },
                            onClick = {
                                groupMenuExpanded = false
                                onGroupSelected(group)
                            },
                        )
                    }
                }
            }
        }
        if (filters.isActive || sort != HostSort.NAME) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.connections_clear_filters))
            }
        }
    }
}

@Composable
internal fun ConnectionsTabs(
    selectedTab: ConnectionsTab,
    counts: CatalogCounts,
    onTabSelected: (ConnectionsTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        modifier = Modifier.fillMaxWidth().testTag(ConnectionsTabsTestTag),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {},
    ) {
        ConnectionsTab.entries.forEach { tab ->
            val count = when (tab) {
                ConnectionsTab.HOSTS -> counts.hosts
                ConnectionsTab.KEYS -> counts.keys
                ConnectionsTab.SNIPPETS -> counts.snippets
            }
            val tabLabel = stringResource(tab.labelResId)
            val tabDescription = if (count == 0) {
                tabLabel
            } else {
                pluralStringResource(
                    R.plurals.connections_tab_item_count_description,
                    count,
                    tabLabel,
                    count,
                )
            }
            Tab(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                modifier = Modifier
                    .heightIn(min = MaterialTheme.iconMetrics.minimumTouchTarget)
                    .semantics {
                        contentDescription = tabDescription
                    },
                text = {
                    Text(
                        text = if (count == 0) {
                            tabLabel
                        } else {
                            stringResource(R.string.connections_tab_item_count, tabLabel, count)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
            )
        }
    }
}

@Composable
internal fun ConnectionsLoadingState() {
    val loadingDescription = stringResource(R.string.connections_loading_description)
    Column(
        modifier = Modifier.fillMaxSize().testTag(ConnectionsLoadingTestTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(MaterialTheme.iconMetrics.prominent)
                .semantics { contentDescription = loadingDescription },
            strokeWidth = 3.dp,
        )
        Text(
            text = stringResource(R.string.connections_loading),
            modifier = Modifier.padding(top = MaterialTheme.spacing.large),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ConnectionsErrorState(message: UiText, onRetry: () -> Unit) {
    ConnectionsMessageState(
        title = uiText(R.string.connections_unavailable_title),
        detail = message,
        actionLabel = uiText(R.string.try_again),
        onAction = onRetry,
        modifier = Modifier.testTag(ConnectionsErrorTestTag),
        glyph = ConnectionsGlyph.WARNING,
    )
}

@Composable
internal fun ConnectionsReadyContent(
    rows: ConnectionsTabRowsUi,
    navigationExpanded: Boolean,
    nowEpochMillis: Long,
    selectedHostId: String?,
    selectedKeyId: String?,
    selectedSnippetId: String?,
    onSelectHost: (String) -> Unit,
    onSelectKey: (String) -> Unit,
    onSelectSnippet: (String) -> Unit,
    onConnectHost: ((String) -> Unit)?,
    onOpenSftp: ((String) -> Unit)?,
    onEditHost: ((String) -> Unit)?,
    onDeleteHost: ((HostRowUi) -> Unit)?,
    onCopyPublicKey: ((String) -> Unit)?,
    onViewKeyFingerprint: ((String) -> Unit)?,
    onRenameKey: ((String) -> Unit)?,
    onDeleteKey: ((KeyRowUi) -> Unit)?,
    onInsertSnippet: ((String) -> Unit)?,
    onRunSnippet: ((SnippetRowUi) -> Unit)?,
    onCopySnippet: ((String) -> Unit)?,
    onEditSnippet: ((String) -> Unit)?,
    onDeleteSnippet: ((SnippetRowUi) -> Unit)?,
    onEmptyAction: (() -> Unit)?,
    onClearNoResults: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val listDetail = navigationExpanded && maxWidth >= ExpandedConnectionsMinimumContentWidth
        when (rows) {
            is ConnectionsTabRowsUi.Hosts -> {
                val selection = rows.content.rows.firstOrNull { it.id == selectedHostId }
                    ?: rows.content.rows.firstOrNull()
                CatalogAdaptiveLayout(
                    listDetail = listDetail,
                    list = {
                        HostCatalogList(
                            content = rows.content,
                            selectedId = selection?.id,
                            listDetail = listDetail,
                            nowEpochMillis = nowEpochMillis,
                            onSelect = onSelectHost,
                            onConnect = onConnectHost,
                            onOpenSftp = onOpenSftp,
                            onEdit = onEditHost,
                            onDelete = onDeleteHost,
                            onEmptyAction = onEmptyAction,
                            onClearNoResults = onClearNoResults,
                        )
                    },
                    detail = {
                        HostDetailPane(
                            host = selection,
                            nowEpochMillis = nowEpochMillis,
                            onConnect = onConnectHost,
                            onOpenSftp = onOpenSftp,
                            onEdit = onEditHost,
                            onDelete = onDeleteHost,
                        )
                    },
                )
            }
            is ConnectionsTabRowsUi.Keys -> {
                val selection = rows.content.rows.firstOrNull { it.id == selectedKeyId }
                    ?: rows.content.rows.firstOrNull()
                CatalogAdaptiveLayout(
                    listDetail = listDetail,
                    list = {
                        KeyCatalogList(
                            content = rows.content,
                            selectedId = selection?.id,
                            listDetail = listDetail,
                            onSelect = onSelectKey,
                            onCopyPublicKey = onCopyPublicKey,
                            onViewFingerprint = onViewKeyFingerprint,
                            onRename = onRenameKey,
                            onDelete = onDeleteKey,
                            onEmptyAction = onEmptyAction,
                            onClearNoResults = onClearNoResults,
                        )
                    },
                    detail = {
                        KeyDetailPane(
                            key = selection,
                            onCopyPublicKey = onCopyPublicKey,
                            onViewFingerprint = onViewKeyFingerprint,
                            onRename = onRenameKey,
                            onDelete = onDeleteKey,
                        )
                    },
                )
            }
            is ConnectionsTabRowsUi.Snippets -> {
                val selection = rows.content.rows.firstOrNull { it.id == selectedSnippetId }
                    ?: rows.content.rows.firstOrNull()
                CatalogAdaptiveLayout(
                    listDetail = listDetail,
                    list = {
                        SnippetCatalogList(
                            content = rows.content,
                            selectedId = selection?.id,
                            listDetail = listDetail,
                            onSelect = onSelectSnippet,
                            onInsert = onInsertSnippet,
                            onRun = onRunSnippet,
                            onCopy = onCopySnippet,
                            onEdit = onEditSnippet,
                            onDelete = onDeleteSnippet,
                            onEmptyAction = onEmptyAction,
                            onClearNoResults = onClearNoResults,
                        )
                    },
                    detail = {
                        SnippetDetailPane(
                            snippet = selection,
                            onInsert = onInsertSnippet,
                            onRun = onRunSnippet,
                            onCopy = onCopySnippet,
                            onEdit = onEditSnippet,
                            onDelete = onDeleteSnippet,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CatalogAdaptiveLayout(
    listDetail: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (listDetail) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.56f)
                    .fillMaxHeight()
                    .testTag(ConnectionsExpandedListTestTag),
            ) {
                list()
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .testTag(ConnectionsExpandedDetailTestTag),
            ) {
                detail()
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().testTag(ConnectionsCompactListTestTag)) {
            list()
        }
    }
}

@Composable
private fun HostCatalogList(
    content: ConnectionsListUi<HostRowUi>,
    selectedId: String?,
    listDetail: Boolean,
    nowEpochMillis: Long,
    onSelect: (String) -> Unit,
    onConnect: ((String) -> Unit)?,
    onOpenSftp: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((HostRowUi) -> Unit)?,
    onEmptyAction: (() -> Unit)?,
    onClearNoResults: () -> Unit,
) {
    if (content.emptyState != null) {
        ConnectionsCatalogEmptyState(
            tab = ConnectionsTab.HOSTS,
            reason = content.emptyState,
            onAdd = onEmptyAction,
            onClear = onClearNoResults,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.extraLarge),
    ) {
        item {
            CatalogCountHeader(
                pluralStringResource(
                    R.plurals.connections_host_count,
                    content.rows.size,
                    content.rows.size,
                ),
            )
        }
        items(content.rows, key = HostRowUi::id) { host ->
            val activationDescription = stringResource(
                R.string.connections_connect_to,
                host.displayName,
            )
            HostCatalogRow(
                host = host,
                selected = listDetail && selectedId == host.id,
                nowEpochMillis = nowEpochMillis,
                onActivate = onConnect?.let { connect -> { connect(host.id) } },
                activationDescription = activationDescription,
                onShowDetails = if (listDetail) ({ onSelect(host.id) }) else null,
                onConnect = onConnect,
                onOpenSftp = onOpenSftp,
                onEdit = onEdit,
                onDelete = onDelete,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun KeyCatalogList(
    content: ConnectionsListUi<KeyRowUi>,
    selectedId: String?,
    listDetail: Boolean,
    onSelect: (String) -> Unit,
    onCopyPublicKey: ((String) -> Unit)?,
    onViewFingerprint: ((String) -> Unit)?,
    onRename: ((String) -> Unit)?,
    onDelete: ((KeyRowUi) -> Unit)?,
    onEmptyAction: (() -> Unit)?,
    onClearNoResults: () -> Unit,
) {
    if (content.emptyState != null) {
        ConnectionsCatalogEmptyState(
            tab = ConnectionsTab.KEYS,
            reason = content.emptyState,
            onAdd = onEmptyAction,
            onClear = onClearNoResults,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.extraLarge),
    ) {
        item {
            CatalogCountHeader(
                pluralStringResource(
                    R.plurals.connections_key_count,
                    content.rows.size,
                    content.rows.size,
                ),
            )
        }
        items(content.rows, key = KeyRowUi::id) { key ->
            KeyCatalogRow(
                key = key,
                selected = listDetail && selectedId == key.id,
                onActivate = if (listDetail) ({ onSelect(key.id) }) else null,
                onCopyPublicKey = onCopyPublicKey,
                onViewFingerprint = onViewFingerprint,
                onRename = onRename,
                onDelete = onDelete,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun SnippetCatalogList(
    content: ConnectionsListUi<SnippetRowUi>,
    selectedId: String?,
    listDetail: Boolean,
    onSelect: (String) -> Unit,
    onInsert: ((String) -> Unit)?,
    onRun: ((SnippetRowUi) -> Unit)?,
    onCopy: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((SnippetRowUi) -> Unit)?,
    onEmptyAction: (() -> Unit)?,
    onClearNoResults: () -> Unit,
) {
    if (content.emptyState != null) {
        ConnectionsCatalogEmptyState(
            tab = ConnectionsTab.SNIPPETS,
            reason = content.emptyState,
            onAdd = onEmptyAction,
            onClear = onClearNoResults,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.extraLarge),
    ) {
        item {
            CatalogCountHeader(
                pluralStringResource(
                    R.plurals.connections_snippet_count,
                    content.rows.size,
                    content.rows.size,
                ),
            )
        }
        items(content.rows, key = SnippetRowUi::id) { snippet ->
            val compactActivation = when (snippet.tapAction) {
                SnippetTapAction.INSERT -> onInsert?.let { insert -> { insert(snippet.id) } }
                SnippetTapAction.SEND_IMMEDIATELY -> onRun?.let { run -> { run(snippet) } }
            }
            val activationDescription = stringResource(
                when {
                    listDetail -> R.string.connections_show_details
                    snippet.tapAction == SnippetTapAction.INSERT ->
                        R.string.connections_insert_into_terminal
                    else -> R.string.connections_run_in_terminal
                },
                snippet.name,
            )
            SnippetCatalogRow(
                snippet = snippet,
                selected = listDetail && selectedId == snippet.id,
                onActivate = if (listDetail) ({ onSelect(snippet.id) }) else compactActivation,
                activationDescription = activationDescription,
                onInsert = onInsert,
                onRun = onRun,
                onCopy = onCopy,
                onEdit = onEdit,
                onDelete = onDelete,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun CatalogCountHeader(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun HostCatalogRow(
    host: HostRowUi,
    selected: Boolean,
    nowEpochMillis: Long,
    onActivate: (() -> Unit)?,
    activationDescription: String,
    onShowDetails: (() -> Unit)?,
    onConnect: ((String) -> Unit)?,
    onOpenSftp: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((HostRowUi) -> Unit)?,
) {
    val favouriteDescription = stringResource(R.string.connections_favourite_host)
    val detailsDescription = stringResource(R.string.connections_show_details, host.displayName)
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(MaterialTheme.motion.standardDurationMillis),
        label = "host-selection",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onActivate == null) Modifier else Modifier.clickable(
                    onClick = onActivate,
                    role = Role.Button,
                ),
            )
            .semantics {
                if (onActivate != null) contentDescription = activationDescription
            }
            .testTag("connections-host-${host.id}"),
        color = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .padding(start = MaterialTheme.spacing.large, end = MaterialTheme.spacing.small, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogIconContainer(glyph = ConnectionsGlyph.HOST, active = host.activeSessionStatus != null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = host.displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (host.isFavourite) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.STAR,
                            modifier = Modifier
                                .padding(start = MaterialTheme.spacing.small)
                                .size(MaterialTheme.iconMetrics.compact)
                                .semantics { contentDescription = favouriteDescription },
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    text = host.secondaryLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProtocolLabel(host.protocol)
                    HostActiveStatusLabel(host)
                    val location = listOfNotNull(
                        host.group,
                        host.tags.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "#$it" },
                    )
                        .joinToString(" · ")
                    if (location.isNotEmpty()) {
                        Text(
                            text = location,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (host.activeSessionStatus == null && host.lastSessionActivityAtEpochMillis != null) {
                    Text(
                        text = formatHostLastUsed(
                            host.lastSessionActivityAtEpochMillis,
                            nowEpochMillis,
                        ).resolve(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            onShowDetails?.let { showDetails ->
                IconButton(
                    onClick = showDetails,
                    modifier = Modifier.semantics {
                        contentDescription = detailsDescription
                    },
                ) {
                    ConnectionsGlyphIcon(
                        glyph = ConnectionsGlyph.INFO,
                        modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
                    )
                }
            }
            HostOverflowMenu(host, onConnect, onOpenSftp, onEdit, onDelete)
        }
    }
}

@Composable
private fun KeyCatalogRow(
    key: KeyRowUi,
    selected: Boolean,
    onActivate: (() -> Unit)?,
    onCopyPublicKey: ((String) -> Unit)?,
    onViewFingerprint: ((String) -> Unit)?,
    onRename: ((String) -> Unit)?,
    onDelete: ((KeyRowUi) -> Unit)?,
) {
    val detailsDescription = stringResource(R.string.connections_show_details, key.name)
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(MaterialTheme.motion.standardDurationMillis),
        label = "key-selection",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onActivate == null) Modifier else Modifier.clickable(
                    onClick = onActivate,
                    role = Role.Button,
                ),
            )
            .semantics {
                if (onActivate != null) contentDescription = detailsDescription
            }
            .testTag("connections-key-${key.id}"),
        color = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .padding(start = MaterialTheme.spacing.large, end = MaterialTheme.spacing.small, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogIconContainer(glyph = ConnectionsGlyph.KEY, active = false)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Text(
                    text = key.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = key.shortFingerprint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetadataLabel(key.algorithm)
                    MetadataLabel(stringResource(key.origin.labelResId))
                    if (key.isPassphraseProtected) {
                        ConnectionsGlyphIcon(
                            ConnectionsGlyph.LOCK,
                            Modifier.size(MaterialTheme.iconMetrics.compact),
                        )
                        Text(
                            text = stringResource(R.string.connections_key_protected),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            KeyOverflowMenu(key, onCopyPublicKey, onViewFingerprint, onRename, onDelete)
        }
    }
}

@Composable
private fun SnippetCatalogRow(
    snippet: SnippetRowUi,
    selected: Boolean,
    onActivate: (() -> Unit)?,
    activationDescription: String,
    onInsert: ((String) -> Unit)?,
    onRun: ((SnippetRowUi) -> Unit)?,
    onCopy: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((SnippetRowUi) -> Unit)?,
) {
    val favouriteDescription = stringResource(R.string.connections_favourite_snippet)
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(MaterialTheme.motion.standardDurationMillis),
        label = "snippet-selection",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onActivate == null) Modifier else Modifier.clickable(
                    onClick = onActivate,
                    role = Role.Button,
                ),
            )
            .semantics {
                if (onActivate != null) contentDescription = activationDescription
            }
            .testTag("connections-snippet-${snippet.id}"),
        color = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp)
                .padding(start = MaterialTheme.spacing.large, end = MaterialTheme.spacing.small, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogIconContainer(glyph = ConnectionsGlyph.SNIPPET, active = false)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = snippet.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (snippet.isFavourite) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.STAR,
                            modifier = Modifier
                                .padding(start = MaterialTheme.spacing.small)
                                .size(MaterialTheme.iconMetrics.compact)
                                .semantics { contentDescription = favouriteDescription },
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    text = safeSnippetPreview(snippet.command, maxCodePoints = 120, maxLines = 2),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = snippet.metadataSummary().resolve(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SnippetOverflowMenu(snippet, onInsert, onRun, onCopy, onEdit, onDelete)
        }
    }
}

@Composable
private fun CatalogIconContainer(glyph: ConnectionsGlyph, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.statusColors.connectedContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.statusColors.onConnectedContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            ConnectionsGlyphIcon(glyph, Modifier.size(MaterialTheme.iconMetrics.standard))
        }
    }
}

@Composable
private fun ProtocolLabel(protocol: ConnectionProtocol) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = protocol.label,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetadataLabel(value: String) {
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HostActiveStatusLabel(host: HostRowUi) {
    val status = host.activeSessionStatus ?: return
    val (container, content) = when (status) {
        HostActiveSessionStatus.CONNECTED -> MaterialTheme.statusColors.connectedContainer to
            MaterialTheme.statusColors.onConnectedContainer
        HostActiveSessionStatus.CONNECTING -> MaterialTheme.statusColors.warningContainer to
            MaterialTheme.statusColors.onWarningContainer
        HostActiveSessionStatus.RECONNECTING -> MaterialTheme.statusColors.reconnectingContainer to
            MaterialTheme.statusColors.onReconnectingContainer
    }
    val label = if (host.activeSessionCount > 1) {
        pluralStringResource(
            R.plurals.connections_active_sessions,
            host.activeSessionCount,
            host.activeSessionCount,
        )
    } else {
        stringResource(status.labelResId)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun HostOverflowMenu(
    host: HostRowUi,
    onConnect: ((String) -> Unit)?,
    onOpenSftp: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((HostRowUi) -> Unit)?,
) {
    val actions = listOfNotNull(
        onConnect?.let { CatalogMenuAction(R.string.connections_action_connect) { it(host.id) } },
        onOpenSftp?.let {
            CatalogMenuAction(R.string.connections_action_open_files) { it(host.id) }
        },
        onEdit?.let { CatalogMenuAction(R.string.connections_action_edit) { it(host.id) } },
        onDelete?.let {
            CatalogMenuAction(R.string.connections_action_delete, destructive = true) { it(host) }
        },
    )
    CatalogOverflowMenu(
        description = stringResource(R.string.connections_host_actions, host.displayName),
        actions = actions,
    )
}

@Composable
private fun KeyOverflowMenu(
    key: KeyRowUi,
    onCopyPublicKey: ((String) -> Unit)?,
    onViewFingerprint: ((String) -> Unit)?,
    onRename: ((String) -> Unit)?,
    onDelete: ((KeyRowUi) -> Unit)?,
) {
    val actions = listOfNotNull(
        onCopyPublicKey?.let {
            CatalogMenuAction(R.string.connections_action_copy_public_key) { it(key.id) }
        },
        onViewFingerprint?.let {
            CatalogMenuAction(R.string.connections_action_view_fingerprint) { it(key.id) }
        },
        onRename?.let { CatalogMenuAction(R.string.connections_action_rename) { it(key.id) } },
        onDelete?.let {
            CatalogMenuAction(R.string.connections_action_delete, destructive = true) { it(key) }
        },
    )
    CatalogOverflowMenu(
        description = stringResource(R.string.connections_key_actions, key.name),
        actions = actions,
    )
}

@Composable
private fun SnippetOverflowMenu(
    snippet: SnippetRowUi,
    onInsert: ((String) -> Unit)?,
    onRun: ((SnippetRowUi) -> Unit)?,
    onCopy: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((SnippetRowUi) -> Unit)?,
) {
    val actions = listOfNotNull(
        onInsert?.let {
            CatalogMenuAction(R.string.connections_action_insert_terminal) { it(snippet.id) }
        },
        onRun?.let { CatalogMenuAction(R.string.connections_action_run_now) { it(snippet) } },
        onCopy?.let {
            CatalogMenuAction(R.string.connections_action_copy_command) { it(snippet.id) }
        },
        onEdit?.let { CatalogMenuAction(R.string.connections_action_edit) { it(snippet.id) } },
        onDelete?.let {
            CatalogMenuAction(R.string.connections_action_delete, destructive = true) { it(snippet) }
        },
    )
    CatalogOverflowMenu(
        description = stringResource(R.string.connections_snippet_actions, snippet.name),
        actions = actions,
    )
}

private data class CatalogMenuAction(
    @StringRes val labelRes: Int,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun CatalogOverflowMenu(description: String, actions: List<CatalogMenuAction>) {
    if (actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            ConnectionsGlyphIcon(
                glyph = ConnectionsGlyph.MORE,
                modifier = Modifier.size(MaterialTheme.iconMetrics.standard),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(action.labelRes),
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.Unspecified
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun HostDetailPane(
    host: HostRowUi?,
    nowEpochMillis: Long,
    onConnect: ((String) -> Unit)?,
    onOpenSftp: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((HostRowUi) -> Unit)?,
) {
    CatalogDetailContainer(selectionPresent = host != null) {
        host ?: return@CatalogDetailContainer
        DetailHeading(
            glyph = ConnectionsGlyph.HOST,
            title = host.displayName,
            favourite = host.isFavourite,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            ProtocolLabel(host.protocol)
            HostActiveStatusLabel(host)
        }
        DetailField(R.string.connections_detail_endpoint, host.secondaryLine, monospace = true)
        host.group?.let { DetailField(R.string.connections_detail_group, it) }
        if (host.tags.isNotEmpty()) {
            DetailField(
                R.string.connections_detail_tags,
                host.tags.joinToString(" · ") { "#$it" },
            )
        }
        DetailField(
            R.string.connections_detail_activity,
            host.lastSessionActivityAtEpochMillis
                ?.let { formatHostLastUsed(it, nowEpochMillis).resolve() }
                ?: stringResource(R.string.connections_no_history),
        )
        DetailActions {
            onConnect?.let { connect ->
                Button(onClick = { connect(host.id) }) {
                    Text(stringResource(R.string.connections_action_connect))
                }
            }
            onOpenSftp?.let { openFiles ->
                OutlinedButton(onClick = { openFiles(host.id) }) {
                    Text(stringResource(R.string.connections_action_open_files))
                }
            }
            onEdit?.let { edit ->
                OutlinedButton(onClick = { edit(host.id) }) {
                    Text(stringResource(R.string.connections_action_edit))
                }
            }
            onDelete?.let { delete ->
                TextButton(onClick = { delete(host) }) {
                    Text(
                        stringResource(R.string.connections_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyDetailPane(
    key: KeyRowUi?,
    onCopyPublicKey: ((String) -> Unit)?,
    onViewFingerprint: ((String) -> Unit)?,
    onRename: ((String) -> Unit)?,
    onDelete: ((KeyRowUi) -> Unit)?,
) {
    CatalogDetailContainer(selectionPresent = key != null) {
        key ?: return@CatalogDetailContainer
        DetailHeading(glyph = ConnectionsGlyph.KEY, title = key.name, favourite = false)
        DetailField(R.string.connections_detail_algorithm, key.algorithm)
        DetailField(
            R.string.connections_detail_fingerprint,
            key.fingerprint,
            monospace = true,
            selectable = true,
        )
        DetailField(
            R.string.connections_detail_source,
            stringResource(key.origin.labelResId),
        )
        DetailField(
            R.string.connections_detail_passphrase,
            stringResource(
                if (key.isPassphraseProtected) {
                    R.string.connections_key_protected
                } else {
                    R.string.connections_key_not_protected
                },
            ),
        )
        key.comment?.let { DetailField(R.string.connections_detail_comment, it) }
        Text(
            text = stringResource(R.string.connections_private_key_honesty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        DetailActions {
            onCopyPublicKey?.let { copy ->
                Button(onClick = { copy(key.id) }) {
                    Text(stringResource(R.string.connections_action_copy_public_key))
                }
            }
            onViewFingerprint?.let { view ->
                OutlinedButton(onClick = { view(key.id) }) {
                    Text(stringResource(R.string.connections_action_view_fingerprint))
                }
            }
            onRename?.let { rename ->
                OutlinedButton(onClick = { rename(key.id) }) {
                    Text(stringResource(R.string.connections_action_rename))
                }
            }
            onDelete?.let { delete ->
                TextButton(onClick = { delete(key) }) {
                    Text(
                        stringResource(R.string.connections_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SnippetDetailPane(
    snippet: SnippetRowUi?,
    onInsert: ((String) -> Unit)?,
    onRun: ((SnippetRowUi) -> Unit)?,
    onCopy: ((String) -> Unit)?,
    onEdit: ((String) -> Unit)?,
    onDelete: ((SnippetRowUi) -> Unit)?,
) {
    CatalogDetailContainer(selectionPresent = snippet != null) {
        snippet ?: return@CatalogDetailContainer
        DetailHeading(
            glyph = ConnectionsGlyph.SNIPPET,
            title = snippet.name,
            favourite = snippet.isFavourite,
        )
        snippet.group?.let { DetailField(R.string.connections_detail_group, it) }
        DetailField(
            R.string.connections_detail_tap_action,
            stringResource(snippet.tapAction.labelResId),
        )
        DetailField(
            R.string.connections_detail_enter_key,
            stringResource(
                if (snippet.appendEnter) {
                    R.string.connections_enter_appended
                } else {
                    R.string.connections_enter_not_appended
                },
            ),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer {
                Text(
                    text = safeSnippetPreview(snippet.command, maxCodePoints = 500, maxLines = 12),
                    modifier = Modifier.padding(MaterialTheme.spacing.large),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (snippet.confirmMultilineExecution && ('\n' in snippet.command || '\r' in snippet.command)) {
            Text(
                text = stringResource(R.string.connections_multiline_confirmation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DetailActions {
            onInsert?.let { insert ->
                Button(onClick = { insert(snippet.id) }) {
                    Text(stringResource(R.string.connections_action_insert))
                }
            }
            onRun?.let { run ->
                OutlinedButton(onClick = { run(snippet) }) {
                    Text(stringResource(R.string.connections_action_run_now))
                }
            }
            onCopy?.let { copy ->
                OutlinedButton(onClick = { copy(snippet.id) }) {
                    Text(stringResource(R.string.connections_action_copy))
                }
            }
            onEdit?.let { edit ->
                OutlinedButton(onClick = { edit(snippet.id) }) {
                    Text(stringResource(R.string.connections_action_edit))
                }
            }
            onDelete?.let { delete ->
                TextButton(onClick = { delete(snippet) }) {
                    Text(
                        stringResource(R.string.connections_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogDetailContainer(
    selectionPresent: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = selectionPresent,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 12 }),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
            ) {
                content()
            }
        }
        if (!selectionPresent) {
            ConnectionsMessageState(
                title = uiText(R.string.connections_select_item),
                detail = uiText(R.string.connections_select_item_detail),
                glyph = ConnectionsGlyph.INFO,
            )
        }
    }
}

@Composable
private fun DetailHeading(glyph: ConnectionsGlyph, title: String, favourite: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CatalogIconContainer(glyph = glyph, active = false)
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.spacing.medium)
                .semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (favourite) {
            ConnectionsGlyphIcon(
                ConnectionsGlyph.STAR,
                Modifier.size(MaterialTheme.iconMetrics.standard),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun DetailField(
    @StringRes labelRes: Int,
    value: String,
    monospace: Boolean = false,
    selectable: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        val valueContent: @Composable () -> Unit = {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            )
        }
        if (selectable) SelectionContainer { valueContent() } else valueContent()
    }
}

@Composable
private fun DetailActions(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ConnectionsCatalogEmptyState(
    tab: ConnectionsTab,
    reason: ConnectionsEmptyState,
    onAdd: (() -> Unit)?,
    onClear: () -> Unit,
) {
    val title: UiText
    val detail: UiText
    val action: UiText?
    val callback: (() -> Unit)?
    if (reason == ConnectionsEmptyState.NO_RESULTS) {
        title = uiText(tab.noMatchingLabelResId)
        detail = uiText(R.string.connections_no_results_detail)
        action = uiText(R.string.connections_clear_search_filters)
        callback = onClear
    } else {
        when (tab) {
            ConnectionsTab.HOSTS -> {
                title = uiText(R.string.connections_no_hosts)
                detail = uiText(R.string.connections_no_hosts_detail)
                action = onAdd?.let { uiText(R.string.connections_add_host) }
            }
            ConnectionsTab.KEYS -> {
                title = uiText(R.string.connections_no_keys)
                detail = uiText(R.string.connections_no_keys_detail)
                action = onAdd?.let { uiText(R.string.connections_add_key) }
            }
            ConnectionsTab.SNIPPETS -> {
                title = uiText(R.string.connections_no_snippets)
                detail = uiText(R.string.connections_no_snippets_detail)
                action = onAdd?.let { uiText(R.string.connections_add_snippet) }
            }
        }
        callback = onAdd
    }
    ConnectionsMessageState(
        title = title,
        detail = detail,
        actionLabel = action,
        onAction = callback,
        modifier = Modifier.testTag(ConnectionsEmptyTestTag),
        glyph = when (tab) {
            ConnectionsTab.HOSTS -> ConnectionsGlyph.HOST
            ConnectionsTab.KEYS -> ConnectionsGlyph.KEY
            ConnectionsTab.SNIPPETS -> ConnectionsGlyph.SNIPPET
        },
    )
}

@Composable
private fun ConnectionsMessageState(
    title: UiText,
    detail: UiText,
    modifier: Modifier = Modifier,
    actionLabel: UiText? = null,
    onAction: (() -> Unit)? = null,
    glyph: ConnectionsGlyph,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.large,
        ) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                ConnectionsGlyphIcon(glyph, Modifier.size(MaterialTheme.iconMetrics.prominent))
            }
        }
        Text(
            text = title.resolve(),
            modifier = Modifier.padding(top = MaterialTheme.spacing.large).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail.resolve(),
            modifier = Modifier.padding(top = MaterialTheme.spacing.small).widthIn(max = 420.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = MaterialTheme.spacing.large),
            ) {
                Text(actionLabel.resolve())
            }
        }
    }
}

internal fun safeSnippetPreview(
    command: String,
    maxCodePoints: Int = 240,
    maxLines: Int = 4,
): String {
    require(maxCodePoints > 0) { "Preview code-point limit must be positive." }
    require(maxLines > 0) { "Preview line limit must be positive." }
    val normalized = command.replace("\r\n", "\n").replace('\r', '\n')
    val preview = StringBuilder()
    var sourceOffset = 0
    var displayedCodePoints = 0
    var displayedLines = 1
    var truncated = false
    while (sourceOffset < normalized.length) {
        val codePoint = normalized.codePointAt(sourceOffset)
        sourceOffset += Character.charCount(codePoint)
        if (codePoint == '\n'.code) {
            if (displayedLines >= maxLines) {
                truncated = true
                break
            }
            preview.append('\n')
            displayedLines += 1
            continue
        }
        if (displayedCodePoints >= maxCodePoints) {
            truncated = true
            break
        }
        when {
            codePoint == '\t'.code -> preview.append("    ")
            Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt() ->
                preview.append('\uFFFD')
            else -> preview.appendCodePoint(codePoint)
        }
        displayedCodePoints += 1
    }
    if (sourceOffset < normalized.length) truncated = true
    if (truncated) preview.append('…')
    return preview.toString()
}

internal fun formatHostLastUsed(lastActivityAtEpochMillis: Long, nowEpochMillis: Long): UiText {
    if (lastActivityAtEpochMillis <= 0L) {
        return uiText(R.string.connections_last_used_unavailable)
    }
    val elapsed = (nowEpochMillis - lastActivityAtEpochMillis).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> uiText(R.string.connections_last_used_now)
        elapsed < 3_600_000L -> {
            val minutes = (elapsed / 60_000L).toInt()
            quantityText(R.plurals.connections_last_used_minutes, minutes)
        }
        elapsed < 86_400_000L -> {
            val hours = (elapsed / 3_600_000L).toInt()
            quantityText(R.plurals.connections_last_used_hours, hours)
        }
        else -> {
            val days = (elapsed / 86_400_000L).toInt()
            quantityText(R.plurals.connections_last_used_days, days)
        }
    }
}

private val ConnectionsTab.labelResId: Int
    get() = when (this) {
        ConnectionsTab.HOSTS -> R.string.connections_tab_hosts
        ConnectionsTab.KEYS -> R.string.connections_tab_keys
        ConnectionsTab.SNIPPETS -> R.string.connections_tab_snippets
    }

private val ConnectionsTab.searchLabelResId: Int
    get() = when (this) {
        ConnectionsTab.HOSTS -> R.string.connections_search_hosts
        ConnectionsTab.KEYS -> R.string.connections_search_keys
        ConnectionsTab.SNIPPETS -> R.string.connections_search_snippets
    }

private val ConnectionsTab.noMatchingLabelResId: Int
    get() = when (this) {
        ConnectionsTab.HOSTS -> R.string.connections_no_matching_hosts
        ConnectionsTab.KEYS -> R.string.connections_no_matching_keys
        ConnectionsTab.SNIPPETS -> R.string.connections_no_matching_snippets
    }

private val HostSort.labelResId: Int
    get() = when (this) {
        HostSort.NAME -> R.string.connections_sort_name
        HostSort.FAVOURITES -> R.string.connections_sort_favourites
        HostSort.RECENT -> R.string.connections_sort_recent
        HostSort.GROUP -> R.string.connections_sort_group
    }

private val HostFilters.isActive: Boolean
    get() = favouritesOnly || group != null

private val ConnectionProtocol.label: String
    get() = when (this) {
        ConnectionProtocol.SSH -> "SSH"
        ConnectionProtocol.MOSH -> "MOSH"
    }

private val HostActiveSessionStatus.labelResId: Int
    get() = when (this) {
        HostActiveSessionStatus.CONNECTING -> R.string.connections_status_connecting
        HostActiveSessionStatus.CONNECTED -> R.string.connections_status_connected
        HostActiveSessionStatus.RECONNECTING -> R.string.connections_status_reconnecting
    }

private val SshKeyOrigin.labelResId: Int
    get() = when (this) {
        SshKeyOrigin.IMPORTED -> R.string.connections_key_origin_imported
        SshKeyOrigin.GENERATED -> R.string.connections_key_origin_generated
    }

private val SnippetTapAction.labelResId: Int
    get() = when (this) {
        SnippetTapAction.INSERT -> R.string.connections_snippet_insert_terminal
        SnippetTapAction.SEND_IMMEDIATELY -> R.string.connections_snippet_run_immediately
    }

private val KeyRowUi.shortFingerprint: String
    get() = if (fingerprint.length <= 36) fingerprint else "${fingerprint.take(20)}…${fingerprint.takeLast(12)}"

private fun SnippetRowUi.metadataSummary(): UiText = UiText.Joined(
    buildList {
        group?.let { add(UiText.Dynamic(it)) }
        add(uiText(tapAction.labelResId))
        if (appendEnter) add(uiText(R.string.connections_snippet_enter_appended))
        if (confirmMultilineExecution && ('\n' in command || '\r' in command)) {
            add(uiText(R.string.connections_snippet_confirms_multiline))
        }
    },
)

internal const val ConnectionsHostFiltersTestTag = "connections-host-filters"
