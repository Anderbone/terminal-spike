package com.yanjiyu.terminalspike.ui

internal interface VoiceInputSession {
    fun start(languageTag: String)
    fun stop()
    fun cancel()
    fun destroy()
}

internal class VoiceInputCallbacks(
    val phase: (VoiceInputPhase) -> Unit,
    val partial: (String) -> Unit,
    val result: (String?) -> Unit,
    val failure: (VoiceInputFailure) -> Unit,
)

/** Main-thread owner. Each attempt uses a fresh backend and a listener bound to that attempt. */
internal class VoiceInputSessionOwner(
    private val recognitionAvailable: () -> Boolean,
    private val createSession: (VoiceInputCallbacks) -> VoiceInputSession,
    private val onPhase: (VoiceInputPhase) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFailure: (VoiceInputFailure) -> Unit,
) {
    private class Attempt(var session: VoiceInputSession? = null)
    private var active: Attempt? = null
    private var destroyed = false

    val available: Boolean
        get() = !destroyed && try { recognitionAvailable() } catch (_: Exception) { false }

    fun start(languageTag: String) {
        if (destroyed) return
        cancel()
        if (!available) {
            onFailure(VoiceInputFailure.UNAVAILABLE)
            return
        }
        val attempt = Attempt()
        active = attempt
        try {
            val session = createSession(
                VoiceInputCallbacks(
                    phase = { if (active === attempt) onPhase(it) },
                    partial = { if (active === attempt) onPartial(it) },
                    result = { text ->
                        finish(attempt) {
                            val transcript = text?.trim()
                            if (transcript.isNullOrEmpty()) onFailure(VoiceInputFailure.NO_MATCH)
                            else onResult(transcript)
                        }
                    },
                    failure = { failure -> finish(attempt) { onFailure(failure) } },
                ),
            )
            if (active !== attempt) {
                dispose(session, cancel = true)
                return
            }
            attempt.session = session
            onPartial("")
            if (active !== attempt) return
            onPhase(VoiceInputPhase.LISTENING)
            if (active !== attempt) return
            session.start(languageTag)
        } catch (_: Exception) {
            finish(attempt, cancel = true) { onFailure(VoiceInputFailure.UNAVAILABLE) }
        }
    }

    fun stop() {
        val attempt = active ?: return
        onPhase(VoiceInputPhase.PROCESSING)
        if (active !== attempt) return
        try {
            attempt.session?.stop()
        } catch (_: Exception) {
            finish(attempt, cancel = true) { onFailure(VoiceInputFailure.UNKNOWN) }
        }
    }

    fun cancel() {
        val attempt = active
        // Invalidate before calling providers, which can synchronously deliver late callbacks.
        active = null
        attempt?.session?.let { dispose(it, cancel = true) }
        onPartial("")
        if (active == null) onPhase(VoiceInputPhase.IDLE)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
    }

    private fun finish(attempt: Attempt, cancel: Boolean = false, deliver: () -> Unit) {
        if (active !== attempt) return
        active = null
        attempt.session?.let { dispose(it, cancel) }
        onPartial("")
        if (active != null) return
        onPhase(VoiceInputPhase.IDLE)
        if (active == null) deliver()
    }

    private fun dispose(session: VoiceInputSession, cancel: Boolean) {
        if (cancel) try { session.cancel() } catch (_: Exception) { /* Still destroy below. */ }
        try { session.destroy() } catch (_: Exception) { /* A retired backend cannot own callbacks. */ }
    }
}

internal data class VoiceInputRequest(val targetId: Long, val languageTag: String)

/** Keeps microphone permission and recognition results attached to the user's original target. */
internal class VoiceInputTargetOwner {
    private var permissionRequest: VoiceInputRequest? = null
    private var activeTargetId: Long? = null

    fun requestPermission(request: VoiceInputRequest): Boolean {
        if (permissionRequest != null) return false
        permissionRequest = request
        return true
    }

    fun takePermissionRequest(currentTargetId: Long, enabled: Boolean): VoiceInputRequest? {
        val request = permissionRequest
        permissionRequest = null
        return request?.takeIf { enabled && it.targetId == currentTargetId }
    }

    fun activate(request: VoiceInputRequest) { activeTargetId = request.targetId }

    fun ownsCallbacks(currentTargetId: Long, enabled: Boolean): Boolean =
        enabled && activeTargetId == currentTargetId

    fun invalidate() {
        permissionRequest = null
        activeTargetId = null
    }
}
