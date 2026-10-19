package com.yanjiyu.terminalspike.ui.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import kotlinx.coroutines.launch

/** Each project retains its own file, scroll position, tree expansion, and review drafts. */
@Composable
internal fun ProjectScreen(
    controller: ProjectController,
    onPasteToTerminal: (Long, String) -> Boolean,
    onReturnToTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Capture immutable values before the provider lambda: an old provider must never observe
    // another tab's live state before its saved scroll state has been collected.
    val state = controller.state.collectAsStateWithLifecycle().value
    val tabs = controller.tabs.collectAsStateWithLifecycle().value
    val saved = rememberSaveableStateHolder()
    MaterialTheme(colorScheme = ProjectReaderColors) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { tab ->
                            val root = tab.snapshot?.root
                            val basename = root?.substringAfterLast('/')
                            val duplicate = basename != null && tabs.count { it.snapshot?.root?.substringAfterLast('/') == basename } > 1
                            val label = if (duplicate) root.split('/').takeLast(2).joinToString("/")
                                else basename ?: stringResource(R.string.project_open)
                            val closeLabel = stringResource(R.string.project_close)
                            val activeColor = MaterialTheme.colorScheme.primary
                            Row(Modifier.drawWithContent {
                                drawContent()
                                if (tab.tabId == state.tabId) drawLine(activeColor, Offset(0f, size.height),
                                    Offset(size.width, size.height), 2.dp.toPx())
                            }.background(if (tab.tabId == state.tabId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .heightIn(min = 40.dp).semantics { selected = tab.tabId == state.tabId }.clickable { controller.selectTab(tab.tabId) }.testTag("project-tab-${tab.tabId}"), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(min = 64.dp, max = 200.dp)
                                        .padding(horizontal = 10.dp, vertical = 7.dp))
                                Text("×", fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(40.dp).height(40.dp)
                                        .clickable { controller.closeTab(tab.tabId); saved.removeState(tab.tabId) }
                                        .semantics { contentDescription = closeLabel + " " + (root ?: tab.title) }
                                        .padding(vertical = 7.dp))
                            }
                        }
                        ProjectAction(onClick = controller::newProject, modifier = Modifier.testTag("project-add-tab")) {
                            Text("+", fontSize = 18.sp)
                        }
                    }
                    HorizontalDivider()
                    key(state.tabId) {
                        saved.SaveableStateProvider(state.tabId) {
                            ProjectWorkspace(state, controller, onPasteToTerminal, onReturnToTerminal, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Source reading has its own lazy UI; it never touches the terminal renderer or viewport. */
@Composable
private fun ProjectWorkspace(
    state: ProjectUiState,
    controller: ProjectController,
    onPasteToTerminal: (Long, String) -> Boolean,
    onReturnToTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var directoryDialog by rememberSaveable { mutableStateOf(false) }
    var reviewDialog by rememberSaveable { mutableStateOf(false) }
    var changesOnly by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable(state.snapshot?.root) { mutableStateOf("") }
    var expandedFiles by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsedChanges by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val filesListState = rememberSaveable(state.snapshot?.root, saver = LazyListState.Saver) { LazyListState() }
    var lastFilter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(changesOnly, search) {
        val filter = "$changesOnly\u0000$search"
        if (lastFilter != filter) { filesListState.scrollToItem(0); lastFilter = filter }
    }
    BackHandler(state.document != null && !directoryDialog && !reviewDialog) { controller.closeFile() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state.snapshot != null && !state.busy) controller.refresh()
    }
    PullToRefreshBox(isRefreshing = state.busy, onRefresh = controller::refresh,
        modifier = modifier.fillMaxSize().testTag("project-pull-refresh")) {
        Column(Modifier.fillMaxSize().testTag("project-screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.snapshot?.let { "${state.title} · ${it.branch}" } ?: state.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 3.dp))
                ProjectAction(onClick = { directoryDialog = true }, enabled = !state.busy) { Text(stringResource(R.string.project_directory)) }
                ProjectAction(onClick = controller::refresh, enabled = !state.busy) { Text(stringResource(R.string.project_refresh)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (state.snapshot == null) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.project_choose_detail), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.locations, key = { it.path }) { location ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { controller.load(location.path) }
                                .padding(vertical = 6.dp)) {
                                Text(location.label + if (location.current) " · " + stringResource(R.string.project_current_pane) else "",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                Text(location.path, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    ProjectAction(onClick = { directoryDialog = true }, enabled = !state.busy) { Text(stringResource(R.string.project_enter_path)) }
                }
            } else {
                val snapshot = requireNotNull(state.snapshot)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ProjectAction(onClick = { changesOnly = true }, modifier = Modifier.testTag("project-changes-tab")) {
                        Text(stringResource(R.string.project_changes_count, snapshot.changes.size),
                            color = if (changesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProjectAction(onClick = { changesOnly = false }, modifier = Modifier.testTag("project-files-tab")) {
                        Text(stringResource(R.string.project_all_files),
                            color = if (!changesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f))
                    ProjectAction(onClick = { reviewDialog = true }) { Text(stringResource(R.string.project_feedback_count, state.feedback.size)) }
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 600.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide || state.document == null) {
                            Column((if (wide) Modifier.width(260.dp) else Modifier.fillMaxSize())
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Text(if (changesOnly) stringResource(R.string.project_changes_scope) else snapshot.root,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                                val searchLabel = stringResource(R.string.project_filter_files)
                                BasicTextField(search, { search = it }, singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                                        .semantics { contentDescription = searchLabel }.testTag("project-file-search")
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    decorationBox = { input -> Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ConnectionsGlyphIcon(ConnectionsGlyph.SEARCH, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                        Box(Modifier.weight(1f)) { if (search.isEmpty()) Text(searchLabel, fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant); input() }
                                    } })
                                ProjectFiles(snapshot, changesOnly, search, expandedFiles, collapsedChanges, { path ->
                                    if (changesOnly) collapsedChanges = if (path in collapsedChanges) collapsedChanges - path else collapsedChanges + path
                                    else expandedFiles = if (path in expandedFiles) expandedFiles - path else expandedFiles + path
                                }, filesListState, state.document?.path, state.busy, { path ->
                                    focus.clearFocus(); keyboard?.hide(); controller.openFile(path)
                                }, Modifier.weight(1f))
                            }
                        }
                        if (wide && state.document != null) VerticalDivider()
                        if (wide && state.document == null) {
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.project_select_file),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        state.document?.let { document ->
                            ProjectCode(document, state.feedback, controller::closeFile, controller::addFeedback,
                                Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
            }
        }
    }
    if (directoryDialog) {
        var directory by remember { mutableStateOf(state.directory) }
        AlertDialog(onDismissRequest = { directoryDialog = false },
            title = { Text(stringResource(R.string.project_open)) },
            text = { Column {
                Text(stringResource(R.string.project_path_detail))
                OutlinedTextField(directory, { directory = it }, label = { Text(stringResource(R.string.project_path)) },
                    singleLine = true, modifier = Modifier.testTag("project-path-input"))
            } },
            confirmButton = { Button(onClick = { directoryDialog = false; controller.load(directory) },
                enabled = directory.startsWith('/') && directory.isNotBlank()) { Text(stringResource(R.string.project_open)) } },
            dismissButton = { TextButton(onClick = { directoryDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (reviewDialog) {
        ProjectReview(state, controller::removeFeedback, controller::editFeedback, onDismiss = { reviewDialog = false },
            onPaste = { text ->
                val id = state.sessionId
                if (id != null && onPasteToTerminal(id, text)) {
                    reviewDialog = false
                    onReturnToTerminal()
                    true
                } else false
            })
    }
}

@Composable
private fun ProjectFiles(snapshot: ProjectSnapshot, changesOnly: Boolean, search: String,
    expanded: List<String>, collapsed: List<String>, onToggle: (String) -> Unit,
    listState: LazyListState, selected: String?, busy: Boolean, onOpen: (String) -> Unit, modifier: Modifier) {
    val changes = remember(snapshot) { snapshot.changes.associateBy { it.path } }
    val entries = remember(snapshot, changesOnly, search, expanded, collapsed) {
        val paths = if (changesOnly) snapshot.changes.map { it.path } else snapshot.files
        projectTreeEntries(paths.filter { it.contains(search, ignoreCase = true) },
            expanded.toSet(), if (search.isEmpty()) collapsed.toSet() else emptySet(), changesOnly || search.isNotEmpty())
    }
    if (entries.isEmpty()) Text(stringResource(if (changesOnly && search.isEmpty()) R.string.project_no_changes else R.string.project_no_files),
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), fontSize = 12.sp)
    else LazyColumn(modifier.testTag("project-file-list"), state = listState) {
        items(entries, key = { (if (it.directory) "d:" else "f:") + it.path }) { entry ->
            val change = changes[entry.path]
            val isSelected = selected == entry.path && !entry.directory
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics { this.selected = isSelected }
                .testTag((if (entry.directory) "project-folder:" else "project-file:") + entry.path)
                .clickable(enabled = !busy) { if (entry.directory) onToggle(entry.path) else onOpen(entry.path) }
                .padding(start = (8 + entry.depth * 16).dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (entry.directory) if (entry.expanded) "⌄" else "›" else "",
                    fontSize = 16.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
                ProjectEntryIcon(entry.directory, entry.expanded, Modifier.size(18.dp))
                Text(entry.label, fontSize = 14.sp, lineHeight = 20.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (entry.directory) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                change?.status?.trim()?.takeIf { it.isNotEmpty() }?.let { status ->
                    Text(status, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = when {
                            'D' in status -> MaterialTheme.colorScheme.error
                            'A' in status || '?' in status -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        })
                }
            }
        }
    }
}

@Composable
private fun ProjectCode(document: ProjectDocument, feedback: List<ProjectFeedback>, onBack: () -> Unit,
    onComment: (Int, Int, String) -> Unit, modifier: Modifier) {
    var diffOnly by rememberSaveable(document.path) { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    val density = LocalDensity.current
    val longestLine = remember(document) { document.lines.maxOfOrNull { it.text.length } ?: 0 }
    // Leave room for wide glyphs and font scaling; never create unrepresentable Compose constraints.
    val unwrappedWidth = with(density) { 16.sp.toDp() * longestLine } + 74.dp
    val canUnwrap = with(density) { unwrappedWidth.toPx() <= 60_000f }
    val effectiveWrap = wrap || !canUnwrap
    var selection by remember(document.revision) { mutableStateOf<IntRange?>(null) }
    var commentDialog by remember(document.revision) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberSaveable(document.path, document.revision, diffOnly, saver = LazyListState.Saver) { LazyListState() }
    val rows = remember(document, diffOnly) {
        if (!diffOnly) document.lines.indices.toList() else {
            val visible = BooleanArray(document.lines.size)
            document.lines.forEachIndexed { i, line ->
                if (line.kind != ProjectLineKind.CONTEXT) {
                    for (index in maxOf(0, i - 3)..minOf(document.lines.lastIndex, i + 3)) visible[index] = true
                }
            }
            document.lines.indices.filter { visible[it] }
        }
    }
    var positioned by rememberSaveable(document.path, document.revision, diffOnly) { mutableStateOf(false) }
    LaunchedEffect(document.path, document.revision, diffOnly) {
        if (!positioned) {
            val firstChange = document.changeStarts.firstOrNull()
            val index = if (firstChange == null) 0 else rows.indexOf(firstChange).coerceAtLeast(0)
            if (rows.isNotEmpty()) listState.scrollToItem(index)
            positioned = true
        }
    }
    Column(modifier.testTag("project-code")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProjectAction(onClick = onBack) { Text(stringResource(R.string.project_file_list)) }
            Text(document.path.substringAfterLast('/') + document.path.substringBeforeLast('/', "").let {
                if (it.isEmpty()) "" else " · $it"
            }, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProjectAction(onClick = { diffOnly = !diffOnly; selection = null }) {
                Text(stringResource(if (diffOnly) R.string.project_diff else R.string.project_full_file))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("+${document.additions} −${document.deletions}", modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelMedium)
            ProjectAction(onClick = {
                val current = rows.getOrNull(listState.firstVisibleItemIndex) ?: 0
                val target = document.changeStarts.lastOrNull { it < current } ?: document.changeStarts.lastOrNull()
                target?.let { scope.launch { listState.animateScrollToItem(rows.indexOf(it).coerceAtLeast(0)) } }
            }, enabled = document.changeStarts.isNotEmpty()) { Text(stringResource(R.string.project_previous)) }
            ProjectAction(onClick = {
                val current = rows.getOrNull(listState.firstVisibleItemIndex) ?: -1
                val target = document.changeStarts.firstOrNull { it > current } ?: document.changeStarts.firstOrNull()
                target?.let { scope.launch { listState.animateScrollToItem(rows.indexOf(it).coerceAtLeast(0)) } }
            }, enabled = document.changeStarts.isNotEmpty()) { Text(stringResource(R.string.project_next)) }
            Checkbox(effectiveWrap, { wrap = it }, enabled = canUnwrap, modifier = Modifier.testTag("project-wrap-toggle"))
            Text(stringResource(R.string.project_wrap), style = MaterialTheme.typography.labelSmall)
        }
        selection?.let { range ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectAction(onClick = { selection = null }) { Text(stringResource(R.string.cancel)) }
                ProjectAction(onClick = { commentDialog = true }) {
                    Text(stringResource(R.string.project_comment_range, document.lines[range.first].number, document.lines[range.last].number))
                }
            }
        }
        document.metadata.forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp)) }
        HorizontalDivider()
        if (rows.isEmpty()) Text(stringResource(if (diffOnly) R.string.project_no_changes else R.string.project_empty_file), Modifier.padding(16.dp))
        val scrollX = rememberScrollState()
        BoxWithConstraints(Modifier.weight(1f)) {
            val codeWidth = if (effectiveWrap) maxWidth else maxOf(maxWidth, unwrappedWidth)
            Box(if (effectiveWrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(scrollX)) {
                LazyColumn(Modifier.width(codeWidth).fillMaxSize().testTag("project-code-list"), state = listState) {
                    itemsIndexed(rows, key = { _, index -> index }) { displayIndex, index ->
                        val line = document.lines[index]
                        if (diffOnly && displayIndex > 0 && rows[displayIndex - 1] != index - 1) {
                            Text("⋯", modifier = Modifier.padding(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val selected = selection?.contains(index) == true
                        val marked = feedback.any { it.path == document.path && it.revision == document.revision &&
                            it.deleted == (line.kind == ProjectLineKind.DELETED) && line.number in it.firstLine..it.lastLine }
                        val background = when {
                            selected -> MaterialTheme.colorScheme.secondaryContainer
                            line.kind == ProjectLineKind.ADDED -> Color(0xFF173E32)
                            line.kind == ProjectLineKind.DELETED -> Color(0xFF4A2834)
                            else -> Color.Transparent
                        }
                        Row(Modifier.fillMaxWidth().background(background), verticalAlignment = Alignment.Top) {
                            Text((if (marked) "●" else if (line.kind == ProjectLineKind.ADDED) "+" else if (line.kind == ProjectLineKind.DELETED) "−" else " ") + line.number,
                                fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(52.dp).heightIn(min = 18.dp)
                                    .combinedClickable(onClick = {
                                        val anchor = selection?.first
                                        if (anchor != null && document.lines.subList(minOf(anchor, index), maxOf(anchor, index) + 1)
                                                .all { (it.kind == ProjectLineKind.DELETED) == (line.kind == ProjectLineKind.DELETED) }) {
                                            selection = minOf(anchor, index)..maxOf(anchor, index)
                                        } else selection = index..index
                                    }, onLongClick = { selection = index..index })
                                    .padding(horizontal = 4.dp))
                            val syntax = remember(line.text, document.path) { projectSyntax(line.text, document.path) }
                            val colors = MaterialTheme.colorScheme
                            val highlighted = remember(line.text, syntax, colors) {
                                AnnotatedString.Builder(line.text.ifEmpty { " " }).apply {
                                    syntax.forEach { token ->
                                        addStyle(SpanStyle(color = when (token.kind) {
                                            CodeTokenKind.KEYWORD -> Color(0xFFC4A7FF)
                                            CodeTokenKind.STRING -> Color(0xFFA9DF87)
                                            CodeTokenKind.COMMENT -> colors.onSurfaceVariant
                                            CodeTokenKind.NUMBER -> Color(0xFFFFC580)
                                        }), token.start, token.end)
                                    }
                                }.toAnnotatedString()
                            }
                            SelectionContainer(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text(highlighted, color = colors.onSurface, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, softWrap = effectiveWrap)
                            }
                        }
                    }
                }
            }
        }
        if (feedback.any { it.path == document.path && it.revision != document.revision }) {
            Text(stringResource(R.string.project_feedback_outdated), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(stringResource(R.string.project_select_hint), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
    }
    if (commentDialog) {
        val range = selection ?: return
        var message by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { commentDialog = false }, title = { Text(stringResource(R.string.project_add_feedback)) },
            text = { Column {
                Text("${document.path}:${document.lines[range.first].number}–${document.lines[range.last].number}")
                OutlinedTextField(message, { if (it.length <= 4_000) message = it }, minLines = 3,
                    modifier = Modifier.testTag("project-feedback-input"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = {
                runCatching { onComment(range.first, range.last, message) }
                    .onSuccess { commentDialog = false; selection = null }.onFailure { error = it.message }
            }, enabled = message.isNotBlank()) { Text(stringResource(R.string.project_save_draft)) } },
            dismissButton = { TextButton(onClick = { commentDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ProjectReview(state: ProjectUiState, onRemove: (String) -> Unit, onEdit: (String, String) -> Unit,
    onDismiss: () -> Unit, onPaste: (String) -> Boolean) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProjectFeedback?>(null) }
    var error by remember { mutableStateOf(false) }
    val text = remember(state.feedback, state.snapshot?.root) { projectFeedbackText(state.snapshot?.root.orEmpty(), state.feedback) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_feedback_count, state.feedback.size)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.project_feedback_target, state.title))
            Text(stringResource(R.string.project_feedback_draft_detail), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { preview = !preview }) { Text(stringResource(if (preview) R.string.project_comments else R.string.project_preview)) }
            if (preview) SelectionContainer { Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            else state.feedback.forEach { item ->
                Text("${item.path}:${item.firstLine}–${item.lastLine}", style = MaterialTheme.typography.labelMedium)
                if (state.document?.path == item.path && state.document.revision != item.revision) {
                    Text(stringResource(R.string.project_feedback_outdated), style = MaterialTheme.typography.labelSmall)
                }
                Text(item.message)
                Row {
                    TextButton(onClick = { editing = item }) { Text(stringResource(R.string.project_edit)) }
                    TextButton(onClick = { onRemove(item.id) }) { Text(stringResource(R.string.project_remove)) }
                }
                HorizontalDivider()
            }
            if (state.feedback.isEmpty()) Text(stringResource(R.string.project_no_feedback))
            if (error) Text(stringResource(R.string.project_paste_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button(onClick = { error = !onPaste(text) }, enabled = state.feedback.isNotEmpty()) {
            Text(stringResource(R.string.project_paste_terminal))
        } },
        dismissButton = { Row {
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Code review", text))
            }, enabled = state.feedback.isNotEmpty()) { Text(stringResource(R.string.project_copy)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        } })
    editing?.let { item ->
        var message by remember(item.id) { mutableStateOf(item.message) }
        var editError by remember(item.id) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.project_edit)) },
            text = { Column {
                OutlinedTextField(message, { if (it.length <= 4_000) message = it }, minLines = 3)
                editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = {
                runCatching { onEdit(item.id, message) }.onSuccess { editing = null }.onFailure { editError = it.message }
            }, enabled = message.isNotBlank()) {
                Text(stringResource(R.string.project_save_draft))
            } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ProjectAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), content = content)
}

/** Fixed contrast for source review, independent of the terminal's user-selected ANSI palette. */
private val ProjectReaderColors = darkColorScheme(
    primary = Color(0xFF82CFFF), onPrimary = Color(0xFF082A40),
    secondary = Color(0xFFFFC580), tertiary = Color(0xFFA9DF87),
    background = Color(0xFF18212D), onBackground = Color(0xFFF0F4FA),
    surface = Color(0xFF18212D), onSurface = Color(0xFFF0F4FA),
    onSurfaceVariant = Color(0xFFB7C6D9),
    surfaceContainerLow = Color(0xFF1D2938), surfaceContainerHigh = Color(0xFF2B3A4D),
    secondaryContainer = Color(0xFF294E70), onSecondaryContainer = Color(0xFFFFFFFF),
    outline = Color(0xFF8296AE), outlineVariant = Color(0xFF46586D),
    error = Color(0xFFFFA5AB),
)
