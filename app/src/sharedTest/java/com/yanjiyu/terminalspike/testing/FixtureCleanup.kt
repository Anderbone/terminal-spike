package com.yanjiyu.terminalspike.testing

import java.io.Closeable

/** Register each acquired fixture immediately, before the next setup step can fail. */
internal class FixtureCleanup : Closeable {
    private val actions = mutableListOf<() -> Unit>()

    fun onClose(action: () -> Unit) {
        actions += action
    }

    override fun close() {
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
