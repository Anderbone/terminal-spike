package com.yanjiyu.terminalspike

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class AppContainerTest {
    @Test
    fun applicationOwnsOneInstanceOfEveryDiskAndCredentialBoundary() {
        val application = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()

        assertSame(application.container.database, application.container.database)
        assertSame(application.container.settings, application.container.settings)
        assertSame(
            application.container.authoritativeData,
            application.container.authoritativeData,
        )
        assertSame(
            application.container.backupRecoveryMarkers,
            application.container.backupRecoveryMarkers,
        )
        assertSame(application.container.credentialStore, application.container.credentialStore)
        assertSame(
            application.container.credentialRepository,
            application.container.credentialRepository,
        )
        assertSame(
            application.container.terminalDataRepository,
            application.container.terminalDataRepository,
        )
        assertSame(
            application.container.knownHostManager,
            application.container.knownHostManager,
        )
        assertSame(
            application.container.legacyMigrationCoordinator,
            application.container.legacyMigrationCoordinator,
        )
    }

    @Test
    fun explicitCutoverMigrationIsSingleFlightWithoutRunningAgainstDeviceData() {
        val application = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val pausedScope = CoroutineScope(SupervisorJob() + PausedDispatcher)
        val container = AppContainer(application, applicationScope = pausedScope)

        val first = container.startLegacyMigrationForCutover()
        val second = container.startLegacyMigrationForCutover()

        assertSame(first, second)
        first.cancel()
        pausedScope.cancel()
    }

    @Test
    fun startupResolutionIsSingleFlightOnTheApplicationScope() {
        val application = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val pausedScope = CoroutineScope(SupervisorJob() + PausedDispatcher)
        val container = AppContainer(application, applicationScope = pausedScope)

        val first = container.resolveStartup()
        val second = container.resolveStartup()

        assertSame(first, second)
        first.cancel()
        pausedScope.cancel()
    }

    private object PausedDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }
}
