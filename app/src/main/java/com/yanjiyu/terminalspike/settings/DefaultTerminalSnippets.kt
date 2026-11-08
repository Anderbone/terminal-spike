package com.yanjiyu.terminalspike.settings

/** Built-in terminal actions share IDs across the toolbar and session picker. */
internal object DefaultTerminalSnippets {
    val newCodex = CommandSnippet(0L, "New Codex", SnippetSequence.CODEX_EXAMPLE, false)
    val text = listOf(
        CommandSnippet(-1L, "Continue", "continue", false, sendsImmediately = false),
        CommandSnippet(-2L, "Git commit & push", "git add -A && git commit && git push", false, sendsImmediately = false),
    )
    val all = listOf(newCodex) + text
}
