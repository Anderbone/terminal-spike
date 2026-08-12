/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import java.util.UUID

internal object MoshContractValidation {
    fun requireModelVersion(version: Int) {
        require(version == MoshApi.MODEL_VERSION) { "Unsupported model version: $version" }
    }

    fun requireSessionId(sessionId: String) {
        requireUtf8Bounded(sessionId, "sessionId", MoshContractLimit.SESSION_ID_UTF8_BYTES)
        val parsed = runCatching { UUID.fromString(sessionId) }.getOrNull()
        require(parsed != null && parsed.toString() == sessionId) {
            "sessionId must be a canonical lowercase UUID"
        }
    }

    fun requireAddress(addressFamily: Int, address: ByteArray) {
        val expectedSize = when (addressFamily) {
            MoshAddressFamily.IPV4 -> 4
            MoshAddressFamily.IPV6 -> 16
            else -> throw IllegalArgumentException("Unsupported numeric address family: $addressFamily")
        }
        require(address.size == expectedSize) {
            "Address family $addressFamily requires $expectedSize address bytes"
        }
    }

    fun requirePort(port: Int) {
        require(port in 1..65_535) { "UDP port must be in 1..65535" }
    }

    fun requireDimensions(columns: Int, rows: Int) {
        require(columns in 1..MoshContractLimit.MAX_COLUMNS) {
            "columns must be in 1..${MoshContractLimit.MAX_COLUMNS}"
        }
        require(rows in 1..MoshContractLimit.MAX_ROWS) {
            "rows must be in 1..${MoshContractLimit.MAX_ROWS}"
        }
    }

    fun requireLocale(locale: String) {
        requireUtf8Bounded(locale, "locale", MoshContractLimit.LOCALE_UTF8_BYTES)
        require(LOCALE_PATTERN.matches(locale)) { "locale contains unsupported characters" }
    }

    fun requireOptionFlags(flags: Long) {
        require(flags and MoshOption.VERSION_1_MASK.inv() == 0L) {
            "Unknown version-1 option flags"
        }
        require(flags and MoshOption.PREDICTION_ALWAYS == 0L || flags and MoshOption.PREDICTION_NEVER == 0L) {
            "Prediction-always and prediction-never are mutually exclusive"
        }
    }

    fun requireNegotiatedCapabilities(flags: Long) {
        require(flags and MoshCapability.VERSION_1_MASK.inv() == 0L) {
            "Negotiated capabilities contain unknown version-1 flags"
        }
        require(flags and (MoshCapability.IPV4 or MoshCapability.IPV6) != 0L) {
            "At least one numeric address family capability is required"
        }
    }

    fun requireNetworkHint(addressFamily: Int, generation: Long) {
        require(
            addressFamily == MoshAddressFamily.UNSPECIFIED ||
                addressFamily == MoshAddressFamily.IPV4 ||
                addressFamily == MoshAddressFamily.IPV6,
        ) { "Unsupported network address family: $addressFamily" }
        require(generation >= 0L) { "connectivityGeneration must be non-negative" }
    }

    fun requireState(state: Int) {
        require(state in MoshSessionState.CONNECTING..MoshSessionState.ERROR) {
            "Unsupported session state: $state"
        }
    }

    fun requireErrorCode(errorCode: Int) {
        require(errorCode in MoshErrorCode.NONE..MoshErrorCode.INTERNAL_REDACTED) {
            "Unsupported error code: $errorCode"
        }
    }

    fun requireDisconnectReason(reason: Int) {
        require(reason in MoshDisconnectReason.NONE..MoshDisconnectReason.SESSION_REPLACED) {
            "Unsupported disconnect reason: $reason"
        }
    }

    fun requireEventConsistency(state: Int, disconnectReason: Int, errorCode: Int) {
        if (state == MoshSessionState.ERROR) {
            require(errorCode != MoshErrorCode.NONE) { "Error state requires a stable error code" }
        } else {
            require(errorCode == MoshErrorCode.NONE) {
                "A stable error code is only valid for error state"
            }
        }
        if (state == MoshSessionState.DISCONNECTED) {
            require(disconnectReason != MoshDisconnectReason.NONE) {
                "Disconnected state requires a stable disconnect reason"
            }
        } else {
            require(disconnectReason == MoshDisconnectReason.NONE) {
                "Disconnect reason is only valid for disconnected state"
            }
        }
    }

    fun requireRedactedDetail(detail: String) {
        requireUtf8Bounded(
            detail,
            "redactedDetail",
            MoshContractLimit.REDACTED_DETAIL_UTF8_BYTES,
            allowEmpty = true,
        )
        require(detail.none { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }) {
            "redactedDetail must be a single printable line"
        }
    }

    private fun requireUtf8Bounded(
        value: String,
        field: String,
        maxBytes: Int,
        allowEmpty: Boolean = false,
    ) {
        require(allowEmpty || value.isNotEmpty()) { "$field must not be empty" }
        require(value.encodeToByteArray().size <= maxBytes) { "$field exceeds $maxBytes UTF-8 bytes" }
    }

    private val LOCALE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.@-]{0,63}")
}
