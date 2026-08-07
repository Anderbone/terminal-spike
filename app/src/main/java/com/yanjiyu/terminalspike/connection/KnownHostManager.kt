package com.yanjiyu.terminalspike.connection

import java.io.File
import java.security.MessageDigest
import java.util.Base64

data class KnownHostSummary(
    val host: String,
    val algorithm: String,
    val sha256Fingerprint: String,
)

class KnownHostManager(private val file: () -> File) {
    private val store by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { KnownHostStores.forFile(file()) }

    fun list(): List<KnownHostSummary> = store.entries()
        .map { entry ->
            KnownHostSummary(
                host = entry.host,
                algorithm = entry.algorithm,
                sha256Fingerprint = fingerprint(entry.key),
            )
        }
        .sortedWith(compareBy(KnownHostSummary::host, KnownHostSummary::algorithm))

    fun remove(host: String, algorithm: String) = store.remove(host, algorithm)

    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }
}
