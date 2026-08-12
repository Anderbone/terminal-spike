package com.yanjiyu.terminalspike.connection

import java.io.File

data class KnownHostSummary(
    /** Host alias used by the existing UI: `host` for port 22, `[host]:port` otherwise. */
    val host: String,
    val algorithm: String,
    val sha256Fingerprint: String,
    val port: Int = DEFAULT_SSH_PORT,
) {
    private companion object {
        const val DEFAULT_SSH_PORT = 22
    }
}

/** Process-owned management and JSch assembly surface for one authoritative trust store. */
class KnownHostManager internal constructor(
    private val trustStore: KnownHostTrustStore,
) {
    /** Compatibility constructor retained while the legacy ViewModel is cut over by its owner. */
    constructor(file: () -> File) : this(
        FileKnownHostTrustStore(KnownHostStores.forFile(file())),
    )

    fun list(): List<KnownHostSummary> = trustStore.list()
        .map { entry -> entry.toSummary() }
        .sortedWith(
            compareBy<KnownHostSummary>(KnownHostSummary::host)
                .thenBy(KnownHostSummary::port)
                .thenBy(KnownHostSummary::algorithm),
        )

    /** Endpoint-wide forget; [algorithm] is retained only for source compatibility with old UI. */
    @Suppress("UNUSED_PARAMETER")
    fun remove(host: String, algorithm: String) {
        trustStore.forgetEndpoint(KnownHostEndpoint.parseDisplayHost(host))
    }

    fun forget(host: String, port: Int): Int =
        trustStore.forgetEndpoint(KnownHostEndpoint.create(host, port))

    fun replace(
        host: String,
        port: Int,
        algorithm: String,
        key: ByteArray,
    ): KnownHostSummary {
        val endpoint = KnownHostEndpoint.create(host, port)
        trustStore.replaceEndpoint(endpoint, algorithm, key)
        return requireNotNull(
            trustStore.list(endpoint).firstOrNull { entry ->
                entry.algorithm == algorithm && entry.copyKey().contentEquals(key)
            },
        ) { "The replacement host key was not visible after its transaction committed." }
            .toSummary()
    }

    internal fun verifyingRepository(
        host: String,
        port: Int,
        @Suppress("UNUSED_PARAMETER")
        displayHost: String,
        onPrompt: (HostIdentityPrompt) -> Unit,
    ): VerifyingHostKeyRepository = VerifyingHostKeyRepository(
        store = trustStore,
        endpoint = KnownHostEndpoint.create(host, port),
        onPrompt = onPrompt,
    )

    private fun TrustedKnownHostKey.toSummary() = KnownHostSummary(
        host = endpoint.displayHost(),
        algorithm = algorithm,
        sha256Fingerprint = sha256Fingerprint,
        port = endpoint.port,
    )
}
