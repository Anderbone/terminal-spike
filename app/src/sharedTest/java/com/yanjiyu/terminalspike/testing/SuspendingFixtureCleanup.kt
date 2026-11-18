package com.yanjiyu.terminalspike.testing

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Teardown may suspend even when a failed child has already cancelled the fixture's parent. */
internal class SuspendingFixtureCleanup {
    private val actions = mutableListOf<suspend () -> Unit>()

    fun onClose(action: suspend () -> Unit) {
        actions += action
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        val pending = actions.asReversed().toList()
        actions.clear()
        var failure: Throwable? = null
        for (action in pending) {
            try {
                action()
            } catch (caught: Throwable) {
                val first = failure
                if (first == null) failure = caught
                else if (first !== caught) first.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }
}

internal suspend inline fun <T> withSuspendingFixtureCleanup(block: (SuspendingFixtureCleanup) -> T): T {
    val cleanup = SuspendingFixtureCleanup()
    var failure: Throwable? = null
    try {
        return block(cleanup)
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        try {
            cleanup.close()
        } catch (caught: Throwable) {
            val first = failure
            if (first == null) throw caught
            if (first !== caught) first.addSuppressed(caught)
        }
    }
}
