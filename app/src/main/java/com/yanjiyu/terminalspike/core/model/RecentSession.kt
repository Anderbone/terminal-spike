package com.yanjiyu.terminalspike.core.model

/**
 * Persisted, display-safe session metadata used for active/recent cards and honest reconnect offers.
 * Raw endpoint and credential details remain reachable only through [hostProfileId]. A nullable
 * device-local HMAC token can group unsaved/deleted-host history without revealing that endpoint.
 */
data class RecentSession(
    val id: String,
    /** Null retains safe history after its host profile has been deleted, but disables reconnect. */
    val hostProfileId: String?,
    val hostDisplayName: String,
    val protocol: ConnectionProtocol,
    val state: SessionState,
    val startedAtEpochMillis: Long,
    val lastActivityAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val terminalTitle: String? = null,
    /** Device-local HMAC only; raw endpoint identity never enters persisted Recent metadata. */
    val endpointIdentityToken: String? = null,
) {
    init {
        requireCanonicalUuid(id, "session ID")
        hostProfileId?.let { requireCanonicalUuid(it, "host profile ID") }
        requirePlainText(hostDisplayName, "host display name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        requireOptionalPlainText(terminalTitle, "terminal title", ModelLimits.MAX_TERMINAL_TITLE_LENGTH)
        endpointIdentityToken?.let { token ->
            require(token.length == ENDPOINT_IDENTITY_HEX_LENGTH && token.all { character ->
                character in '0'..'9' || character in 'a'..'f'
            }) { "Endpoint identity token must be a lowercase HMAC-SHA-256 value." }
        }
        requireEpochMillis(startedAtEpochMillis, "started timestamp")
        requireEpochMillis(lastActivityAtEpochMillis, "last-activity timestamp")
        requireTimestampOrder(startedAtEpochMillis, lastActivityAtEpochMillis, "last-activity timestamp")
        endedAtEpochMillis?.let {
            requireEpochMillis(it, "ended timestamp")
            requireTimestampOrder(lastActivityAtEpochMillis, it, "ended timestamp")
        }
        require(state.isActive == (endedAtEpochMillis == null)) {
            "Active sessions must not have an end time and ended sessions must have one."
        }
    }

    private companion object {
        const val ENDPOINT_IDENTITY_HEX_LENGTH = 64
    }
}

/**
 * Display-safe, grouped activity for one saved host.
 *
 * [lastActivityAtEpochMillis] is the newest persisted activity across both active and ended session
 * history. This deliberately carries neither endpoint metadata nor credential material.
 */
data class RecentHostActivity(
    val hostProfileId: String,
    val lastActivityAtEpochMillis: Long,
) {
    init {
        requireCanonicalUuid(hostProfileId, "host profile ID")
        requireEpochMillis(lastActivityAtEpochMillis, "last-activity timestamp")
    }
}
