/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoshContractValidationTest {
    @Test
    fun capabilitiesRequireBoundedVersionOneValues() {
        val capabilities = MoshCapabilities(
            modelVersion = MoshApi.MODEL_VERSION,
            minimumApiVersion = 1,
            maximumApiVersion = 1,
            capabilityFlags = MoshCapability.IPV4 or MoshCapability.NETWORK_ROAMING,
            maximumConcurrentSessions = 2,
        )

        assertEquals(1, capabilities.minimumApiVersion)
        assertEquals(2, capabilities.maximumConcurrentSessions)
        val futureCapability = 1L shl 40
        MoshCapabilities(1, 1, 2, futureCapability, 1)
        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireNegotiatedCapabilities(futureCapability)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshCapabilities(1, 1, 1, MoshCapability.IPV4, MoshContractLimit.MAX_SESSIONS + 1)
        }
    }

    @Test
    fun requestFieldValidationRejectsHostnamesInvalidPortsAndAmbiguousPrediction() {
        MoshContractValidation.requireSessionId(SESSION_ID)
        MoshContractValidation.requireAddress(MoshAddressFamily.IPV4, byteArrayOf(192.toByte(), 0, 2, 1))
        MoshContractValidation.requirePort(60_001)
        MoshContractValidation.requireDimensions(120, 40)
        MoshContractValidation.requireLocale("en_GB.UTF-8")
        MoshContractValidation.requireOptionFlags(MoshOption.DISPLAY_AMBIGUOUS_WIDTH_WIDE)

        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireSessionId("saved-host-id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireAddress(MoshAddressFamily.IPV4, "host.example".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requirePort(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireOptionFlags(
                MoshOption.PREDICTION_ALWAYS or MoshOption.PREDICTION_NEVER,
            )
        }
    }

    @Test
    fun localeAndCallbackDetailAreUtf8Bounded() {
        MoshContractValidation.requireLocale("C.UTF-8")
        MoshContractValidation.requireRedactedDetail("UDP handshake timed out")

        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireLocale("x".repeat(MoshContractLimit.LOCALE_UTF8_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireRedactedDetail(
                "x".repeat(MoshContractLimit.REDACTED_DETAIL_UTF8_BYTES + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshContractValidation.requireRedactedDetail("first line\nsecret second line")
        }
    }

    @Test
    fun eventStateAndReasonMustAgree() {
        MoshSessionEvent(
            modelVersion = 1,
            sessionId = SESSION_ID,
            state = MoshSessionState.DISCONNECTED,
            disconnectReason = MoshDisconnectReason.REMOTE_CLOSED,
            errorCode = MoshErrorCode.NONE,
            redactedDetail = "Remote session closed",
            connectivityGeneration = 3,
        )

        assertThrows(IllegalArgumentException::class.java) {
            MoshSessionEvent(
                1,
                SESSION_ID,
                MoshSessionState.ERROR,
                MoshDisconnectReason.NONE,
                MoshErrorCode.NONE,
                "",
                0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshSessionEvent(
                1,
                SESSION_ID,
                MoshSessionState.CONNECTING,
                MoshDisconnectReason.NONE,
                MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL,
                "",
                0,
            )
        }
    }

    @Test
    fun networkHintContainsOnlyGenerationFamilyAndMeteredState() {
        val hint = MoshNetworkHint(1, 42, MoshAddressFamily.IPV6, true)

        assertEquals(42, hint.connectivityGeneration)
        assertEquals(MoshAddressFamily.IPV6, hint.addressFamily)
        assertEquals(true, hint.isMetered)
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
