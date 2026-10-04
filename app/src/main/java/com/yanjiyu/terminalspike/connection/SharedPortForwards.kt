package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.PortForwardRule

/** Only already authenticated sessions for the same SSH endpoint/account may share a listener. */
internal data class PortForwardEndpoint(val host: String, val port: Int, val username: String)

internal interface PortForwardTransport {
    val endpoint: PortForwardEndpoint
    val connected: Boolean
    fun retain()
    fun release()
    fun start(rule: PortForwardRule)
    fun stop(rule: PortForwardRule)
}

/** Process-wide listener leases; terminal channels still have independent lifetimes. */
internal class SharedPortForwards {
    private data class Key(val endpoint: PortForwardEndpoint, val rule: PortForwardRule)
    private class Entry(val transport: PortForwardTransport, var users: Int = 0)
    private val entries = mutableMapOf<Key, Entry>()

    @Synchronized
    fun acquire(transport: PortForwardTransport, rules: List<PortForwardRule>): AutoCloseable {
        val acquired = mutableListOf<Pair<Key, Entry>>()
        try {
            rules.forEachIndexed { index, rule ->
                if (!rule.enabled) return@forEachIndexed
                val key = Key(transport.endpoint, rule)
                try {
                    val entry = entries[key]?.takeIf { it.transport.connected } ?: run {
                        transport.start(rule)
                        transport.retain()
                        Entry(transport).also { entries[key] = it }
                    }
                    entry.users++
                    acquired += key to entry
                } catch (_: Exception) {
                    throw PortForwardStartException(index + 1)
                }
            }
        } catch (failure: Exception) {
            release(acquired)
            throw failure
        }
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    release(acquired)
                }
            }
        }
    }

    private fun release(acquired: List<Pair<Key, Entry>>) {
        acquired.asReversed().forEach { (key, entry) ->
            if (--entry.users == 0) {
                if (entries[key] === entry) entries.remove(key)
                runCatching { entry.transport.stop(key.rule) }
                entry.transport.release()
            }
        }
    }

    companion object {
        val process = SharedPortForwards()
    }
}
