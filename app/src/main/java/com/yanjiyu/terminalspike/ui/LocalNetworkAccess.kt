package com.yanjiyu.terminalspike.ui

internal data class LocalNetworkActionEffect(
    val terminalSource: AppRoute? = null,
    val sessionConnection: Pair<Long, SshConnectPurpose>? = null,
    val newConnectionProfileId: Long? = null,
    val workspaceAuthenticationHostId: String? = null,
    val clearTerminalAuthentication: Boolean = false,
    val clearWorkspaceAuthentication: Boolean = false,
    val clearConnectDialog: Boolean = false,
)

internal sealed interface LocalNetworkActionDispatch {
    val id: Long

    data class RequestPermission(override val id: Long) : LocalNetworkActionDispatch

    data class Apply(
        override val id: Long,
        val effect: LocalNetworkActionEffect,
    ) : LocalNetworkActionDispatch
}

/**
 * Retains exactly one permission-gated operation across Activity recreation without persisting
 * endpoints or credentials. The owner must be cleared when its ViewModel is destroyed.
 */
internal class LocalNetworkPendingActionOwner {
    private var pending: PendingLocalNetworkAction? = null

    fun stage(
        cancel: () -> Unit,
        proceed: () -> LocalNetworkActionEffect,
    ): Boolean {
        if (pending != null) {
            cancel()
            return false
        }
        pending = PendingLocalNetworkAction(proceed = proceed, cancel = cancel)
        return true
    }

    fun resolve(granted: Boolean): LocalNetworkActionEffect? {
        val action = pending ?: return null
        pending = null
        return if (granted) {
            action.proceed()
        } else {
            action.cancel()
            null
        }
    }

    fun cancel() {
        val action = pending
        pending = null
        action?.cancel?.invoke()
    }

    internal fun hasPendingAction(): Boolean = pending != null
}

private data class PendingLocalNetworkAction(
    val proceed: () -> LocalNetworkActionEffect,
    val cancel: () -> Unit,
)

internal fun shouldRequestLocalNetworkPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
    endpoint: String,
): Boolean = sdkInt >= ANDROID_17_API_LEVEL &&
    !permissionGranted &&
    isClearlyLocalNetworkEndpoint(endpoint)

internal fun isClearlyLocalNetworkEndpoint(endpoint: String): Boolean {
    val host = endpoint.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
    if (host == "localhost" || host.endsWith(".local")) return true
    val ipv4 = host.split('.').takeIf { parts ->
        parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
    }?.map(String::toInt)
    if (ipv4 != null) {
        return ipv4[0] == 10 ||
            ipv4[0] == 127 ||
            (ipv4[0] == 169 && ipv4[1] == 254) ||
            (ipv4[0] == 172 && ipv4[1] in 16..31) ||
            (ipv4[0] == 192 && ipv4[1] == 168)
    }
    if (':' !in host) return false
    val address = host.substringBefore('%')
    return address == "::1" ||
        address.startsWith("fc") ||
        address.startsWith("fd") ||
        address.startsWith("fe8") ||
        address.startsWith("fe9") ||
        address.startsWith("fea") ||
        address.startsWith("feb")
}

/** Returns null when resolution fails; callers must not turn a DNS failure into a broad prompt. */
internal fun resolvedEndpointIsLocalNetwork(
    endpoint: String,
    resolve: (String) -> List<String>,
): Boolean? = runCatching {
    resolve(endpoint.trim().removeSurrounding("[", "]"))
        .any(::isClearlyLocalNetworkEndpoint)
}.getOrNull()

private const val ANDROID_17_API_LEVEL = 37
