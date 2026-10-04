package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.PortForwardDirection
import com.yanjiyu.terminalspike.core.model.PortForwardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedPortForwardsTest {
    private val rule = PortForwardRule(listenPort = 8080, destinationPort = 80)

    @Test
    fun identicalWindowsShareBothDirectionsUntilLastWindowCloses() {
        PortForwardDirection.entries.forEach { direction ->
            val registry = SharedPortForwards()
            val owner = Transport()
            val second = Transport()
            val rules = listOf(rule.copy(direction = direction))
            val firstLease = registry.acquire(owner, rules)
            val secondLease = registry.acquire(second, rules)
            assertEquals(1, owner.starts)
            assertEquals(0, second.starts)
            firstLease.close()
            owner.release() // Terminal closes, but its SSH transport must stay alive.
            assertEquals(1, owner.references)
            assertEquals(0, owner.stops)
            secondLease.close()
            secondLease.close()
            assertEquals(0, owner.references)
            assertEquals(1, owner.stops)
        }
    }

    @Test
    fun failedSecondRuleRollsBackWithoutBreakingAnotherWindowsListener() {
        val registry = SharedPortForwards()
        val owner = Transport()
        val first = registry.acquire(owner, listOf(rule))
        val second = Transport().apply { failStart = true }
        val error = assertThrows(PortForwardStartException::class.java) {
            registry.acquire(second, listOf(rule, rule.copy(listenPort = 8081)))
        }
        assertEquals(2, error.ruleNumber)
        assertEquals(0, owner.stops)
        first.close()
        assertEquals(1, owner.stops)
        assertEquals(1, owner.references)
    }

    @Test
    fun differentAccountsServersAndDestinationsNeverSilentlyShare() {
        val registry = SharedPortForwards()
        val owner = Transport()
        val first = registry.acquire(owner, listOf(rule))
        val alternatives = listOf(
            Transport(PortForwardEndpoint("other.test", 22, "alice")) to rule,
            Transport(PortForwardEndpoint("example.test", 2222, "alice")) to rule,
            Transport(PortForwardEndpoint("example.test", 22, "bob")) to rule,
            Transport() to rule.copy(destinationPort = 81),
            Transport() to rule.copy(destinationHost = "other.test"),
        )
        alternatives.forEach { (transport, otherRule) ->
            transport.failStart = true // Simulate the listener still being occupied.
            assertThrows(PortForwardStartException::class.java) {
                registry.acquire(transport, listOf(otherRule))
            }
            assertEquals(1, transport.starts)
            assertEquals(1, transport.references)
        }
        first.close()
    }

    @Test
    fun disconnectedOwnerIsNotReusedAndOldLeaseCannotRemoveReplacement() {
        val registry = SharedPortForwards()
        val owner = Transport()
        val first = registry.acquire(owner, listOf(rule))
        owner.connected = false
        val replacement = Transport()
        val second = registry.acquire(replacement, listOf(rule))
        first.close()
        val thirdTransport = Transport()
        val third = registry.acquire(thirdTransport, listOf(rule))
        assertEquals(1, replacement.starts)
        assertEquals(0, thirdTransport.starts)
        second.close()
        assertEquals(0, replacement.stops)
        third.close()
        assertEquals(1, replacement.stops)
    }

    @Test
    fun partialNewSetupIsReleasedAndDisabledRulesDoNotListen() {
        val registry = SharedPortForwards()
        val transport = Transport().apply { failPort = 8081 }
        assertThrows(PortForwardStartException::class.java) {
            registry.acquire(transport, listOf(rule.copy(enabled = false), rule, rule.copy(listenPort = 8081)))
        }
        assertEquals(2, transport.starts)
        assertEquals(1, transport.stops)
        assertEquals(1, transport.references)
        registry.acquire(transport, listOf(rule)).close()
        assertEquals(3, transport.starts)
        assertEquals(2, transport.stops)
    }

    @Test
    fun simultaneousWindowsCreateOnlyOneListener() {
        val registry = SharedPortForwards()
        val transports = List(8) { Transport() }
        val executor = Executors.newFixedThreadPool(transports.size)
        val ready = CountDownLatch(transports.size)
        val start = CountDownLatch(1)
        try {
            val futures = transports.map { transport ->
                executor.submit<AutoCloseable> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    registry.acquire(transport, listOf(rule))
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val leases = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, transports.sumOf { it.starts })
            leases.forEach { it.close() }
            assertEquals(1, transports.sumOf { it.stops })
            assertEquals(transports.size, transports.sumOf { it.references })
        } finally {
            executor.shutdownNow()
        }
    }

    private class Transport(
        override val endpoint: PortForwardEndpoint = PortForwardEndpoint("example.test", 22, "alice"),
    ) : PortForwardTransport {
        override var connected = true
        var references = 1
        var starts = 0
        var stops = 0
        var failStart = false
        var failPort = 0
        override fun retain() { references++ }
        override fun release() { references-- }
        override fun start(rule: PortForwardRule) {
            starts++
            check(!failStart && rule.listenPort != failPort)
        }
        override fun stop(rule: PortForwardRule) { stops++ }
    }
}
