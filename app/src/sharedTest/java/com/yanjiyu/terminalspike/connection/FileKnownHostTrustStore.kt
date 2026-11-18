package com.yanjiyu.terminalspike.connection

import java.io.File

/** File-backed trust fixture shared by JVM and opt-in Android transport tests. */
internal class FileKnownHostTrustStore(
    private val store: KnownHostStore,
) : KnownHostTrustStore {
    override fun check(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): KnownHostTrustCheck {
        val trusted = store.entries(endpoint.displayHost())
        return when {
            trusted.any { it.algorithm == algorithm && it.key.contentEquals(key) } ->
                KnownHostTrustCheck.Trusted
            trusted.isEmpty() -> KnownHostTrustCheck.UnknownEndpoint
            else -> KnownHostTrustCheck.Changed(
                trusted.map { entry ->
                    TrustedKnownHostKey(endpoint, entry.algorithm, entry.key)
                },
            )
        }
    }

    override fun trustFirst(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): FirstHostKeyTrustResult = store.trustFirstKeyIfHostUnchanged(
        endpoint.displayHost(),
        algorithm,
        key,
    )

    override fun replaceEndpoint(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): Int = store.replaceEndpoint(endpoint.displayHost(), algorithm, key)

    override fun replaceEndpointIfUnchanged(
        endpoint: KnownHostEndpoint,
        expectedTrustedKeys: List<TrustedKnownHostKey>,
        algorithm: String,
        key: ByteArray,
    ): ConditionalHostKeyReplacementResult {
        expectedTrustedKeys.requireEndpoint(endpoint)
        return if (
            store.replaceEndpointIfUnchanged(
                host = endpoint.displayHost(),
                expectedKeys = expectedTrustedKeys.map { expected ->
                    KnownHost(
                        host = endpoint.displayHost(),
                        algorithm = expected.algorithm,
                        key = expected.copyKey(),
                    )
                },
                algorithm = algorithm,
                key = key,
            )
        ) {
            ConditionalHostKeyReplacementResult.REPLACED
        } else {
            ConditionalHostKeyReplacementResult.STALE
        }
    }

    override fun forgetEndpoint(endpoint: KnownHostEndpoint): Int =
        store.removeEndpoint(endpoint.displayHost())

    override fun list(endpoint: KnownHostEndpoint?): List<TrustedKnownHostKey> =
        store.entries(endpoint?.displayHost()).mapNotNull { entry ->
            runCatching {
                TrustedKnownHostKey(
                    endpoint = KnownHostEndpoint.parseDisplayHost(entry.host),
                    algorithm = entry.algorithm,
                    key = entry.key,
                )
            }.getOrNull()
        }
}

/** File-backed assembly is deliberately unavailable to production callers. */
internal fun fileKnownHostManager(file: () -> File): KnownHostManager =
    KnownHostManager(FileKnownHostTrustStore(KnownHostStores.forFile(file())))
