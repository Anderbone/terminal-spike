package com.yanjiyu.terminalspike.connection.mosh

import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshCapability

internal data class MoshInstalledPackageFacts(
    val version: MoshExtensionVersion,
    val applicationEnabled: Boolean,
    val servicePresent: Boolean,
    val serviceEnabled: Boolean,
    val serviceExported: Boolean,
    val sameApplicationUid: Boolean,
    val separateBrokerProcess: Boolean,
)

internal sealed interface MoshDiscoveryDecision {
    data object Absent : MoshDiscoveryDecision

    data class Disabled(val version: MoshExtensionVersion) : MoshDiscoveryDecision

    data class Untrusted(
        val version: MoshExtensionVersion,
        val reason: MoshExtensionTrustReason,
    ) : MoshDiscoveryDecision

    data class Trusted(val version: MoshExtensionVersion) : MoshDiscoveryDecision

    data object QueryFailed : MoshDiscoveryDecision
}

internal object MoshExtensionDecision {
    fun discover(facts: MoshInstalledPackageFacts?): MoshDiscoveryDecision {
        if (facts == null) return MoshDiscoveryDecision.Absent
        if (!facts.applicationEnabled || (facts.servicePresent && !facts.serviceEnabled)) {
            return MoshDiscoveryDecision.Disabled(facts.version)
        }
        if (!facts.servicePresent) {
            return MoshDiscoveryDecision.Untrusted(
                facts.version,
                MoshExtensionTrustReason.SERVICE_MISSING,
            )
        }
        if (facts.serviceExported) {
            return MoshDiscoveryDecision.Untrusted(facts.version, MoshExtensionTrustReason.SERVICE_EXPORTED)
        }
        if (!facts.sameApplicationUid) {
            return MoshDiscoveryDecision.Untrusted(facts.version, MoshExtensionTrustReason.UID_MISMATCH)
        }
        if (!facts.separateBrokerProcess) {
            return MoshDiscoveryDecision.Untrusted(facts.version, MoshExtensionTrustReason.SERVICE_PROCESS_MISMATCH)
        }
        return MoshDiscoveryDecision.Trusted(facts.version)
    }

}

internal sealed interface MoshNegotiationDecision {
    data class Compatible(val protocol: MoshNegotiatedProtocol) : MoshNegotiationDecision

    data class Incompatible(
        val extensionApiVersion: Int?,
        val reason: MoshExtensionCompatibilityReason,
    ) : MoshNegotiationDecision
}

internal object MoshProtocolNegotiator {
    fun negotiate(
        extensionApiVersion: Int,
        capabilities: MoshCapabilities,
    ): MoshNegotiationDecision {
        if (extensionApiVersion < 1 || extensionApiVersion != capabilities.maximumApiVersion) {
            return MoshNegotiationDecision.Incompatible(
                extensionApiVersion.takeIf { it >= 1 },
                MoshExtensionCompatibilityReason.INVALID_API_VERSION,
            )
        }
        if (capabilities.modelVersion != MoshApi.MODEL_VERSION) {
            return MoshNegotiationDecision.Incompatible(
                extensionApiVersion,
                MoshExtensionCompatibilityReason.MODEL_VERSION_MISMATCH,
            )
        }
        if (MoshApi.PROTOCOL_VERSION !in capabilities.minimumApiVersion..capabilities.maximumApiVersion) {
            return MoshNegotiationDecision.Incompatible(
                extensionApiVersion,
                MoshExtensionCompatibilityReason.API_RANGE_MISMATCH,
            )
        }
        if (capabilities.capabilityFlags and (MoshCapability.IPV4 or MoshCapability.IPV6) == 0L) {
            return MoshNegotiationDecision.Incompatible(
                extensionApiVersion,
                MoshExtensionCompatibilityReason.CAPABILITY_MISMATCH,
            )
        }
        return MoshNegotiationDecision.Compatible(
            MoshNegotiatedProtocol(
                extensionApiVersion = extensionApiVersion,
                negotiatedApiVersion = MoshApi.PROTOCOL_VERSION,
                capabilityFlags = capabilities.capabilityFlags,
                maximumConcurrentSessions = capabilities.maximumConcurrentSessions,
            ),
        )
    }
}
