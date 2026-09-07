package com.yanjiyu.terminalspike.connection.mosh

import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshExtensionDecisionTest {
    @Test
    fun packageServiceActionAndPermissionRemainExact() {
        assertEquals("com.yanjiyu.terminalspike", MoshExtensionContract.PACKAGE_NAME)
        assertEquals(
            "com.yanjiyu.terminalspike.mosh.MoshExtensionService",
            MoshExtensionContract.SERVICE_CLASS_NAME,
        )
        assertEquals("com.yanjiyu.terminalspike.mosh.BIND", MoshExtensionContract.BIND_ACTION)

    }

    @Test
    fun missingAndDisabledPackagesRemainHonestNonAvailableStates() {
        assertEquals(MoshDiscoveryDecision.Absent, MoshExtensionDecision.discover(null))
        assertTrue(
            MoshExtensionDecision.discover(trustedFacts(applicationEnabled = false)) is
                MoshDiscoveryDecision.Disabled,
        )
        assertTrue(
            MoshExtensionDecision.discover(trustedFacts(serviceEnabled = false)) is
                MoshDiscoveryDecision.Disabled,
        )
    }

    @Test
    fun discoveryRequiresPrivateSameUidServiceInTheBrokerProcess() {
        assertUntrusted(trustedFacts(servicePresent = false), MoshExtensionTrustReason.SERVICE_MISSING)
        assertUntrusted(trustedFacts(serviceExported = true), MoshExtensionTrustReason.SERVICE_EXPORTED)
        assertUntrusted(trustedFacts(sameApplicationUid = false), MoshExtensionTrustReason.UID_MISMATCH)
        assertUntrusted(trustedFacts(separateBrokerProcess = false), MoshExtensionTrustReason.SERVICE_PROCESS_MISMATCH)
        assertTrue(MoshExtensionDecision.discover(trustedFacts()) is MoshDiscoveryDecision.Trusted)
    }

    @Test
    fun compatibleApiNegotiatesKnownVersionAndPreservesFutureCapabilityBits() {
        val futureBit = 1L shl 40
        val result = MoshProtocolNegotiator.negotiate(
            extensionApiVersion = 2,
            capabilities = MoshCapabilities(
                modelVersion = 1,
                minimumApiVersion = 1,
                maximumApiVersion = 2,
                capabilityFlags = MoshCapability.IPV4 or futureBit,
                maximumConcurrentSessions = 3,
            ),
        )

        assertTrue(result is MoshNegotiationDecision.Compatible)
        val protocol = (result as MoshNegotiationDecision.Compatible).protocol
        assertEquals(1, protocol.negotiatedApiVersion)
        assertEquals(MoshCapability.IPV4 or futureBit, protocol.capabilityFlags)
    }

    @Test
    fun negotiationRejectsMismatchedVersionRangeOrMissingAddressCapability() {
        val wrongReportedVersion = MoshProtocolNegotiator.negotiate(
            1,
            MoshCapabilities(1, 1, 2, MoshCapability.IPV4, 1),
        )
        val wrongRange = MoshProtocolNegotiator.negotiate(
            2,
            MoshCapabilities(1, 2, 2, MoshCapability.IPV4, 1),
        )
        val noAddressFamily = MoshProtocolNegotiator.negotiate(
            1,
            MoshCapabilities(1, 1, 1, MoshCapability.NETWORK_ROAMING, 1),
        )

        assertEquals(
            MoshExtensionCompatibilityReason.INVALID_API_VERSION,
            (wrongReportedVersion as MoshNegotiationDecision.Incompatible).reason,
        )
        assertEquals(
            MoshExtensionCompatibilityReason.API_RANGE_MISMATCH,
            (wrongRange as MoshNegotiationDecision.Incompatible).reason,
        )
        assertEquals(
            MoshExtensionCompatibilityReason.CAPABILITY_MISMATCH,
            (noAddressFamily as MoshNegotiationDecision.Incompatible).reason,
        )
    }

    private fun assertUntrusted(
        facts: MoshInstalledPackageFacts,
        reason: MoshExtensionTrustReason,
    ) {
        val result = MoshExtensionDecision.discover(facts)
        assertTrue(result is MoshDiscoveryDecision.Untrusted)
        assertEquals(reason, (result as MoshDiscoveryDecision.Untrusted).reason)
    }

    private fun trustedFacts(
        applicationEnabled: Boolean = true,
        servicePresent: Boolean = true,
        serviceEnabled: Boolean = true,
        serviceExported: Boolean = false,
        sameApplicationUid: Boolean = true,
        separateBrokerProcess: Boolean = true,
    ) = MoshInstalledPackageFacts(
        version = MoshExtensionVersion(12, "1.2.0"),
        applicationEnabled = applicationEnabled,
        servicePresent = servicePresent,
        serviceEnabled = serviceEnabled,
        serviceExported = serviceExported,
        sameApplicationUid = sameApplicationUid,
        separateBrokerProcess = separateBrokerProcess,
    )
}
