package com.yanjiyu.terminalspike.ui

/** Main-thread ownership of one switcher opening and its latest asynchronous request. */
internal class TmuxSwitcherRequestOwner {
    class Owner internal constructor(val sessionId: Long)
    class Request internal constructor(val owner: Owner)

    private var currentOwner: Owner? = null
    private var latestRequest: Request? = null
    private var operation: Request? = null

    fun open(sessionId: Long): Owner = Owner(sessionId).also {
        currentOwner = it
        latestRequest = null
        operation = null
    }

    fun close(owner: Owner) {
        if (!isCurrent(owner)) return
        currentOwner = null
        latestRequest = null
        operation = null
    }

    fun beginRequest(owner: Owner): Request? {
        if (!isCurrent(owner) || operation != null) return null
        return Request(owner).also { latestRequest = it }
    }

    /** Polling must not supersede an accepted switch/delete while it is in flight. */
    fun beginOperation(owner: Owner): Request? = beginRequest(owner)?.also { operation = it }

    fun finishOperation(request: Request) {
        if (operation === request) operation = null
    }

    fun isCurrent(owner: Owner): Boolean = currentOwner === owner

    fun isCurrent(request: Request): Boolean =
        isCurrent(request.owner) && latestRequest === request
}
