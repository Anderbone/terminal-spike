package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionCompatibilityReason
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionError
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionTrustReason
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionVersion
import com.yanjiyu.terminalspike.mosh.api.MoshCapability

enum class MoshExtensionUiKind {
    CHECKING,
    ABSENT,
    DISABLED,
    UNTRUSTED,
    INCOMPATIBLE,
    AVAILABLE,
    ERROR,
}

data class MoshExtensionUiDetail(
    val label: UiText,
    val value: UiText,
)

data class MoshExtensionUiState(
    val kind: MoshExtensionUiKind,
    val statusLabel: UiText,
    val summary: UiText,
    val details: List<MoshExtensionUiDetail> = emptyList(),
    val verificationMessage: UiText,
    val actionLabel: UiText? = null,
    val installationHelpLabel: UiText? = null,
)

internal fun MoshExtensionStatus.toUiState(): MoshExtensionUiState = when (this) {
    MoshExtensionStatus.Checking -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.CHECKING,
        statusLabel = uiText(R.string.mosh_status_checking),
        summary = uiText(R.string.mosh_summary_checking),
        verificationMessage = uiText(R.string.mosh_verification_checking),
    )

    MoshExtensionStatus.Absent -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.ABSENT,
        statusLabel = uiText(R.string.mosh_status_not_installed),
        summary = uiText(R.string.mosh_summary_absent),
        verificationMessage = uiText(R.string.mosh_verification_absent),
        actionLabel = uiText(R.string.mosh_retry),
        installationHelpLabel = uiText(R.string.mosh_installation_help),
    )

    is MoshExtensionStatus.Disabled -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.DISABLED,
        statusLabel = uiText(R.string.mosh_status_disabled),
        summary = uiText(R.string.mosh_summary_disabled),
        details = listOf(version.toUiDetail()),
        verificationMessage = uiText(R.string.mosh_verification_disabled),
        actionLabel = uiText(R.string.mosh_retry),
    )

    is MoshExtensionStatus.Untrusted -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.UNTRUSTED,
        statusLabel = uiText(R.string.mosh_status_untrusted),
        summary = reason.summaryText(),
        details = listOf(version.toUiDetail()),
        verificationMessage = uiText(R.string.mosh_verification_untrusted),
        actionLabel = uiText(R.string.mosh_retry),
    )

    is MoshExtensionStatus.Incompatible -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.INCOMPATIBLE,
        statusLabel = uiText(R.string.mosh_status_incompatible),
        summary = reason.summaryText(),
        details = buildList {
            add(version.toUiDetail())
            extensionApiVersion?.let {
                add(
                    MoshExtensionUiDetail(
                        uiText(R.string.mosh_detail_reported_api),
                        UiText.Dynamic(it.toString()),
                    ),
                )
            }
        },
        verificationMessage = uiText(R.string.mosh_verification_incompatible),
        actionLabel = uiText(R.string.mosh_retry),
    )

    is MoshExtensionStatus.Available -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.AVAILABLE,
        statusLabel = uiText(R.string.mosh_status_available),
        summary = uiText(R.string.mosh_summary_available),
        details = listOf(
            version.toUiDetail(),
            MoshExtensionUiDetail(
                uiText(R.string.mosh_detail_extension_api),
                UiText.Dynamic(protocol.extensionApiVersion.toString()),
            ),
            MoshExtensionUiDetail(
                uiText(R.string.mosh_detail_negotiated_api),
                UiText.Dynamic(protocol.negotiatedApiVersion.toString()),
            ),
            MoshExtensionUiDetail(
                uiText(R.string.mosh_detail_capabilities),
                protocol.capabilityFlags.capabilitySummary(),
            ),
            MoshExtensionUiDetail(
                uiText(R.string.mosh_detail_maximum_sessions),
                UiText.Dynamic(protocol.maximumConcurrentSessions.toString()),
            ),
        ),
        verificationMessage = uiText(R.string.mosh_verification_available),
        actionLabel = uiText(R.string.mosh_refresh),
    )

    is MoshExtensionStatus.Error -> MoshExtensionUiState(
        kind = MoshExtensionUiKind.ERROR,
        statusLabel = uiText(R.string.mosh_status_check_failed),
        summary = reason.summaryText(),
        details = listOfNotNull(version?.toUiDetail()),
        verificationMessage = uiText(
            if (version == null) {
                R.string.mosh_verification_error_unknown_package
            } else {
                R.string.mosh_verification_error_verified_package
            },
        ),
        actionLabel = if (reason == MoshExtensionError.CLIENT_CLOSED) {
            null
        } else {
            uiText(R.string.mosh_retry)
        },
    )
}

private fun MoshExtensionVersion.toUiDetail(): MoshExtensionUiDetail = MoshExtensionUiDetail(
    label = uiText(R.string.mosh_detail_installed_version),
    value = versionName?.let { uiText(R.string.mosh_version_named, it, versionCode) }
        ?: uiText(R.string.mosh_version_code, versionCode),
)

private fun MoshExtensionTrustReason.summaryText(): UiText = uiText(
    when (this) {
        MoshExtensionTrustReason.SERVICE_MISSING -> R.string.mosh_summary_untrusted_service_missing
        MoshExtensionTrustReason.SERVICE_NOT_EXPORTED ->
            R.string.mosh_summary_untrusted_service_not_exported
        MoshExtensionTrustReason.SERVICE_PERMISSION_MISMATCH ->
            R.string.mosh_summary_untrusted_permission_mismatch
        MoshExtensionTrustReason.BIND_PERMISSION_NOT_SIGNATURE_PROTECTED ->
            R.string.mosh_summary_untrusted_permission_unprotected
        MoshExtensionTrustReason.SIGNER_INFORMATION_MISSING ->
            R.string.mosh_summary_untrusted_signer_missing
        MoshExtensionTrustReason.SIGNER_MISMATCH ->
            R.string.mosh_summary_untrusted_signer_mismatch
    },
)

private fun MoshExtensionCompatibilityReason.summaryText(): UiText = uiText(
    when (this) {
        MoshExtensionCompatibilityReason.INVALID_API_VERSION ->
            R.string.mosh_summary_incompatible_api_invalid
        MoshExtensionCompatibilityReason.API_RANGE_MISMATCH ->
            R.string.mosh_summary_incompatible_api_range
        MoshExtensionCompatibilityReason.MODEL_VERSION_MISMATCH ->
            R.string.mosh_summary_incompatible_model
        MoshExtensionCompatibilityReason.CAPABILITY_MISMATCH ->
            R.string.mosh_summary_incompatible_capability
        MoshExtensionCompatibilityReason.INVALID_EXTENSION_RESPONSE ->
            R.string.mosh_summary_incompatible_response
    },
)

private fun MoshExtensionError.summaryText(): UiText = uiText(
    when (this) {
        MoshExtensionError.PACKAGE_QUERY_FAILED -> R.string.mosh_summary_error_package_query
        MoshExtensionError.BIND_REJECTED -> R.string.mosh_summary_error_bind_rejected
        MoshExtensionError.BIND_TIMEOUT -> R.string.mosh_summary_error_bind_timeout
        MoshExtensionError.NULL_BINDING -> R.string.mosh_summary_error_null_binding
        MoshExtensionError.WRONG_BINDER -> R.string.mosh_summary_error_wrong_binder
        MoshExtensionError.BINDER_DIED -> R.string.mosh_summary_error_binder_died
        MoshExtensionError.REMOTE_FAILURE -> R.string.mosh_summary_error_remote_failure
        MoshExtensionError.CLIENT_CLOSED -> R.string.mosh_summary_error_client_closed
    },
)

private fun Long.capabilitySummary(): UiText {
    val labels = buildList {
        // Protocol names intentionally remain literal tokens rather than translated product copy.
        if (this@capabilitySummary and MoshCapability.IPV4 != 0L) add(UiText.Dynamic("IPv4"))
        if (this@capabilitySummary and MoshCapability.IPV6 != 0L) add(UiText.Dynamic("IPv6"))
        if (this@capabilitySummary and MoshCapability.NETWORK_ROAMING != 0L) {
            add(uiText(R.string.mosh_capability_network_roaming))
        }
        if (this@capabilitySummary and MoshCapability.PREDICTION_CONTROL != 0L) {
            add(uiText(R.string.mosh_capability_prediction_control))
        }
        if (this@capabilitySummary and MoshCapability.MULTIPLE_SESSIONS != 0L) {
            add(uiText(R.string.mosh_capability_multiple_sessions))
        }
        val unknownFlags = this@capabilitySummary and KNOWN_MOSH_CAPABILITIES.inv()
        if (unknownFlags != 0L) {
            add(
                uiText(
                    R.string.mosh_capability_other,
                    "0x${unknownFlags.toULong().toString(16)}",
                ),
            )
        }
    }
    return when (labels.size) {
        0 -> uiText(R.string.mosh_capability_none)
        1 -> labels.single()
        else -> UiText.Joined(labels)
    }
}

private const val KNOWN_MOSH_CAPABILITIES: Long = MoshCapability.IPV4 or
    MoshCapability.IPV6 or
    MoshCapability.NETWORK_ROAMING or
    MoshCapability.PREDICTION_CONTROL or
    MoshCapability.MULTIPLE_SESSIONS
