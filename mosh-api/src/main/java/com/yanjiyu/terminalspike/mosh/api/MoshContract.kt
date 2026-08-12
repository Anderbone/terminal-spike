/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

/** Stable protocol and model versions shared by the main app and the separate extension. */
public object MoshApi {
    public const val PROTOCOL_VERSION: Int = 1
    public const val MODEL_VERSION: Int = 1
}

/** Advertised extension capabilities. Consumers ignore unknown bits and negotiate known ones. */
public object MoshCapability {
    public const val IPV4: Long = 1L shl 0
    public const val IPV6: Long = 1L shl 1
    public const val NETWORK_ROAMING: Long = 1L shl 2
    public const val PREDICTION_CONTROL: Long = 1L shl 3
    public const val MULTIPLE_SESSIONS: Long = 1L shl 4

    internal const val VERSION_1_MASK: Long =
        IPV4 or IPV6 or NETWORK_ROAMING or PREDICTION_CONTROL or MULTIPLE_SESSIONS
}

/** Numeric-address families. Hostnames are deliberately not part of this API. */
public object MoshAddressFamily {
    public const val UNSPECIFIED: Int = 0
    public const val IPV4: Int = 4
    public const val IPV6: Int = 6
}

/** Reviewed prediction/display request bits. No flags means adaptive prediction. */
public object MoshOption {
    public const val PREDICTION_ALWAYS: Long = 1L shl 0
    public const val PREDICTION_NEVER: Long = 1L shl 1
    public const val DISPLAY_AMBIGUOUS_WIDTH_WIDE: Long = 1L shl 2

    internal const val VERSION_1_MASK: Long =
        PREDICTION_ALWAYS or PREDICTION_NEVER or DISPLAY_AMBIGUOUS_WIDTH_WIDE
}

public object MoshSessionState {
    public const val CONNECTING: Int = 1
    public const val CONNECTED: Int = 2
    public const val ROAMING: Int = 3
    public const val SUSPENDED: Int = 4
    public const val DISCONNECTED: Int = 5
    public const val ERROR: Int = 6
}

/** Stable, non-secret error groups. Details must be bounded and redacted separately. */
public object MoshErrorCode {
    public const val NONE: Int = 0
    public const val EXTENSION_ABSENT: Int = 1
    public const val EXTENSION_INCOMPATIBLE: Int = 2
    public const val EXTENSION_UNTRUSTED: Int = 3
    public const val INVALID_REQUEST: Int = 4
    public const val KEY_READ_FAILED: Int = 5
    public const val UDP_TIMEOUT_OR_FIREWALL: Int = 6
    public const val LOCALE_UNSUPPORTED: Int = 7
    public const val NATIVE_INITIALIZATION_FAILED: Int = 8
    public const val EXTENSION_DIED: Int = 9
    public const val CANCELLED: Int = 10
    public const val INTERNAL_REDACTED: Int = 11
}

public object MoshDisconnectReason {
    public const val NONE: Int = 0
    public const val USER_REQUESTED: Int = 1
    public const val REMOTE_CLOSED: Int = 2
    public const val TRANSPORT_LOST: Int = 3
    public const val EXTENSION_FAILED: Int = 4
    public const val SESSION_REPLACED: Int = 5
}

public object MoshStopReason {
    public const val USER_REQUESTED: Int = 1
    public const val SESSION_REPLACED: Int = 2
    public const val APP_SHUTDOWN: Int = 3
    public const val ERROR_RECOVERY: Int = 4
}

/** Public limits are part of the version-1 boundary and must not be relaxed silently. */
public object MoshContractLimit {
    public const val SESSION_ID_UTF8_BYTES: Int = 36
    public const val LOCALE_UTF8_BYTES: Int = 64
    public const val REDACTED_DETAIL_UTF8_BYTES: Int = 256
    public const val MAX_COLUMNS: Int = 1_000
    public const val MAX_ROWS: Int = 1_000
    public const val MAX_SESSIONS: Int = 64
}
