package com.yanjiyu.terminalspike.connection

/** Bounded reads over a live authenticated SSH exec channel, never the interactive PTY. */
internal interface RemoteProjectConnection {
    fun readProject(command: String): RemoteProjectOutput
    fun projectLocations(): List<RemoteProjectLocation> = emptyList()
}

internal val PROJECT_EXEC_LIMITS = TmuxExecLimits(10_000, 20_000, 4 * 1024 * 1024)

/** Result of a bounded, non-interactive remote project read. */
class RemoteProjectOutput(val stdout: ByteArray, val exitStatus: Int)

/** A host-reported directory; Git resolves its actual worktree root when opened. */
data class RemoteProjectLocation(val path: String, val label: String, val current: Boolean = false)
