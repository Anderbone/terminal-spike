package com.yanjiyu.terminalspike.settings

/** Exact historical password identity used for migration associated data. */
data class SshPasswordScope(
    val profileId: Long,
    val host: String,
    val port: Int,
    val username: String,
)
