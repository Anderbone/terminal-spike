package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortForwardRuleTest {
    private val local = PortForwardRule(listenPort = 8080, destinationPort = 80)

    @Test fun storageRoundTripsIpv6RemoteAndDisabledRules() {
        val rules = listOf(local, local.copy(direction = PortForwardDirection.REMOTE,
            bindAddress = "::1", destinationHost = "2001:db8::1"), local.copy(enabled = false))
        assertEquals(rules, PortForwardRules.decode(PortForwardRules.encode(rules)))
        assertEquals(emptyList<PortForwardRule>(), PortForwardRules.decode(""))
    }

    @Test fun overlappingPortsAreRejectedButDirectionsAndDisabledRulesAreIndependent() {
        assertThrows(IllegalArgumentException::class.java) { PortForwardRules.validate(listOf(local, local)) }
        assertThrows(IllegalArgumentException::class.java) {
            PortForwardRules.validate(listOf(local, local.copy(bindAddress = "0.0.0.0")))
        }
        PortForwardRules.validate(listOf(local, local.copy(enabled = false),
            local.copy(direction = PortForwardDirection.REMOTE)))
    }

    @Test fun rejectsInvalidPortsHostsBindingsVersionsAndOversizedInput() {
        listOf(0, -1, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { local.copy(listenPort = port) }
            assertThrows(IllegalArgumentException::class.java) { local.copy(destinationPort = port) }
        }
        listOf("bad\nhost", "a\tb", "host:80", "-oProxyCommand=x").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { local.copy(destinationHost = host) }
        }
        assertThrows(IllegalArgumentException::class.java) { local.copy(bindAddress = "example.com") }
        assertThrows(IllegalArgumentException::class.java) { PortForwardRules.decode("2\tLOCAL\t127.0.0.1\t8080\tlocalhost\t80\t1") }
        assertThrows(IllegalArgumentException::class.java) { PortForwardRules.decode("x".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { PortForwardRules.validate(List(17) { local.copy(enabled = false) }) }
    }
}
