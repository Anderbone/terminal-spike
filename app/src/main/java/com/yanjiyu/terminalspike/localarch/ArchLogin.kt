package com.yanjiyu.terminalspike.localarch

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

internal enum class ArchLoginProvider(val label: String, val verificationUrl: String, val script: String) {
    GITHUB("GitHub", "https://github.com/login/device",
        "set -e; export GH_BROWSER=true GH_PROMPT_DISABLED=1; gh auth login --hostname github.com --git-protocol https --web --skip-ssh-key </dev/null; gh auth setup-git --hostname github.com"),
    CODEX("Codex", "https://auth.openai.com/codex/device", "exec codex login --device-auth"),
    ;

    val command: List<String> get() = listOf("/bin/bash", "--noprofile", "--norc", "-c", script)
}

internal data class ArchLoginState(
    val provider: ArchLoginProvider? = null,
    val active: Boolean = false,
    val code: String? = null,
    val succeeded: Boolean = false,
    val failed: Boolean = false,
)

/** Only a short-lived device code crosses into Android UI; never retain or log CLI output. */
internal class ArchLoginCodeParser {
    private var pending = ""

    fun accept(text: String): String? {
        pending = (pending + text).takeLast(8192)
        val plain = pending.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        return Regex("(?<![A-Z0-9-])[A-Z0-9]{4}-[A-Z0-9]{4,5}(?=\\s)")
            .find(plain)?.value
    }
}

internal suspend fun runArchLogin(pty: LocalPty, onCode: (String) -> Unit): Boolean {
    val deadline = System.nanoTime() + 16 * 60 * 1_000_000_000L
    val parser = ArchLoginCodeParser()
    val buffer = ByteArray(4096)
    var previous: String? = null
    while (System.nanoTime() < deadline) {
        currentCoroutineContext().ensureActive()
        val count = pty.read(buffer)
        if (count < 0) {
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                pty.exitCode()?.let { return it == 0 }
                delay(20)
            }
            return false
        }
        if (count > 0) {
            val code = parser.accept(String(buffer, 0, count, Charsets.UTF_8))
            if (code != null && code != previous) { previous = code; onCode(code) }
        }
    }
    return false
}
