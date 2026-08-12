package com.yanjiyu.terminalspike.connection.mosh

data class MoshExtensionVersion(
    val versionCode: Long,
    val versionName: String?,
)

data class MoshNegotiatedProtocol(
    val extensionApiVersion: Int,
    val negotiatedApiVersion: Int,
    val capabilityFlags: Long,
    val maximumConcurrentSessions: Int,
)

enum class MoshExtensionTrustReason {
    SERVICE_MISSING,
    SERVICE_NOT_EXPORTED,
    SERVICE_PERMISSION_MISMATCH,
    BIND_PERMISSION_NOT_SIGNATURE_PROTECTED,
    SIGNER_INFORMATION_MISSING,
    SIGNER_MISMATCH,
}

enum class MoshExtensionCompatibilityReason {
    INVALID_API_VERSION,
    API_RANGE_MISMATCH,
    MODEL_VERSION_MISMATCH,
    CAPABILITY_MISMATCH,
    INVALID_EXTENSION_RESPONSE,
}

enum class MoshExtensionError {
    PACKAGE_QUERY_FAILED,
    BIND_REJECTED,
    BIND_TIMEOUT,
    NULL_BINDING,
    WRONG_BINDER,
    BINDER_DIED,
    REMOTE_FAILURE,
    CLIENT_CLOSED,
}

sealed interface MoshExtensionStatus {
    data object Checking : MoshExtensionStatus

    data object Absent : MoshExtensionStatus

    data class Disabled(
        val version: MoshExtensionVersion,
    ) : MoshExtensionStatus

    data class Untrusted(
        val version: MoshExtensionVersion,
        val reason: MoshExtensionTrustReason,
    ) : MoshExtensionStatus

    data class Incompatible(
        val version: MoshExtensionVersion,
        val extensionApiVersion: Int?,
        val reason: MoshExtensionCompatibilityReason,
    ) : MoshExtensionStatus

    data class Available(
        val version: MoshExtensionVersion,
        val protocol: MoshNegotiatedProtocol,
    ) : MoshExtensionStatus

    data class Error(
        val version: MoshExtensionVersion?,
        val reason: MoshExtensionError,
    ) : MoshExtensionStatus
}

enum class MoshClientFailure {
    EXTENSION_UNAVAILABLE,
    INVALID_REQUEST,
    CAPABILITY_MISMATCH,
    INVALID_EXTENSION_RESPONSE,
    REMOTE_FAILURE,
    CLIENT_CLOSED,
}

sealed interface MoshClientResult<out T> {
    data class Success<T>(val value: T) : MoshClientResult<T>

    data class Failure(val reason: MoshClientFailure) : MoshClientResult<Nothing>
}
