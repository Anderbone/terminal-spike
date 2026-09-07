package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionCompatibilityReason
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionError
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionTrustReason
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionVersion
import com.yanjiyu.terminalspike.connection.mosh.MoshNegotiatedProtocol
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshExtensionPresentationTest {
    private val version = MoshExtensionVersion(versionCode = 42, versionName = "1.0.0-mosh-1.4.0")

    @Test
    fun allProtocolStatesExposeResourceBackedCopyAndRealActions() {
        val presentations = listOf(
            MoshExtensionStatus.Checking,
            MoshExtensionStatus.Absent,
            MoshExtensionStatus.Disabled(version),
            MoshExtensionStatus.Untrusted(version, MoshExtensionTrustReason.UID_MISMATCH),
            MoshExtensionStatus.Incompatible(
                version,
                extensionApiVersion = 2,
                reason = MoshExtensionCompatibilityReason.API_RANGE_MISMATCH,
            ),
            availableStatus(),
            MoshExtensionStatus.Error(version, MoshExtensionError.BINDER_DIED),
        ).map { it.toUiState() }

        assertEquals(MoshExtensionUiKind.entries, presentations.map { it.kind })
        assertEquals(presentations.size, presentations.map { it.statusLabel }.toSet().size)
        assertTrue(presentations.all { it.statusLabel is UiText.Resource })
        assertTrue(presentations.all { it.summary is UiText.Resource })
        assertTrue(presentations.all { it.verificationMessage is UiText.Resource })
        assertNull(presentations.single { it.kind == MoshExtensionUiKind.CHECKING }.actionLabel)
        assertEquals(
            uiText(R.string.mosh_refresh),
            presentations.single { it.kind == MoshExtensionUiKind.AVAILABLE }.actionLabel,
        )
        assertEquals(
            uiText(R.string.mosh_installation_help),
            presentations.single { it.kind == MoshExtensionUiKind.ABSENT }.installationHelpLabel,
        )
        presentations
            .filter { it.kind != MoshExtensionUiKind.ABSENT }
            .forEach { assertNull(it.installationHelpLabel) }
        presentations
            .filter { it.kind !in setOf(MoshExtensionUiKind.CHECKING, MoshExtensionUiKind.AVAILABLE) }
            .forEach { assertEquals(uiText(R.string.mosh_retry), it.actionLabel) }
    }

    @Test
    fun absentExplainsMatchingCompanionAndKeepsRetryDiscoveryOnly() {
        val presentation = MoshExtensionStatus.Absent.toUiState()

        assertEquals(uiText(R.string.mosh_summary_absent), presentation.summary)
        assertEquals(uiText(R.string.mosh_retry), presentation.actionLabel)
        assertEquals(uiText(R.string.mosh_installation_help), presentation.installationHelpLabel)
    }

    @Test
    fun availableShowsInstalledAndNegotiatedMetadataIncludingUnknownCapabilities() {
        val presentation = availableStatus(
            flags = MoshCapability.IPV4 or
                MoshCapability.IPV6 or
                MoshCapability.NETWORK_ROAMING or
                MoshCapability.PREDICTION_CONTROL or
                MoshCapability.MULTIPLE_SESSIONS or
                (1L shl 12),
        ).toUiState()
        val details = presentation.details.associate { it.label to it.value }

        assertEquals(
            uiText(R.string.mosh_version_named, "1.0.0-mosh-1.4.0", 42L),
            details[uiText(R.string.mosh_detail_installed_version)],
        )
        assertEquals(
            UiText.Dynamic("2"),
            details[uiText(R.string.mosh_detail_extension_api)],
        )
        assertEquals(
            UiText.Dynamic("1"),
            details[uiText(R.string.mosh_detail_negotiated_api)],
        )
        assertEquals(
            UiText.Joined(
                listOf(
                    UiText.Dynamic("IPv4"),
                    UiText.Dynamic("IPv6"),
                    uiText(R.string.mosh_capability_network_roaming),
                    uiText(R.string.mosh_capability_prediction_control),
                    uiText(R.string.mosh_capability_multiple_sessions),
                    uiText(R.string.mosh_capability_other, "0x1000"),
                ),
            ),
            details[uiText(R.string.mosh_detail_capabilities)],
        )
        assertEquals(
            uiText(R.string.mosh_verification_available),
            presentation.verificationMessage,
        )
    }

    @Test
    fun everyUntrustedReasonMapsToResourceCopyBeforeBinding() {
        val expected = listOf(
            R.string.mosh_summary_untrusted_service_missing,
            R.string.mosh_summary_untrusted_service_not_exported,
            R.string.mosh_summary_untrusted_signer_mismatch,
            R.string.mosh_summary_untrusted_permission_mismatch,
        )

        assertEquals(
            expected.map { uiText(it) },
            MoshExtensionTrustReason.entries.map { reason ->
                MoshExtensionStatus.Untrusted(version, reason).toUiState().summary
            },
        )
    }

    @Test
    fun everyIncompatibilityMapsToResourceCopyAfterSignatureVerification() {
        val expected = listOf(
            R.string.mosh_summary_incompatible_api_invalid,
            R.string.mosh_summary_incompatible_api_range,
            R.string.mosh_summary_incompatible_model,
            R.string.mosh_summary_incompatible_capability,
            R.string.mosh_summary_incompatible_response,
        )

        MoshExtensionCompatibilityReason.entries.forEachIndexed { index, reason ->
            val presentation = MoshExtensionStatus.Incompatible(
                version = version,
                extensionApiVersion = 7,
                reason = reason,
            ).toUiState()

            assertEquals(uiText(expected[index]), presentation.summary)
            assertEquals(
                UiText.Dynamic("7"),
                presentation.details.associate { it.label to it.value }[
                    uiText(R.string.mosh_detail_reported_api)
                ],
            )
            assertEquals(
                uiText(R.string.mosh_verification_incompatible),
                presentation.verificationMessage,
            )
        }
    }

    @Test
    fun closedClientDoesNotOfferANonfunctionalRetry() {
        val presentation = MoshExtensionStatus.Error(
            version = null,
            reason = MoshExtensionError.CLIENT_CLOSED,
        ).toUiState()

        assertEquals(MoshExtensionUiKind.ERROR, presentation.kind)
        assertNull(presentation.actionLabel)
        assertEquals(uiText(R.string.mosh_summary_error_client_closed), presentation.summary)
        assertTrue(presentation.details.isEmpty())
    }

    @Test
    fun missingVersionNameStillShowsBoundedInstalledVersionCode() {
        val presentation = MoshExtensionStatus.Disabled(
            MoshExtensionVersion(versionCode = 9, versionName = null),
        ).toUiState()

        assertEquals(
            MoshExtensionUiDetail(
                uiText(R.string.mosh_detail_installed_version),
                uiText(R.string.mosh_version_code, 9L),
            ),
            presentation.details.single(),
        )
    }

    private fun availableStatus(
        flags: Long = MoshCapability.IPV4 or MoshCapability.NETWORK_ROAMING,
    ): MoshExtensionStatus.Available = MoshExtensionStatus.Available(
        version = version,
        protocol = MoshNegotiatedProtocol(
            extensionApiVersion = 2,
            negotiatedApiVersion = 1,
            capabilityFlags = flags,
            maximumConcurrentSessions = 4,
        ),
    )
}
