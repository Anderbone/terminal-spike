package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Bounded task metadata and optional excerpts from the explicitly enabled local helper. */
data class HerdrAgentState(
    val paneId: String,
    val terminalId: String,
    val agentSession: String?,
    val state: String,
    val sequence: Long,
    val taskTitle: String? = null,
    val replyId: String? = null,
    val reply: String? = null,
    val replySupported: Boolean = false,
)

internal fun captureHerdrAgentStates(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
): List<HerdrAgentState>? = runCatching {
    val output = runner.run(herdrTaskAgentCommand(choice))
    require(output.exitStatus == 0 && output.stdout.size <= 256 * 1024)
    val result = (Json.parseToJsonElement(output.stdout.toString(Charsets.UTF_8)) as JsonObject)
        .getValue("result") as JsonObject
    require(result.string("type") == "agent_list")
    val agents = result.getValue("agents") as JsonArray
    require(agents.size <= 256)
    agents.map { element ->
        val agent = element as JsonObject
        val paneId = requireNotNull(agent.string("pane_id"))
        val terminalId = requireNotNull(agent.string("terminal_id"))
        val state = requireNotNull(agent.string("agent_status"))
        val sequence = (agent["state_change_seq"] as? JsonPrimitive)?.longOrNull
        require(paneId.matches(Regex("[A-Za-z0-9_-]+:p[0-9A-Z]+")))
        require(terminalId.isNotBlank() && terminalId.length <= 256)
        require(state in setOf("working", "idle", "done", "blocked", "unknown"))
        require(sequence != null && sequence >= 0L)
        val session = (agent["agent_session"] as? JsonObject)?.string("value")
        require(session == null || session.length <= 512)
        val reply = agent["task_reply"] as? JsonObject
        val replyId = reply?.string("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        val text = reply?.string("message")?.takeIf { it.length <= 1_024 && it.isNotBlank() }
        val title = sequenceOf("title", "name", "terminal_title_stripped", "terminal_title")
            .mapNotNull(agent::string).firstOrNull { it.isNotBlank() && it.length <= 1_024 }
        HerdrAgentState(paneId, terminalId, session, state, sequence, title,
            replyId.takeIf { text != null }, text.takeIf { replyId != null },
            agent.string("task_reply_source") == "codex")
    }.also { agents -> require(agents.map { it.paneId }.distinct().size == agents.size) }
}.getOrNull()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

internal fun herdrTaskAgentCommand(choice: HerdrStartupChoice): String {
    val command = listOf(choice.executable, "--session", choice.sessionName, "agent", "list")
        .joinToString(" ", transform = ::quotePosixShellArgument)
    val helper = "\"\${HOME}/.local/bin/terminal-task-notify\""
    val enriched = listOf("--agents", choice.executable, choice.sessionName)
        .joinToString(" ", transform = ::quotePosixShellArgument)
    val script = "if [ -x $helper ]; then exec $helper $enriched; else exec $command; fi"
    return "/bin/sh -c ${quotePosixShellArgument(script)}"
}

/** A first sample, restart, missing pane, or failed read never replays old completion. */
internal class HerdrTaskNotificationTracker {
    private var previous = emptyMap<String, HerdrAgentState>()
    private val awaitingReply = mutableSetOf<String>()

    fun observe(agents: List<HerdrAgentState>?): Boolean = observeEvents(agents).isNotEmpty()

    fun observeEvents(agents: List<HerdrAgentState>?): List<HerdrAgentState> {
        val currentAgents = agents.orEmpty()
        val events = currentAgents.filter { current ->
            val old = previous[current.paneId]
            val sameIdentity = old != null && old.terminalId == current.terminalId &&
                old.agentSession == current.agentSession
            if (!sameIdentity || old == null) {
                awaitingReply.remove(current.paneId)
                return@filter false
            }
            if (current.sequence < old.sequence) {
                awaitingReply.remove(current.paneId)
                return@filter false
            }
            if (current.state == "unknown") return@filter false
            val advanced = current.sequence > old.sequence
            val newReply = current.replyId != null && current.replyId != old.replyId &&
                (old.replyId != null || old.state == "working" || advanced || current.paneId in awaitingReply)
            val needsInput = current.state == "blocked" && old.state == "working" && advanced
            val finished = (current.state == "idle" || current.state == "done") &&
                old.state == "working" && advanced && !current.replySupported
            if (current.replySupported && old.state == "working" && advanced &&
                current.state in setOf("idle", "done") && current.replyId == null) {
                awaitingReply.add(current.paneId)
            }
            if (newReply) awaitingReply.remove(current.paneId)
            newReply || needsInput || finished
        }
        previous = currentAgents.associateBy { it.paneId }
        awaitingReply.retainAll(previous.keys)
        return events
    }
}

/** Validate the live identity again before focusing; a recycled pane must never open another task. */
internal fun focusHerdrTask(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
    paneId: String,
    terminalId: String,
    agentSession: String?,
): Boolean = runCatching {
    val current = captureHerdrAgentStates(runner, choice)?.singleOrNull { it.paneId == paneId }
        ?: return false
    if (current.terminalId != terminalId || current.agentSession != agentSession) return false
    val command = listOf(choice.executable, "--session", choice.sessionName, "agent", "focus", paneId)
        .joinToString(" ", transform = ::quotePosixShellArgument)
    runner.run(command).exitStatus == 0
}.getOrDefault(false)
