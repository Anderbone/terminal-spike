package com.yanjiyu.terminalspike.connection

import java.nio.CharBuffer

/** Internal-only outcome used to distinguish an absent command from a rejected transport write. */
internal enum class SshStartupDispatchResult {
    NOT_CONFIGURED,
    SENT,
    REJECTED,
    ALREADY_DISPATCHED,
}

/** One instance belongs to one shell attempt, including automatic reconnect attempts. */
internal class OneShotSshStartupDispatcher {
    private val lock = Any()
    private var dispatched = false

    fun dispatch(
        command: String?,
        sendOnce: (ByteArray) -> Boolean,
    ): SshStartupDispatchResult {
        val isFirstDispatch = synchronized(lock) {
            if (dispatched) false else true.also { dispatched = true }
        }
        if (!isFirstDispatch) return SshStartupDispatchResult.ALREADY_DISPATCHED
        return dispatchSshStartupCommand(command, sendOnce)
    }
}

/**
 * Sends saved startup input at most once for the caller's fresh shell.
 *
 * The command is never interpreted locally. Existing terminal line endings at the very end are
 * collapsed to one carriage return, matching the terminal Enter key. Every encoded buffer owned by
 * this boundary is wiped after the transport has either copied or rejected it.
 */
internal fun dispatchSshStartupCommand(
    command: String?,
    sendOnce: (ByteArray) -> Boolean,
): SshStartupDispatchResult {
    if (command.isNullOrBlank()) return SshStartupDispatchResult.NOT_CONFIGURED
    val input = encodeSshStartupInput(command)
    return try {
        if (sendOnce(input)) SshStartupDispatchResult.SENT else SshStartupDispatchResult.REJECTED
    } finally {
        input.fill(0)
    }
}

internal fun encodeSshStartupInput(command: String): ByteArray {
    require(command.isNotBlank()) { "SSH startup input must not be blank." }
    var contentEnd = command.length
    while (contentEnd > 0 && command[contentEnd - 1] in "\r\n") contentEnd -= 1

    val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(command, 0, contentEnd))
    return try {
        ByteArray(encoded.remaining() + 1).also { input ->
            encoded.get(input, 0, input.lastIndex)
            input[input.lastIndex] = TERMINAL_ENTER_BYTE
        }
    } finally {
        if (encoded.hasArray()) encoded.array().fill(0)
    }
}

private const val TERMINAL_ENTER_BYTE: Byte = 0x0d
