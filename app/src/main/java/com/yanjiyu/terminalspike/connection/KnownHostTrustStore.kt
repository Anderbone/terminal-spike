package com.yanjiyu.terminalspike.connection

import android.os.Looper
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.repository.KnownHostCheck
import com.yanjiyu.terminalspike.core.data.repository.KnownHostConditionalReplacement
import com.yanjiyu.terminalspike.core.data.repository.KnownHostRepository
import com.yanjiyu.terminalspike.core.data.repository.KnownHostSave
import com.yanjiyu.terminalspike.core.model.KnownHost as RoomKnownHost
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Canonical endpoint identity used for every host-key decision. */
internal class KnownHostEndpoint private constructor(
    val host: String,
    val port: Int,
) {
    fun displayHost(): String = if (port == DEFAULT_SSH_PORT) host else "[$host]:$port"

    companion object {
        fun create(host: String, port: Int): KnownHostEndpoint {
            val canonicalHost = host.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT)
            require(canonicalHost.isNotBlank()) { "Known-host endpoint must not be blank." }
            require(canonicalHost.none { it.isWhitespace() || it.isISOControl() }) {
                "Known-host endpoint contains unsupported characters."
            }
            require(port in 1..65_535) { "Known-host endpoint port is outside the valid range." }
            return KnownHostEndpoint(canonicalHost, port)
        }

        fun parseDisplayHost(value: String): KnownHostEndpoint {
            val trimmed = value.trim()
            if (trimmed.startsWith('[')) {
                val closingBracket = trimmed.lastIndexOf(']')
                if (closingBracket > 0 && closingBracket + 1 < trimmed.length &&
                    trimmed[closingBracket + 1] == ':'
                ) {
                    val parsedPort = trimmed.substring(closingBracket + 2).toIntOrNull()
                    if (parsedPort != null) {
                        return create(trimmed.substring(1, closingBracket), parsedPort)
                    }
                }
            }
            return create(trimmed, DEFAULT_SSH_PORT)
        }

        private const val DEFAULT_SSH_PORT = 22
    }
}

internal class TrustedKnownHostKey(
    val endpoint: KnownHostEndpoint,
    val algorithm: String,
    key: ByteArray,
) {
    private val storedKey = key.copyOf()

    val sha256Fingerprint: String = keyFingerprint(storedKey)

    fun copyKey(): ByteArray = storedKey.copyOf()
}

internal sealed interface KnownHostTrustCheck {
    data object Trusted : KnownHostTrustCheck

    data object UnknownEndpoint : KnownHostTrustCheck

    /** Exact endpoint-wide snapshot captured by the authoritative check transaction. */
    data class Changed(val trustedKeys: List<TrustedKnownHostKey>) : KnownHostTrustCheck {
        init {
            require(trustedKeys.isNotEmpty()) { "A changed endpoint must have saved host keys." }
        }
    }
}

internal enum class ConditionalHostKeyReplacementResult {
    REPLACED,
    STALE,
}

/**
 * Synchronous because JSch's HostKeyRepository API is synchronous. Implementations may block only
 * a transport/background caller; [RoomKnownHostTrustStore] rejects Android-main-thread calls.
 */
internal interface KnownHostTrustStore {
    fun check(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): KnownHostTrustCheck

    fun trustFirst(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): FirstHostKeyTrustResult

    fun replaceEndpoint(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): Int

    fun replaceEndpointIfUnchanged(
        endpoint: KnownHostEndpoint,
        expectedTrustedKeys: List<TrustedKnownHostKey>,
        algorithm: String,
        key: ByteArray,
    ): ConditionalHostKeyReplacementResult

    fun forgetEndpoint(endpoint: KnownHostEndpoint): Int

    fun list(endpoint: KnownHostEndpoint? = null): List<TrustedKnownHostKey>
}

/** Gate that completes only after retained legacy trust has been imported or proved absent. */
internal fun interface KnownHostAuthorityGate {
    suspend fun awaitAuthority()
}

/**
 * Blocking adapter from JSch's callback contract to the suspend Room repository.
 *
 * There is deliberately no legacy-file dependency or fallback here. Every operation first awaits
 * the migration/cutover gate, then uses only Room for the lifetime of the process.
 */
internal class RoomKnownHostTrustStore(
    private val repository: KnownHostRepository,
    private val authorityGate: KnownHostAuthorityGate,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isMainThread: () -> Boolean = ::isAndroidMainThread,
) : KnownHostTrustStore {
    override fun check(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): KnownHostTrustCheck = offMainBlocking {
        val seenAt = checkedNow()
        when (
            val result = repository.verifyAndRecordSeen(
                presented = key.toRoomModel(endpoint, algorithm, timestamp = null),
                seenAtEpochMillis = seenAt,
            )
        ) {
            KnownHostCheck.UnknownEndpoint -> KnownHostTrustCheck.UnknownEndpoint
            is KnownHostCheck.Trusted -> KnownHostTrustCheck.Trusted
            is KnownHostCheck.Mismatch -> KnownHostTrustCheck.Changed(
                result.trustedKeys.map { trusted ->
                    TrustedKnownHostKey(
                        endpoint = KnownHostEndpoint.create(trusted.host, trusted.port),
                        algorithm = trusted.keyAlgorithm,
                        key = Base64.getDecoder().decode(trusted.publicHostKey),
                    )
                },
            )
        }
    }

    override fun trustFirst(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): FirstHostKeyTrustResult = offMainBlocking {
        when (
            val result = repository.trustIfUntrusted(
                key.toRoomModel(endpoint, algorithm, checkedNow()),
            )
        ) {
            is KnownHostSave.Saved -> FirstHostKeyTrustResult.STORED
            is KnownHostSave.AlreadyTrusted -> FirstHostKeyTrustResult.ALREADY_TRUSTED
            is KnownHostSave.Conflict -> FirstHostKeyTrustResult.CHANGED
        }
    }

    override fun replaceEndpoint(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): Int = offMainBlocking {
        repository.replaceEndpoint(
            key.toRoomModel(endpoint, algorithm, checkedNow()),
        ).removedKeyCount
    }

    override fun replaceEndpointIfUnchanged(
        endpoint: KnownHostEndpoint,
        expectedTrustedKeys: List<TrustedKnownHostKey>,
        algorithm: String,
        key: ByteArray,
    ): ConditionalHostKeyReplacementResult = offMainBlocking {
        expectedTrustedKeys.requireEndpoint(endpoint)
        val candidate = key.toRoomModel(endpoint, algorithm, checkedNow())
        val expected = expectedTrustedKeys.map { trusted ->
            trusted.copyKey().toRoomModel(
                endpoint = trusted.endpoint,
                algorithm = trusted.algorithm,
                timestamp = null,
            )
        }
        when (repository.replaceEndpointIfUnchanged(candidate, expected)) {
            is KnownHostConditionalReplacement.Replaced ->
                ConditionalHostKeyReplacementResult.REPLACED
            is KnownHostConditionalReplacement.Stale -> ConditionalHostKeyReplacementResult.STALE
        }
    }

    override fun forgetEndpoint(endpoint: KnownHostEndpoint): Int = offMainBlocking {
        repository.deleteEndpoint(endpoint.host, endpoint.port)
    }

    override fun list(endpoint: KnownHostEndpoint?): List<TrustedKnownHostKey> = offMainBlocking {
        val rows = if (endpoint == null) {
            repository.observeAll().first()
        } else {
            repository.getEndpoint(endpoint.host, endpoint.port)
        }
        rows.map { row ->
            TrustedKnownHostKey(
                endpoint = KnownHostEndpoint.create(row.host, row.port),
                algorithm = row.keyAlgorithm,
                key = Base64.getDecoder().decode(row.publicHostKey),
            )
        }
    }

    private fun checkedNow(): Long = clock.nowEpochMillis().also { timestamp ->
        require(timestamp in 0..MAX_SUPPORTED_EPOCH_MILLIS) {
            "Known-host timestamp is outside the supported range."
        }
    }

    private fun ByteArray.toRoomModel(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        timestamp: Long?,
    ): RoomKnownHost = RoomKnownHost(
        id = UUID.randomUUID().toString(),
        host = endpoint.host,
        port = endpoint.port,
        keyAlgorithm = algorithm,
        fingerprint = keyFingerprint(this),
        publicHostKey = Base64.getEncoder().encodeToString(this),
        firstSeenAtEpochMillis = timestamp,
        lastSeenAtEpochMillis = timestamp,
    )

    private fun <T> offMainBlocking(block: suspend () -> T): T {
        check(!isMainThread()) {
            "Room known-host trust must be called from the SSH transport/background thread."
        }
        return runBlocking {
            withContext(dispatcher) {
                authorityGate.awaitAuthority()
                block()
            }
        }
    }

    private companion object {
        const val MAX_SUPPORTED_EPOCH_MILLIS = 253_402_300_799_999L
    }
}

/** Compatibility adapter retained only for callers that have not completed the Room cutover. */
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

private fun List<TrustedKnownHostKey>.requireEndpoint(endpoint: KnownHostEndpoint) {
    require(isNotEmpty()) { "Changed-key replacement requires saved host keys." }
    require(all { trusted ->
        trusted.endpoint.host == endpoint.host && trusted.endpoint.port == endpoint.port
    }) { "Expected known-host keys must belong to the replacement endpoint." }
}

private fun keyFingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
}

private fun isAndroidMainThread(): Boolean =
    Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
