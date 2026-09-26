package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.Session
import com.yanjiyu.terminalspike.core.model.PortForwardDirection
import com.yanjiyu.terminalspike.core.model.PortForwardRule

/** Never includes JSch's endpoint-bearing exception message in user-visible failures. */
internal class PortForwardStartException(val ruleNumber: Int) : Exception(
    "Port forward $ruleNumber could not start. Check the listen port and server forwarding permissions.",
)

/** The authenticated session owns all listeners. Its failure/close path releases partial setup. */
internal fun Session.startPortForwards(rules: List<PortForwardRule>) {
    rules.forEachIndexed { index, rule ->
        if (!rule.enabled) return@forEachIndexed
        try {
            when (rule.direction) {
                PortForwardDirection.LOCAL -> setPortForwardingL(
                    rule.bindAddress, rule.listenPort, rule.destinationHost, rule.destinationPort,
                    null, 15_000,
                )
                PortForwardDirection.REMOTE -> setPortForwardingR(
                    rule.bindAddress, rule.listenPort, rule.destinationHost, rule.destinationPort,
                )
            }
        } catch (_: Exception) {
            throw PortForwardStartException(index + 1)
        }
    }
}
