package com.yanjiyu.terminalspike.localarch

/** Setup commands run in a PTY; remove its display controls before showing copyable diagnostics. */
internal fun archDiagnosticOutput(output: String): String = output
    .replace(Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"), "")
    .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    .replace("\r\n", "\n")
    .filter { it == '\n' || it == '\t' || !it.isISOControl() }
    .trim()
    .takeLast(12 * 1024)

internal fun archInstallationError(phase: ArchInstallPhase, failure: Exception): String {
    val stage = when (phase) {
        ArchInstallPhase.INSTALLING_TOOLS -> "installing starter tools and system updates"
        else -> phase.name.lowercase().replace('_', ' ')
    }
    val detail = archDiagnosticOutput(failure.message ?: failure.javaClass.simpleName)
    return "Arch failed while $stage.\n\n$detail"
}
