package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.TerminalProfile

internal const val DEFAULT_RUNTIME_SCROLLBACK_LINES = 20_000

internal fun resolveRuntimeScrollbackLines(configuredLines: Int): Int =
    configuredLines
        .takeIf { it > 0 }
        ?.coerceAtMost(ModelLimits.MAX_SCROLLBACK_LINES)
        ?: DEFAULT_RUNTIME_SCROLLBACK_LINES

/** Exact terminal runtime selected for one fresh connection; null profile means a stale reference. */
internal class RuntimeTerminalSelection(
    val explicitProfileId: String?,
    val profile: TerminalProfile?,
    val startupCommand: String?,
)

internal fun resolveRuntimeTerminalSelection(
    host: HostProfile?,
    defaultProfile: TerminalProfile?,
    profilesById: Map<String, TerminalProfile>,
): RuntimeTerminalSelection {
    val explicitProfileId = host?.terminalProfileId
    return RuntimeTerminalSelection(
        explicitProfileId = explicitProfileId,
        profile = if (explicitProfileId == null) {
            defaultProfile
        } else {
            profilesById[explicitProfileId]
        },
        startupCommand = host?.startupCommand,
    )
}
