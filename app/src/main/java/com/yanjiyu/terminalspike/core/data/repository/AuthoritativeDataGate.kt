package com.yanjiyu.terminalspike.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide readiness and serialization boundary for Room plus DataStore authority. */
internal sealed interface AuthoritativeDataState {
    data object Checking : AuthoritativeDataState

    data object RecoveryRequired : AuthoritativeDataState

    data class Ready(val generation: Long) : AuthoritativeDataState {
        init {
            require(generation >= 0L) { "Authoritative generation cannot be negative." }
        }
    }

    /** Contains no exception text, record identifiers, endpoints, or other persisted data. */
    data class RecoveryFailed(val reason: AuthoritativeRecoveryFailure) : AuthoritativeDataState
}

internal enum class AuthoritativeRecoveryFailure {
    RECOVERY_UNAVAILABLE,
}

internal class AuthoritativeDataUnavailableException(
    val reason: AuthoritativeRecoveryFailure,
) : IllegalStateException("Authoritative app data is unavailable until recovery succeeds.")

/**
 * One application-owned gate shared by every compatibility repository and backup import.
 *
 * Reads hold the same mutex as mutations so nobody can observe the cross-store interval between
 * Room and DataStore commits. A mutation owner advances the generation immediately after its
 * durable commit; later failures therefore invalidate older process-local projections as well.
 */
internal class AuthoritativeDataGate {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AuthoritativeDataState>(
        AuthoritativeDataState.Checking,
    )
    private var generation = 0L

    val state: StateFlow<AuthoritativeDataState> = mutableState.asStateFlow()

    /** Waits through startup checking/recovery and fails closed after a recovery error. */
    suspend fun awaitReady(): Long = state.first { candidate ->
        candidate is AuthoritativeDataState.Ready ||
            candidate is AuthoritativeDataState.RecoveryFailed
    }.requireReadyGeneration()

    /** Runs one authoritative read without overlapping startup recovery or a mutation. */
    suspend fun <T> withRead(block: suspend (generation: Long) -> T): T {
        while (true) {
            awaitReady()
            val result = mutex.withLock {
                when (val current = mutableState.value) {
                    is AuthoritativeDataState.Ready -> ReadAttempt.Completed(
                        block(current.generation),
                    )
                    is AuthoritativeDataState.RecoveryFailed -> throw current.toException()
                    AuthoritativeDataState.Checking,
                    AuthoritativeDataState.RecoveryRequired,
                    -> ReadAttempt.Retry
                }
            }
            when (result) {
                is ReadAttempt.Completed -> return result.value
                ReadAttempt.Retry -> Unit
            }
        }
    }

    /**
     * Serializes a mutation with all authoritative reads. Call [Mutation.markCommitted] exactly
     * once, immediately after the durable commit succeeds. Cancellation or failure before that
     * point leaves the generation unchanged.
     */
    suspend fun <T> withMutation(block: suspend Mutation.() -> T): T {
        while (true) {
            awaitReady()
            val result = mutex.withLock {
                when (val current = mutableState.value) {
                    is AuthoritativeDataState.Ready -> {
                        check(current.generation < Long.MAX_VALUE) {
                            "Authoritative generation is exhausted."
                        }
                        val mutation = Mutation(current.generation)
                        try {
                            MutationAttempt.Completed(block(mutation))
                        } finally {
                            mutation.close()
                        }
                    }
                    is AuthoritativeDataState.RecoveryFailed -> throw current.toException()
                    AuthoritativeDataState.Checking,
                    AuthoritativeDataState.RecoveryRequired,
                    -> MutationAttempt.Retry
                }
            }
            when (result) {
                is MutationAttempt.Completed -> return result.value
                MutationAttempt.Retry -> Unit
            }
        }
    }

    /**
     * Performs the process-start marker check and exact recovery while holding the authority lock.
     * A failed attempt remains fail-closed; calling this method again is the explicit Retry path.
     * [recoverPending] must return only after the marker has been cleared and authoritative stores
     * have committed, and must return false if the expected marker could not be recovered.
     */
    suspend fun resolveStartup(
        hasPendingRecovery: () -> Boolean,
        recoverPending: suspend () -> Boolean,
    ) {
        mutex.withLock {
            if (mutableState.value is AuthoritativeDataState.Ready) return
            mutableState.value = AuthoritativeDataState.Checking
            try {
                if (hasPendingRecovery()) {
                    mutableState.value = AuthoritativeDataState.RecoveryRequired
                    check(recoverPending()) { "Pending authoritative recovery was not applied." }
                    generation = generation.incremented()
                }
                mutableState.value = AuthoritativeDataState.Ready(generation)
            } catch (cancelled: CancellationException) {
                mutableState.value = AuthoritativeDataState.RecoveryFailed(
                    AuthoritativeRecoveryFailure.RECOVERY_UNAVAILABLE,
                )
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = AuthoritativeDataState.RecoveryFailed(
                    AuthoritativeRecoveryFailure.RECOVERY_UNAVAILABLE,
                )
            }
        }
    }

    internal inner class Mutation internal constructor(startGeneration: Long) {
        private var active = true
        private var committed = false
        private var recoveryRequired = false
        var generation: Long = startGeneration
            private set

        fun markCommitted(): Long {
            check(active) { "An authoritative mutation may commit only while it owns the gate." }
            check(!recoveryRequired) {
                "A mutation requiring recovery cannot make authoritative data ready."
            }
            check(!committed) { "An authoritative mutation may commit only once." }
            committed = true
            this@AuthoritativeDataGate.generation =
                this@AuthoritativeDataGate.generation.incremented()
            generation = this@AuthoritativeDataGate.generation
            mutableState.value = AuthoritativeDataState.Ready(generation)
            return generation
        }

        /** Keeps all later reads and writes closed until startup recovery is retried. */
        fun requireRecovery() {
            check(active) { "Recovery may be required only while a mutation owns the gate." }
            recoveryRequired = true
            mutableState.value = AuthoritativeDataState.RecoveryRequired
        }

        internal fun close() {
            active = false
        }
    }

    private fun Long.incremented(): Long {
        check(this < Long.MAX_VALUE) { "Authoritative generation is exhausted." }
        return this + 1L
    }

    private sealed interface ReadAttempt<out T> {
        data class Completed<T>(val value: T) : ReadAttempt<T>
        data object Retry : ReadAttempt<Nothing>
    }

    private sealed interface MutationAttempt<out T> {
        data class Completed<T>(val value: T) : MutationAttempt<T>
        data object Retry : MutationAttempt<Nothing>
    }
}

private fun AuthoritativeDataState.requireReadyGeneration(): Long = when (this) {
    is AuthoritativeDataState.Ready -> generation
    is AuthoritativeDataState.RecoveryFailed -> throw toException()
    AuthoritativeDataState.Checking,
    AuthoritativeDataState.RecoveryRequired,
    -> error("An unresolved authoritative state cannot complete awaitReady().")
}

private fun AuthoritativeDataState.RecoveryFailed.toException() =
    AuthoritativeDataUnavailableException(reason)
