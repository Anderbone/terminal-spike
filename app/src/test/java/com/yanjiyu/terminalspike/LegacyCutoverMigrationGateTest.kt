package com.yanjiyu.terminalspike

import com.yanjiyu.terminalspike.core.data.migration.LegacyMigrationOutcome
import com.yanjiyu.terminalspike.core.data.migration.LegacyMigrationSourceOutcome
import com.yanjiyu.terminalspike.core.data.migration.LegacyStartupMigrationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCutoverMigrationGateTest {
    @Test
    fun successfulResultIsSingleFlightAndRemainsCached() = runTest {
        var calls = 0
        val gate = LegacyCutoverMigrationGate(backgroundScope) {
            calls += 1
            successfulResult()
        }

        val first = gate.start()
        val concurrent = gate.start()

        assertSame(first, concurrent)
        assertEquals(successfulResult(), first.await())
        assertSame(first, gate.start())
        assertEquals(1, calls)
    }

    @Test
    fun blockedResultIsClearedForSameProcessRetry() = runTest {
        var calls = 0
        val gate = LegacyCutoverMigrationGate(backgroundScope) {
            calls += 1
            if (calls == 1) blockedResult() else successfulResult()
        }

        val blocked = gate.start()
        assertEquals(blockedResult(), blocked.await())

        val retry = gate.start()
        assertNotSame(blocked, retry)
        assertEquals(successfulResult(), retry.await())
        assertEquals(2, calls)
    }

    @Test
    fun exceptionalResultIsClearedForSameProcessRetry() = runTest {
        var calls = 0
        // Match the application-owned SupervisorJob: a handled Deferred failure must not cancel
        // the test parent before the same-process retry can be observed.
        val gateScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = LegacyCutoverMigrationGate(gateScope) {
            calls += 1
            if (calls == 1) throw ExpectedMigrationFailure()
            successfulResult()
        }

        try {
            val failed = runCatching { gate.start().await() }.exceptionOrNull()
            assertTrue(failed is ExpectedMigrationFailure)

            assertEquals(successfulResult(), gate.start().await())
            assertEquals(2, calls)
        } finally {
            gateScope.cancel()
        }
    }

    @Test
    fun cutoverReadinessRequiresEveryLegacySourceToBeTerminal() {
        val terminalOutcomes = LegacyMigrationSourceOutcome.entries.filterNot { outcome ->
            outcome == LegacyMigrationSourceOutcome.BLOCKED
        }

        assertFalse(
            migrationResult(
                settings = LegacyMigrationSourceOutcome.BLOCKED,
                knownHosts = LegacyMigrationSourceOutcome.BLOCKED,
            ).isCutoverReady(),
        )
        terminalOutcomes.forEach { terminal ->
            assertFalse(
                migrationResult(
                    settings = LegacyMigrationSourceOutcome.BLOCKED,
                    knownHosts = terminal,
                ).isCutoverReady(),
            )
            assertFalse(
                migrationResult(
                    settings = terminal,
                    knownHosts = LegacyMigrationSourceOutcome.BLOCKED,
                ).isCutoverReady(),
            )
        }
        terminalOutcomes.forEach { settings ->
            terminalOutcomes.forEach { knownHosts ->
                assertTrue(
                    migrationResult(settings = settings, knownHosts = knownHosts).isCutoverReady(),
                )
            }
        }
    }

    private fun successfulResult() = LegacyStartupMigrationResult(
        userSettings = LegacyMigrationOutcome(
            sourceCode = "legacy_user_settings",
            outcome = LegacyMigrationSourceOutcome.APPLIED,
        ),
        knownHosts = LegacyMigrationOutcome(
            sourceCode = "legacy_known_hosts",
            outcome = LegacyMigrationSourceOutcome.NO_SOURCE,
        ),
    )

    private fun blockedResult() = successfulResult().copy(
        knownHosts = LegacyMigrationOutcome(
            sourceCode = "legacy_known_hosts",
            outcome = LegacyMigrationSourceOutcome.BLOCKED,
            errorCode = "known_hosts_corrupt",
        ),
    )

    private fun migrationResult(
        settings: LegacyMigrationSourceOutcome,
        knownHosts: LegacyMigrationSourceOutcome,
    ) = LegacyStartupMigrationResult(
        userSettings = LegacyMigrationOutcome(
            sourceCode = "legacy_user_settings",
            outcome = settings,
        ),
        knownHosts = LegacyMigrationOutcome(
            sourceCode = "legacy_known_hosts",
            outcome = knownHosts,
        ),
    )

    private class ExpectedMigrationFailure : Exception()
}
