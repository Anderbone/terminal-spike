package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.connection.SshAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class HostConnectionTerminalTypeTest {
    @Test
    fun stagedTestCarriesTheResolvedTerminalTypeToAuthenticationBoundary() = runTest {
        var capturedTerminalType: String? = null
        val tester = StagedHostConnectionTester(
            object : HostConnectionTestProbe {
                override suspend fun resolve(host: String) = Unit

                override suspend fun connectTcp(host: String, port: Int) = Unit

                override suspend fun authenticate(
                    spec: HostConnectionTestSpec,
                    interaction: HostConnectionTestInteraction,
                ): HostConnectionTestLease {
                    capturedTerminalType = spec.terminalType
                    return HostConnectionTestLease { }
                }
            },
        )

        val result = tester.test(
            HostConnectionTestSpec(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.Password(byteArrayOf(1)),
                terminalType = "xterm-direct",
                startupCommandConfigured = true,
            ),
        )

        assertTrue(result is HostConnectionTestResult.Success)
        assertTrue((result as HostConnectionTestResult.Success).startupCommandSkipped)
        assertEquals("xterm-direct", capturedTerminalType)
    }
}
