package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Only identity and lifecycle metadata; never terminal text or task contents. */
data class HerdrAgentState(
    val paneId: String,
    val terminalId: String,
    val agentSession: String?,
    val state: String,
    val sequence: Long,
)

internal fun captureHerdrAgentStates(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
): List<HerdrAgentState>? = runCatching {
    val command = listOf(choice.executable, "--session", choice.sessionName, "agent", "list")
        .joinToString(" ", transform = ::quotePosixShellArgument)
    val output = runner.run(command)
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
        HerdrAgentState(paneId, terminalId, session, state, sequence)
    }.also { agents -> require(agents.map { it.paneId }.distinct().size == agents.size) }
}.getOrNull()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

/** A first sample, restart, missing pane, or failed read never replays old completion. */
internal class HerdrTaskNotificationTracker {
    private var previous = emptyMap<String, HerdrAgentState>()

    fun observe(agents: List<HerdrAgentState>?): Boolean {
        val currentAgents = agents.orEmpty()
        val completed = currentAgents.any { current ->
            val old = previous[current.paneId]
            old != null && old.terminalId == current.terminalId &&
                old.agentSession == current.agentSession && old.state == "working" &&
                (current.state == "idle" || current.state == "done") && current.sequence > old.sequence
        }
        previous = currentAgents.associateBy { it.paneId }
        return completed
    }
}
