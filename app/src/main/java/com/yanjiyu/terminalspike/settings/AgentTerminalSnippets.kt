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
    // Claude Code's native /effort command; auto clears the model's saved preference.
    // https://code.claude.com/docs/en/model-config#adjust-effort-level
    val claude = listOf("low", "medium", "high", "xhigh", "max", "auto").mapIndexed { index, effort ->
        CommandSnippet(
            id = -201L - index,
            label = if (effort == "xhigh") "Extra High" else effort.replaceFirstChar(Char::uppercase),
            command = "/effort $effort",
            appendEnter = true,
        )
    }
    val all = codex + claude

    fun forAgent(agentId: String): List<CommandSnippet> = when (agentId) {
        "codex" -> codex
        "claude" -> claude
        else -> emptyList()
    }

    fun canSend(id: Long, agentId: String, hasKnownIdentity: Boolean): Boolean =
        forAgent(agentId).any { it.id == id } ||
            (!hasKnownIdentity && agentId.isEmpty() && codex.any { it.id == id })
}
