package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch

internal class VerifyingHostKeyRepository(
    private val store: KnownHostStore,
    private val displayHost: String,
    private val onPrompt: (HostKeyPrompt) -> Unit,
) : HostKeyRepository {
    private val promptLock = Any()
    private var pendingDecision: HostKeyDecision? = null

    @Volatile
    var failure: HostKeyFailure? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val candidate = HostKey(host, key)
        val knownForHost = store.entries(host)
        val matchingType = knownForHost.filter { it.algorithm == candidate.type }
        if (matchingType.any { it.key.contentEquals(key) }) return HostKeyRepository.OK
        if (matchingType.isNotEmpty()) {
            failure = HostKeyFailure.CHANGED
            return HostKeyRepository.CHANGED
        }

        val decision = HostKeyDecision()
        synchronized(promptLock) {
            pendingDecision?.resolve(false)
            pendingDecision = decision
        }
        onPrompt(
            HostKeyPrompt(
                host = displayHost,
                algorithm = candidate.type,
                sha256Fingerprint = fingerprint(key),
            ),
        )
        val accepted = decision.await()
        synchronized(promptLock) {
            if (pendingDecision === decision) pendingDecision = null
        }
        if (!accepted) {
            failure = HostKeyFailure.REJECTED
            return HostKeyRepository.NOT_INCLUDED
        }
        return runCatching {
            store.trust(host, candidate.type, key)
            HostKeyRepository.OK
        }.getOrElse {
            failure = HostKeyFailure.STORE_FAILED
            HostKeyRepository.NOT_INCLUDED
        }
    }

    fun answerPrompt(accept: Boolean) {
        synchronized(promptLock) { pendingDecision }?.resolve(accept)
    }

    fun cancelPrompt() = answerPrompt(false)

    override fun add(hostkey: HostKey, userinfo: UserInfo?) {
        store.trust(hostkey.host, hostkey.type, Base64.getDecoder().decode(hostkey.key))
    }

    override fun remove(host: String, type: String?) {
        store.remove(host, type)
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        store.remove(host, type, key)
    }

    override fun getKnownHostsRepositoryID(): String = "app-private known_hosts"

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        store.entries(host, type)
            .map { entry -> HostKey(entry.host, entry.key) }
            .toTypedArray()

    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }

    private class HostKeyDecision {
        private val latch = CountDownLatch(1)

        @Volatile
        private var accepted = false

        fun resolve(value: Boolean) {
            accepted = value
            latch.countDown()
        }

        fun await(): Boolean {
            latch.await()
            return accepted
        }
    }
}

internal enum class HostKeyFailure {
    CHANGED,
    REJECTED,
    STORE_FAILED,
}
