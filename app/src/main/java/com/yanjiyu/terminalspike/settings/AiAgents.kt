package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.core.data.settings.AiAgentCommand
import com.yanjiyu.terminalspike.core.data.settings.AiAgentConfiguration
import com.yanjiyu.terminalspike.core.data.settings.AppSettings

data class AiAgent(
    val id: String,
    val name: String,
    val command: String,
    val model: String = "",
    val effort: String = "",
) {
    // Only append arguments to direct invocations. Shell scripts and wrappers keep their exact command.
    val cli: String? get() {
        if (command.any { it in ";|&<>`$\\\n\r\"'#()" }) return null
        if (Regex("(?:^|\\s)(?:--model|-m|--effort|-c|--config|--)(?:\\s|=|$)").containsMatchIn(command)) return null
        return Regex("^\\s*(?:[A-Za-z0-9_./~-]+/)?(codex|claude)(?:\\s|$)")
            .find(command)?.groupValues?.get(1)
    }

    val modelPresets: List<String> get() = when (cli) {
        "codex" -> listOf("gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-astra", "gpt-6-luna")
        "claude" -> listOf("sonnet", "opus", "haiku")
        else -> emptyList()
    }

    val effortPresets: List<String> get() = when {
        cli == "codex" -> listOf("low", "medium", "high", "xhigh", "max")
        cli == "claude" && !model.startsWith("claude-haiku-4-5", ignoreCase = true) ->
            listOf("low", "medium", "high", "xhigh", "max")
        else -> emptyList()
    }

    val launchCommand: String get() = buildString {
        append(command)
        if (cli != null && model.isNotEmpty()) append(" --model ${shellArgument(model)}")
        if (effort.isNotEmpty() && effort in effortPresets) {
            if (cli == "codex") append(" -c ${shellArgument("model_reasoning_effort=\"$effort\"")}")
            else append(" --effort ${shellArgument(effort)}")
        }
    }

    fun launchOptionsAreValid(): Boolean = model.length <= 200 && model.none(Char::isISOControl) &&
        (model.isEmpty() || model.isNotBlank()) &&
        (effort.isEmpty() || effort in effortPresets)

    private fun shellArgument(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

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
                it.name.none(Char::isISOControl) && it.command.none(Char::isISOControl) &&
                it.launchOptionsAreValid()
        } && (defaultId.isEmpty() || defaultAgent != null)

    fun without(id: String): AiAgents = copy(
        entries = entries.filterNot { it.id == id },
        defaultId = defaultId.takeUnless { it == id }.orEmpty(),
    )

    fun toProto(): AiAgentConfiguration {
        require(isValid())
        return AiAgentConfiguration.newBuilder().setDefaultId(defaultId)
            .addAllEntries(entries.map {
                AiAgentCommand.newBuilder().setId(it.id).setName(it.name).setCommand(it.command)
                    .setModel(it.model).setEffort(it.effort).build()
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
                settings.aiAgents.entriesList.map { AiAgent(it.id, it.name, it.command, it.model, it.effort) },
                settings.aiAgents.defaultId,
            )
        }
    }
}
