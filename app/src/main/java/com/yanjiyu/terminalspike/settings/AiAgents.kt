package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.core.data.settings.AiAgentCommand
import com.yanjiyu.terminalspike.core.data.settings.AiAgentConfiguration
import com.yanjiyu.terminalspike.core.data.settings.AppSettings

data class AiAgent(val id: String, val name: String, val command: String)

data class AiAgents(
    val entries: List<AiAgent> = defaultEntries,
    val defaultId: String = "",
) {
    val defaultAgent: AiAgent? get() = entries.firstOrNull { it.id == defaultId }

    fun isValid(): Boolean = entries.size <= MAX_ENTRIES &&
        entries.map { it.id }.distinct().size == entries.size &&
        entries.all {
            it.id.isNotBlank() && it.id.length <= 128 &&
                it.name.isNotBlank() && it.name.length <= 64 &&
                it.command.isNotBlank() && it.command.length <= 4096 &&
                it.name.none(Char::isISOControl) && it.command.none(Char::isISOControl)
        } && (defaultId.isEmpty() || defaultAgent != null)

    fun without(id: String): AiAgents = copy(
        entries = entries.filterNot { it.id == id },
        defaultId = defaultId.takeUnless { it == id }.orEmpty(),
    )

    fun toProto(): AiAgentConfiguration {
        require(isValid())
        return AiAgentConfiguration.newBuilder().setDefaultId(defaultId)
            .addAllEntries(entries.map {
                AiAgentCommand.newBuilder().setId(it.id).setName(it.name).setCommand(it.command).build()
            }).build()
    }

    companion object {
        const val MAX_ENTRIES = 100
        val defaultEntries = listOf(
            AiAgent("codex", "Codex", "codex --yolo"),
            AiAgent("claude", "Claude", "claude"),
            AiAgent("pi", "pi", "pi"),
            AiAgent("opencode", "OpenCode", "opencode"),
        )

        fun from(settings: AppSettings): AiAgents = if (!settings.hasAiAgents()) AiAgents() else {
            AiAgents(
                settings.aiAgents.entriesList.map { AiAgent(it.id, it.name, it.command) },
                settings.aiAgents.defaultId,
            )
        }
    }
}
