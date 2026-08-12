package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class VerifyingHostKeyRepository(
    private val store: KnownHostTrustStore,
    private val endpoint: KnownHostEndpoint,
    private val onPrompt: (HostIdentityPrompt) -> Unit,
) : HostKeyRepository {
    constructor(
        store: KnownHostStore,
        displayHost: String,
        onPrompt: (HostIdentityPrompt) -> Unit,
    ) : this(
        store = FileKnownHostTrustStore(store),
        endpoint = KnownHostEndpoint.parseDisplayHost(displayHost),
        onPrompt = onPrompt,
    )

    private val promptLock = Any()
    private var pendingDecision: PendingHostIdentityDecision? = null
    private val sessionTrustedKeys = mutableListOf<SessionTrustedHostKey>()
    private var retired = false

    @Volatile
    var failure: HostKeyFailure? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val candidate = HostKey(host, key)
        val trustedForSession = synchronized(promptLock) {
            if (retired) null else sessionTrustedKeys.any { trusted ->
                trusted.matches(candidate.type, key)
            }
        } ?: return HostKeyRepository.NOT_INCLUDED
        if (trustedForSession) return HostKeyRepository.OK
        val check = try {
            store.check(endpoint, candidate.type, key)
        } catch (_: Exception) {
            failure = HostKeyFailure.STORE_FAILED
            return HostKeyRepository.NOT_INCLUDED
        }
        if (isRetired()) return HostKeyRepository.NOT_INCLUDED
        when (check) {
            KnownHostTrustCheck.Trusted -> return HostKeyRepository.OK
            KnownHostTrustCheck.UnknownEndpoint -> Unit
            is KnownHostTrustCheck.Changed -> Unit
        }

        val promptToken = nextPromptToken()
        val decision = PendingHostIdentityDecision(
            promptToken = promptToken,
            allowedDecisions = when (check) {
                KnownHostTrustCheck.UnknownEndpoint -> setOf(
                    HostIdentityDecision.Reject,
                    HostIdentityDecision.TrustOnce,
                    HostIdentityDecision.TrustAndSave,
                )
                is KnownHostTrustCheck.Changed -> setOf(
                    HostIdentityDecision.Reject,
                    HostIdentityDecision.ReplaceSavedKey,
                )
                KnownHostTrustCheck.Trusted -> emptySet()
            },
        )
        val installed = synchronized(promptLock) {
            if (retired) {
                false
            } else {
                pendingDecision?.resolve(HostIdentityDecision.Reject)
                pendingDecision = decision
                true
            }
        }
        if (!installed) return HostKeyRepository.NOT_INCLUDED
        val prompt = when (check) {
            KnownHostTrustCheck.UnknownEndpoint -> HostIdentityPrompt.FirstContact(
                endpoint = endpoint.displayHost(),
                algorithm = candidate.type,
                newFingerprint = fingerprint(key),
                promptToken = promptToken,
            )
            is KnownHostTrustCheck.Changed -> HostIdentityPrompt.Changed(
                endpoint = endpoint.displayHost(),
                algorithm = candidate.type,
                previousFingerprint = selectPreviousKey(check.trustedKeys, candidate.type)
                    .sha256Fingerprint,
                newFingerprint = fingerprint(key),
                promptToken = promptToken,
            )
            KnownHostTrustCheck.Trusted -> error("Trusted host key returned before prompting.")
        }
        val published = try {
            // Linearize prompt publication with retirement. If publication starts first, retire()
            // waits for this non-blocking state callback; if retirement starts first, no prompt is
            // published after it returns.
            synchronized(promptLock) {
                if (retired || pendingDecision !== decision) {
                    false
                } else {
                    onPrompt(prompt)
                    !retired && pendingDecision === decision
                }
            }
        } catch (_: Exception) {
            decision.resolve(HostIdentityDecision.Reject)
            synchronized(promptLock) {
                if (pendingDecision === decision) pendingDecision = null
            }
            failure = HostKeyFailure.STORE_FAILED
            return HostKeyRepository.NOT_INCLUDED
        }
        if (!published) {
            decision.resolve(HostIdentityDecision.Reject)
            synchronized(promptLock) {
                if (pendingDecision === decision) pendingDecision = null
            }
            return HostKeyRepository.NOT_INCLUDED
        }
        val selectedDecision = try {
            decision.await()
        } finally {
            synchronized(promptLock) {
                if (pendingDecision === decision) pendingDecision = null
            }
        }
        if (isRetired()) return HostKeyRepository.NOT_INCLUDED
        return when (check) {
            KnownHostTrustCheck.UnknownEndpoint -> resolveFirstContact(candidate, key, selectedDecision)
            is KnownHostTrustCheck.Changed -> resolveChangedKey(
                candidate = candidate,
                key = key,
                expectedTrustedKeys = check.trustedKeys,
                decision = selectedDecision,
            )
            KnownHostTrustCheck.Trusted -> HostKeyRepository.OK
        }
    }

    private fun resolveFirstContact(
        candidate: HostKey,
        key: ByteArray,
        decision: HostIdentityDecision,
    ): Int = when (decision) {
        HostIdentityDecision.Reject,
        HostIdentityDecision.ReplaceSavedKey,
        -> {
            failure = HostKeyFailure.REJECTED
            HostKeyRepository.NOT_INCLUDED
        }
        HostIdentityDecision.TrustOnce -> synchronized(promptLock) {
            if (retired) {
                HostKeyRepository.NOT_INCLUDED
            } else {
                if (sessionTrustedKeys.none { trusted -> trusted.matches(candidate.type, key) }) {
                    sessionTrustedKeys += SessionTrustedHostKey(candidate.type, key)
                }
                HostKeyRepository.OK
            }
        }
        HostIdentityDecision.TrustAndSave -> runCatching {
            when (store.trustFirst(endpoint, candidate.type, key)) {
                FirstHostKeyTrustResult.STORED,
                FirstHostKeyTrustResult.ALREADY_TRUSTED,
                -> HostKeyRepository.OK
                FirstHostKeyTrustResult.CHANGED -> {
                    failure = HostKeyFailure.CHANGED
                    HostKeyRepository.CHANGED
                }
            }
        }.getOrElse {
            failure = HostKeyFailure.STORE_FAILED
            HostKeyRepository.NOT_INCLUDED
        }
    }

    private fun resolveChangedKey(
        candidate: HostKey,
        key: ByteArray,
        expectedTrustedKeys: List<TrustedKnownHostKey>,
        decision: HostIdentityDecision,
    ): Int {
        if (decision != HostIdentityDecision.ReplaceSavedKey) {
            failure = HostKeyFailure.CHANGED
            return HostKeyRepository.CHANGED
        }
        return runCatching {
            when (
                store.replaceEndpointIfUnchanged(
                    endpoint = endpoint,
                    expectedTrustedKeys = expectedTrustedKeys,
                    algorithm = candidate.type,
                    key = key,
                )
            ) {
                ConditionalHostKeyReplacementResult.REPLACED -> HostKeyRepository.OK
                ConditionalHostKeyReplacementResult.STALE -> {
                    failure = HostKeyFailure.CHANGED
                    HostKeyRepository.CHANGED
                }
            }
        }.getOrElse {
            failure = HostKeyFailure.STORE_FAILED
            HostKeyRepository.NOT_INCLUDED
        }
    }

    fun answerPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    ): Boolean = synchronized(promptLock) {
        if (retired) return@synchronized false
        pendingDecision
            ?.takeIf { pending -> pending.promptToken == promptToken }
            ?.resolveIfAllowed(decision)
            ?: false
    }

    /** Permanently retires this per-session verifier and resolves any synchronous JSch waiter. */
    fun retire() {
        val pending = synchronized(promptLock) {
            if (retired) return
            retired = true
            sessionTrustedKeys.forEach(SessionTrustedHostKey::clear)
            sessionTrustedKeys.clear()
            pendingDecision.also { pendingDecision = null }
        }
        pending?.resolve(HostIdentityDecision.Reject)
    }

    /** Source-compatible alias for existing transport retirement call sites. */
    fun cancelPrompt() = retire()

    /** JSch must not silently persist a key after a session-local Trust once decision. */
    override fun add(hostkey: HostKey, userinfo: UserInfo?) {
        // Persistence is performed only inside the explicit typed decisions above.
    }

    override fun remove(host: String, type: String?) {
        forgetEndpoint()
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        forgetEndpoint()
    }

    override fun getKnownHostsRepositoryID(): String = "Room known_hosts"

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = try {
        store.list(endpoint)
            .filter { entry -> type == null || entry.algorithm == type }
            .map { entry -> HostKey(entry.endpoint.displayHost(), entry.copyKey()) }
            .toTypedArray()
    } catch (_: Exception) {
        failure = HostKeyFailure.STORE_FAILED
        emptyArray()
    }

    private fun forgetEndpoint() {
        try {
            store.forgetEndpoint(endpoint)
        } catch (_: Exception) {
            failure = HostKeyFailure.STORE_FAILED
        }
    }

    private fun isRetired(): Boolean = synchronized(promptLock) { retired }

    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }

    private fun selectPreviousKey(
        trustedKeys: List<TrustedKnownHostKey>,
        offeredAlgorithm: String,
    ): TrustedKnownHostKey = trustedKeys.sortedWith(
        compareBy<TrustedKnownHostKey> { it.algorithm != offeredAlgorithm }
            .thenBy(TrustedKnownHostKey::algorithm)
            .thenBy(TrustedKnownHostKey::sha256Fingerprint),
    ).first()

    private class PendingHostIdentityDecision(
        val promptToken: Long,
        private val allowedDecisions: Set<HostIdentityDecision>,
    ) {
        private val latch = CountDownLatch(1)
        private val resolved = AtomicBoolean(false)

        @Volatile
        private var decision: HostIdentityDecision = HostIdentityDecision.Reject

        fun resolveIfAllowed(value: HostIdentityDecision): Boolean =
            value in allowedDecisions && resolve(value)

        fun resolve(value: HostIdentityDecision): Boolean {
            if (!resolved.compareAndSet(false, true)) return false
            decision = value
            latch.countDown()
            return true
        }

        fun await(): HostIdentityDecision {
            latch.await()
            return decision
        }
    }

    private class SessionTrustedHostKey(
        val algorithm: String,
        key: ByteArray,
    ) {
        private val key = key.copyOf()

        fun matches(algorithm: String, key: ByteArray): Boolean =
            this.algorithm == algorithm && this.key.contentEquals(key)

        fun clear() = key.fill(0)
    }

    private companion object {
        val promptTokens = AtomicLong(0L)

        fun nextPromptToken(): Long = promptTokens.incrementAndGet().also { token ->
            check(token > 0L) { "Host-identity prompt token space was exhausted." }
        }
    }
}

internal enum class HostKeyFailure {
    CHANGED,
    REJECTED,
    STORE_FAILED,
}
