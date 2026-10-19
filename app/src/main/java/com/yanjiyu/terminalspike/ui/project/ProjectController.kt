package com.yanjiyu.terminalspike.ui.project

import android.content.Context
import com.yanjiyu.terminalspike.connection.TmuxCommandRunner
import com.yanjiyu.terminalspike.connection.RemoteProjectLocation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ProjectFeedback(
    val id: String,
    val path: String,
    val firstLine: Int,
    val lastLine: Int,
    val deleted: Boolean,
    val code: String,
    val context: String,
    val revision: String,
    val message: String,
)

internal data class ProjectUiState(
    val sessionId: Long? = null,
    val title: String = "",
    val endpointKey: String = "",
    val directory: String = "",
    val snapshot: ProjectSnapshot? = null,
    val document: ProjectDocument? = null,
    val feedback: List<ProjectFeedback> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val tabId: Long = 0,
    val locations: List<RemoteProjectLocation> = emptyList(),
)

internal interface ProjectDraftStore {
    fun directory(endpoint: String): String
    fun directories(endpoint: String): List<String> = listOf(directory(endpoint)).filter(String::isNotBlank)
    fun rememberDirectory(endpoint: String, directory: String)
    fun load(endpoint: String, root: String): List<ProjectFeedback>
    fun save(endpoint: String, root: String, feedback: List<ProjectFeedback>)
}

internal class AndroidProjectDraftStore(context: Context) : ProjectDraftStore {
    private val preferences = context.getSharedPreferences("project-review", Context.MODE_PRIVATE)
    private fun key(endpoint: String, root: String) = projectRevision(endpoint + "\u0000" + root)
    override fun directory(endpoint: String): String = preferences.getString("directory-${key(endpoint, "")}", "") ?: ""
    override fun directories(endpoint: String): List<String> = runCatching {
        val stored = preferences.getString("recent-${key(endpoint, "")}", null) ?: return super.directories(endpoint)
        (Json.parseToJsonElement(stored) as JsonArray).map { (it as JsonPrimitive).content }
    }.getOrElse { super.directories(endpoint) }
    override fun rememberDirectory(endpoint: String, directory: String) {
        val recent = (listOf(directory) + directories(endpoint)).distinct().take(20)
        preferences.edit().putString("directory-${key(endpoint, "")}", directory)
            .putString("recent-${key(endpoint, "")}", JsonArray(recent.map(::JsonPrimitive)).toString()).apply()
    }
    override fun load(endpoint: String, root: String): List<ProjectFeedback> = runCatching {
        val stored = preferences.getString(key(endpoint, root), null) ?: return emptyList()
        (Json.parseToJsonElement(stored) as JsonArray).map { element ->
            val value = element as JsonObject
            fun text(name: String) = (value.getValue(name) as JsonPrimitive).content
            ProjectFeedback(text("id"), text("path"), text("first").toInt(), text("last").toInt(),
                text("deleted").toBooleanStrict(), text("code"), text("context"), text("revision"), text("message"))
        }.take(MAX_PROJECT_FEEDBACK)
    }.getOrDefault(emptyList())

    override fun save(endpoint: String, root: String, feedback: List<ProjectFeedback>) {
        val json = JsonArray(feedback.map { item ->
            buildJsonObject {
                put("id", item.id); put("path", item.path); put("first", item.firstLine); put("last", item.lastLine)
                put("deleted", item.deleted); put("code", item.code); put("context", item.context)
                put("revision", item.revision); put("message", item.message)
            }
        })
        preferences.edit().putString(key(endpoint, root), json.toString()).apply()
    }
}

/** UI-thread owner. Cancelled reads cannot publish stale results; visible documents change only on request. */
internal class ProjectController(
    private val scope: CoroutineScope,
    private val readerFor: (Long) -> TmuxCommandRunner,
    private val store: ProjectDraftStore,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val locationsFor: (Long) -> List<RemoteProjectLocation> = { emptyList() },
    private val ensureConnected: suspend (Long) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ProjectUiState())
    val state = mutableState.asStateFlow()
    private val mutableTabs = MutableStateFlow<List<ProjectUiState>>(emptyList())
    val tabs = mutableTabs.asStateFlow()
    private var nextTabId = 1L
    private var operation: Job? = null
    private var generation = 0L

    fun open(sessionId: Long, title: String, endpointKey: String, autoSelect: Boolean = true) {
        cancelRead()
        if (mutableState.value.busy) publish(mutableState.value.copy(busy = false))
        val pending = mutableTabs.value.firstOrNull {
            it.sessionId == sessionId && it.endpointKey == endpointKey && it.snapshot == null
        } ?: ProjectUiState(sessionId = sessionId, title = title, endpointKey = endpointKey,
            directory = store.directory(endpointKey), tabId = nextTabId++)
        publish(pending.copy(error = null))
        discover(autoSelect)
    }

    fun newProject() {
        val current = mutableState.value
        val session = current.sessionId ?: return
        open(session, current.title, current.endpointKey, autoSelect = false)
    }

    fun discover(autoSelect: Boolean = false) {
        val before = mutableState.value
        val session = before.sessionId ?: return
        cancelRead()
        val token = generation
        publish(before.copy(busy = true, error = null))
        operation = scope.launch {
            try {
                ensureConnected(session)
                val reported = withContext(ioContext) { locationsFor(session) }
                if (generation != token) return@launch
                val recent = store.directories(before.endpointKey).map { RemoteProjectLocation(it, "Recent project") }
                val locations = (reported + recent).distinctBy { it.path }
                publish(mutableState.value.copy(locations = locations, busy = false))
                val current = reported.firstOrNull { it.current }
                if (autoSelect && current != null) load(current.path)
                else if (autoSelect && reported.isEmpty() && recent.size == 1) load(recent.single().path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == token) publish(mutableState.value.copy(busy = false, error = error.message))
            }
        }
    }

    fun selectTab(tabId: Long) {
        if (mutableState.value.tabId == tabId) return
        val target = mutableTabs.value.firstOrNull { it.tabId == tabId } ?: return
        cancelRead()
        if (mutableState.value.busy) publish(mutableState.value.copy(busy = false))
        publish(target.copy(busy = false))
    }

    fun closeTab(tabId: Long) {
        val index = mutableTabs.value.indexOfFirst { it.tabId == tabId }
        if (index < 0) return
        val wasActive = mutableState.value.tabId == tabId
        if (wasActive) cancelRead()
        val remaining = mutableTabs.value.filterNot { it.tabId == tabId }
        mutableTabs.value = remaining
        if (wasActive) {
            val target = remaining.getOrNull((index - 1).coerceAtLeast(0))
            mutableState.value = target?.copy(busy = false) ?: ProjectUiState()
        }
    }

    private fun publish(value: ProjectUiState) {
        mutableState.value = value
        if (value.sessionId != null) {
            val existing = mutableTabs.value
            mutableTabs.value = if (existing.any { it.tabId == value.tabId }) {
                existing.map { if (it.tabId == value.tabId) value else it }
            } else existing + value
        }
    }

    fun load(directory: String, refreshOpenFile: Boolean = false) {
        val before = mutableState.value
        val sessionId = before.sessionId ?: return
        cancelRead()
        val token = generation
        publish(before.copy(busy = true, error = null))
        operation = scope.launch {
            try {
                ensureConnected(sessionId)
                val (snapshot, document) = withContext(ioContext) {
                    val reader = ProjectReader(readerFor(sessionId))
                    val snapshot = reader.snapshot(directory)
                    val path = before.document?.path
                    val document = if (refreshOpenFile && snapshot.root == before.snapshot?.root &&
                        path != null && path in snapshot.files) reader.document(snapshot, path) else null
                    snapshot to document
                }
                if (generation != token) return@launch
                store.rememberDirectory(before.endpointKey, snapshot.root)
                val existing = mutableTabs.value.firstOrNull {
                    it.tabId != before.tabId && it.endpointKey == before.endpointKey && it.snapshot?.root == snapshot.root
                }
                if (existing != null) {
                    if (before.snapshot == null) mutableTabs.value = mutableTabs.value.filterNot { it.tabId == before.tabId }
                    else publish(before.copy(busy = false))
                    publish(existing.copy(sessionId = before.sessionId, title = before.title, busy = false))
                } else {
                    val changingRoot = before.snapshot != null && before.snapshot.root != snapshot.root
                    if (changingRoot) publish(before.copy(busy = false))
                    publish(before.copy(directory = snapshot.root, snapshot = snapshot,
                        tabId = if (changingRoot) nextTabId++ else before.tabId,
                        document = document, feedback = store.load(before.endpointKey, snapshot.root), busy = false, error = null))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == token) publish(mutableState.value.copy(busy = false, error = error.message))
            }
        }
    }

    fun refresh() {
        if (mutableState.value.busy) return
        val directory = mutableState.value.snapshot?.root ?: mutableState.value.directory
        if (directory.isBlank()) discover(autoSelect = true) else load(directory, refreshOpenFile = true)
    }

    fun openFile(path: String) {
        val before = mutableState.value
        val snapshot = before.snapshot ?: return
        val sessionId = before.sessionId ?: return
        cancelRead()
        val token = generation
        publish(before.copy(busy = true, error = null))
        operation = scope.launch {
            try {
                ensureConnected(sessionId)
                val document = withContext(ioContext) {
                    ProjectReader(readerFor(sessionId)).document(snapshot, path)
                }
                if (generation == token) publish(mutableState.value.copy(document = document, busy = false))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == token) publish(mutableState.value.copy(busy = false, error = error.message))
            }
        }
    }

    fun closeFile() {
        cancelRead()
        publish(mutableState.value.copy(document = null, busy = false, error = null))
    }

    fun close() {
        cancelRead()
        mutableTabs.value = emptyList()
        mutableState.value = ProjectUiState()
    }

    fun addFeedback(firstIndex: Int, lastIndex: Int, message: String) {
        val before = mutableState.value
        val document = before.document ?: return
        val first = minOf(firstIndex, lastIndex)
        val last = maxOf(firstIndex, lastIndex)
        require(first in document.lines.indices && last in document.lines.indices)
        require(message.isNotBlank() && message.length <= 4_000)
        require(before.feedback.size < MAX_PROJECT_FEEDBACK) { "This review already has $MAX_PROJECT_FEEDBACK comments." }
        val selected = document.lines.subList(first, last + 1)
        require(selected.all { (it.kind == ProjectLineKind.DELETED) == (selected.first().kind == ProjectLineKind.DELETED) }) {
            "Select current code or deleted code separately."
        }
        val code = selected.joinToString("\n") { it.text }
        require(code.length <= 16_000) { "Select a smaller code range." }
        val context = document.lines.subList(maxOf(0, first - 2), minOf(document.lines.size, last + 3))
            .joinToString("\n") { it.text }.take(20_000)
        val item = ProjectFeedback(UUID.randomUUID().toString(), document.path, selected.first().number,
            selected.last().number, selected.first().kind == ProjectLineKind.DELETED,
            code, context, document.revision, message.trim())
        updateFeedback(before.feedback + item)
    }

    fun editFeedback(id: String, message: String) {
        require(message.isNotBlank() && message.length <= 4_000)
        updateFeedback(mutableState.value.feedback.map { if (it.id == id) it.copy(message = message.trim()) else it })
    }

    fun removeFeedback(id: String) { updateFeedback(mutableState.value.feedback.filterNot { it.id == id }) }

    private fun updateFeedback(feedback: List<ProjectFeedback>) {
        val before = mutableState.value
        val root = before.snapshot?.root ?: return
        require(feedback.sumOf { it.code.length + it.context.length + it.message.length + it.path.length + 200 } <= 120_000) {
            "This review is too large. Copy or finish the current review before adding more feedback."
        }
        store.save(before.endpointKey, root, feedback)
        publish(before.copy(feedback = feedback))
    }

    private fun cancelRead() { generation++; operation?.cancel(); operation = null }
}

internal fun projectFeedbackText(root: String, feedback: List<ProjectFeedback>): String {
    val review = buildString {
        append("Please address this code review for repository: ").append(root)
        append("\nThe line numbers refer to reviewed snapshots. Locate the quoted code in the current files before editing.\n")
        feedback.forEachIndexed { index, item ->
            append("\nFeedback ").append(index + 1).append(": ").append(item.path)
            append(':').append(item.firstLine).append('-').append(item.lastLine)
            if (item.deleted) append(" (deleted code from the base version)")
            append("\nReviewed snapshot: ").append(item.revision.take(12))
            append("\nSelected code:\n").append(item.code)
            append("\nNearby context:\n").append(item.context)
            append("\nMy feedback:\n").append(item.message).append('\n')
        }
        append("\nHandle this feedback as one review, run the relevant checks, and report each result.")
    }
    return buildString(review.length) {
        review.forEach { char -> append(if (char.isISOControl() && char != '\n' && char != '\t') ' ' else char) }
    }
}

internal const val MAX_PROJECT_FEEDBACK = 100
