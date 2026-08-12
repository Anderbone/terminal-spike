package com.yanjiyu.terminalspike.connection.mosh

import com.yanjiyu.terminalspike.mosh.api.IMoshCallback
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal class MoshCallbackDispatcher(
    private val onTerminalSession: (UUID) -> Unit,
) : IMoshCallback.Stub() {
    private val sessions = ConcurrentHashMap<String, MutableSharedFlow<MoshSessionEvent>>()

    fun track(sessionId: UUID): Flow<MoshSessionEvent> = sessions.getOrPut(sessionId.toString()) {
        MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = CALLBACK_BUFFER_CAPACITY - 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    fun isTracked(sessionId: UUID): Boolean = sessions.containsKey(sessionId.toString())

    fun trackedSessionCount(): Int = sessions.size

    fun forget(sessionId: UUID) {
        sessions.remove(sessionId.toString())
    }

    fun failAll(errorCode: Int) {
        sessions.forEach { (sessionId, events) ->
            events.tryEmit(
                MoshSessionEvent(
                    modelVersion = MoshApi.MODEL_VERSION,
                    sessionId = sessionId,
                    state = MoshSessionState.ERROR,
                    disconnectReason = MoshDisconnectReason.NONE,
                    errorCode = errorCode,
                    redactedDetail = "Extension connection lost",
                    connectivityGeneration = 0,
                ),
            )
        }
        sessions.clear()
    }

    override fun onSessionEvent(event: MoshSessionEvent?) {
        val safeEvent = event ?: return
        val canonicalId = safeEvent.sessionId.canonicalUuidOrNull() ?: return
        sessions[canonicalId]?.tryEmit(safeEvent)
        if (
            safeEvent.state == MoshSessionState.DISCONNECTED ||
            safeEvent.state == MoshSessionState.ERROR
        ) {
            sessions.remove(canonicalId)
            onTerminalSession(UUID.fromString(canonicalId))
        }
    }

    private fun String.canonicalUuidOrNull(): String? {
        val parsed = runCatching(UUID::fromString).getOrNull() ?: return null
        return parsed.toString().takeIf { it == this }
    }

    private companion object {
        const val CALLBACK_BUFFER_CAPACITY = 8
    }
}
