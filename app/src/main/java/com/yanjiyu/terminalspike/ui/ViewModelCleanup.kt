package com.yanjiyu.terminalspike.ui

/** Runs the ViewModel's independent cleanup operations in their declared order. */
internal fun runViewModelCleanup(vararg actions: () -> Unit) {
    var failure: Throwable? = null
    for (action in actions) {
        try {
            action()
        } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error
            else if (error !== first) first.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
