package com.yanjiyu.terminalspike.connection

/** Small channel seam that keeps PTY negotiation deterministic in JVM tests. */
internal interface SshPtyTarget {
    fun enablePty()

    fun setTerminalType(terminalType: String)

    fun setDimensions(columns: Int, rows: Int)
}

internal fun configureSshPty(
    target: SshPtyTarget,
    terminalType: String,
    columns: Int,
    rows: Int,
) {
    target.enablePty()
    target.setTerminalType(terminalType)
    target.setDimensions(columns.coerceAtLeast(1), rows.coerceAtLeast(1))
}
