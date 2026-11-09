package com.yanjiyu.terminalspike.settings

/** Session commands, deliberately separate from shell/startup snippets and their saved order. */
internal object AgentTerminalSnippets {
    // Native session commands: https://learn.chatgpt.com/docs/developer-commands?surface=cli
    val codex = listOf("low", "medium", "high", "xhigh", "max", "ultra").mapIndexed { index, effort ->
        CommandSnippet(
            id = -101L - index,
            label = when (effort) { "xhigh" -> "Extra High"; else -> effort.replaceFirstChar(Char::uppercase) },
            command = "/model --reasoning=$effort",
            appendEnter = true,
        )
    }
    val all = codex

    fun forAgent(agentId: String): List<CommandSnippet> = if (agentId == "codex") codex else emptyList()
}
