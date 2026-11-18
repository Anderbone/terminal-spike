package com.yanjiyu.terminalspike.core.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Serializes settings changes, including changes that span more than one persistent store. */
internal class SettingsMutationCoordinator(
    private val authority: AuthoritativeDataGate,
    private val beforeMutation: suspend () -> Unit = {},
) {
    suspend fun <T> mutate(block: suspend SettingsMutationScope.() -> T): T {
        authority.awaitReady()
        beforeMutation()
        return authority.withMutation {
            val scope = SettingsMutationScope(this)
            try {
                scope.block()
            } finally {
                scope.close()
            }
        }
    }
}

/** Sequential durable writes under one authority owner; this is not a cross-store transaction. */
internal class SettingsMutationScope internal constructor(
    private val owner: AuthoritativeDataGate.Mutation,
) {
    private var active = true
    private var committed = false

    /**
     * Finish an accepted write and publish its commit even if the UI owner is cancelled meanwhile.
     * Publish after the first successful write so a later failure cannot leave cached data current.
     * The caller must return successfully only after persistence succeeds (including row checks).
     */
    suspend fun <T> write(block: suspend () -> T): T {
        check(active) { "Settings writes require an active authority owner." }
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val result = block()
            if (!committed) {
                owner.markCommitted()
                committed = true
            }
            result
        }
    }

    internal fun close() {
        active = false
    }
}
