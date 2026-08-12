/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshApiCompatibilityTest {
    @Test
    fun protocolAndModelRemainAtReviewedVersionOne() {
        assertEquals(1, MoshApi.PROTOCOL_VERSION)
        assertEquals(1, MoshApi.MODEL_VERSION)
    }

    @Test
    fun stableCapabilityStateReasonAndErrorValuesAreNotRenumbered() {
        assertEquals(1L, MoshCapability.IPV4)
        assertEquals(2L, MoshCapability.IPV6)
        assertEquals(4L, MoshCapability.NETWORK_ROAMING)
        assertEquals(8L, MoshCapability.PREDICTION_CONTROL)
        assertEquals(16L, MoshCapability.MULTIPLE_SESSIONS)
        assertEquals(1L, MoshOption.PREDICTION_ALWAYS)
        assertEquals(2L, MoshOption.PREDICTION_NEVER)
        assertEquals(4L, MoshOption.DISPLAY_AMBIGUOUS_WIDTH_WIDE)

        assertEquals(1, MoshSessionState.CONNECTING)
        assertEquals(2, MoshSessionState.CONNECTED)
        assertEquals(3, MoshSessionState.ROAMING)
        assertEquals(4, MoshSessionState.SUSPENDED)
        assertEquals(5, MoshSessionState.DISCONNECTED)
        assertEquals(6, MoshSessionState.ERROR)

        assertEquals(0, MoshErrorCode.NONE)
        assertEquals(1, MoshErrorCode.EXTENSION_ABSENT)
        assertEquals(2, MoshErrorCode.EXTENSION_INCOMPATIBLE)
        assertEquals(3, MoshErrorCode.EXTENSION_UNTRUSTED)
        assertEquals(4, MoshErrorCode.INVALID_REQUEST)
        assertEquals(5, MoshErrorCode.KEY_READ_FAILED)
        assertEquals(6, MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL)
        assertEquals(7, MoshErrorCode.LOCALE_UNSUPPORTED)
        assertEquals(8, MoshErrorCode.NATIVE_INITIALIZATION_FAILED)
        assertEquals(9, MoshErrorCode.EXTENSION_DIED)
        assertEquals(10, MoshErrorCode.CANCELLED)
        assertEquals(11, MoshErrorCode.INTERNAL_REDACTED)

        assertEquals(0, MoshDisconnectReason.NONE)
        assertEquals(1, MoshDisconnectReason.USER_REQUESTED)
        assertEquals(2, MoshDisconnectReason.REMOTE_CLOSED)
        assertEquals(3, MoshDisconnectReason.TRANSPORT_LOST)
        assertEquals(4, MoshDisconnectReason.EXTENSION_FAILED)
        assertEquals(5, MoshDisconnectReason.SESSION_REPLACED)

        assertEquals(1, MoshStopReason.USER_REQUESTED)
        assertEquals(2, MoshStopReason.SESSION_REPLACED)
        assertEquals(3, MoshStopReason.APP_SHUTDOWN)
        assertEquals(4, MoshStopReason.ERROR_RECOVERY)
    }

    @Test
    fun pluginInterfaceRetainsVersionOneMethodSurface() {
        val methods = IMoshPlugin::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { method ->
                val parameters = method.parameterTypes.joinToString(",") { it.simpleName }
                "${method.name}($parameters):${method.returnType.simpleName}"
            }
            .sorted()

        assertEquals(
            listOf(
                "getApiVersion():int",
                "getCapabilities():MoshCapabilities",
                "registerCallback(IMoshCallback):void",
                "resizeSession(String,int,int):void",
                "startSession(MoshSessionRequest):MoshSessionHandle",
                "stopSession(String,int):void",
                "unregisterCallback(IMoshCallback):void",
                "updateNetworkHint(String,MoshNetworkHint):void",
            ),
            methods,
        )
    }

    @Test
    fun callbackRetainsOneBoundedControlEventMethod() {
        val methods = IMoshCallback::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { method ->
                val parameters = method.parameterTypes.joinToString(",") { it.simpleName }
                "${method.name}($parameters):${method.returnType.simpleName}"
            }

        assertEquals(listOf("onSessionEvent(MoshSessionEvent):void"), methods)
    }

    @Test
    fun parcelablesRetainVersionOnePublicFieldSurface() {
        assertGetters(
            MoshCapabilities::class.java,
            "getCapabilityFlags",
            "getMaximumApiVersion",
            "getMaximumConcurrentSessions",
            "getMinimumApiVersion",
            "getModelVersion",
        )
        assertGetters(
            MoshNetworkHint::class.java,
            "getAddressFamily",
            "getConnectivityGeneration",
            "getModelVersion",
            "isMetered",
        )
        assertGetters(
            MoshSessionRequest::class.java,
            "getAddressFamily",
            "getInitialColumns",
            "getInitialRows",
            "getLocale",
            "getModelVersion",
            "getMoshKeyRead",
            "getOptionFlags",
            "getServerAddress",
            "getSessionId",
            "getUdpPort",
        )
        assertGetters(
            MoshSessionHandle::class.java,
            "getInitialState",
            "getModelVersion",
            "getNegotiatedCapabilityFlags",
            "getSessionId",
            "getTerminalInputWrite",
            "getTerminalOutputRead",
        )
        assertGetters(
            MoshSessionEvent::class.java,
            "getConnectivityGeneration",
            "getDisconnectReason",
            "getErrorCode",
            "getModelVersion",
            "getRedactedDetail",
            "getSessionId",
            "getState",
        )
    }

    @Test
    fun requestSurfaceCannotCarrySshCredentialsOrHostnames() {
        val publicProperties = MoshSessionRequest::class.java.methods
            .map { it.name.lowercase() }
            .toSet()
        val forbiddenFragments = listOf(
            "password",
            "privatekey",
            "passphrase",
            "credential",
            "hostname",
            "knownhost",
            "startupcommand",
            "connectionuri",
        )

        forbiddenFragments.forEach { forbidden ->
            assertFalse(publicProperties.any { forbidden in it })
        }
        assertTrue(publicProperties.contains("getmoshkeyread"))
        assertTrue(publicProperties.contains("getserveraddress"))
    }

    private fun assertGetters(type: Class<*>, vararg expected: String) {
        val actual = type.declaredMethods
            .filter { method -> method.parameterCount == 0 && (method.name.startsWith("get") || method.name.startsWith("is")) }
            .map { it.name }
            .sorted()
        assertEquals(expected.sorted(), actual)
    }
}
