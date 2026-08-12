package com.yanjiyu.terminalspike.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshStartupCommandTest {
    @Test
    fun absentOrEmptyCommandDoesNotTouchTheTransport() {
        var calls = 0

        val absent = dispatchSshStartupCommand(null) {
            calls += 1
            true
        }
        val empty = dispatchSshStartupCommand("") {
            calls += 1
            true
        }

        assertEquals(SshStartupDispatchResult.NOT_CONFIGURED, absent)
        assertEquals(SshStartupDispatchResult.NOT_CONFIGURED, empty)
        assertEquals(0, calls)
    }

    @Test
    fun commandIsUtf8WithOneTerminalEnterAndTemporaryBytesAreWiped() {
        var offered: ByteArray? = null
        var captured: ByteArray? = null

        val result = dispatchSshStartupCommand("printf 'café'\r\n\n") { bytes ->
            offered = bytes
            captured = bytes.copyOf()
            true
        }

        assertEquals(SshStartupDispatchResult.SENT, result)
        assertArrayEquals("printf 'café'\r".toByteArray(Charsets.UTF_8), captured)
        assertTrue(requireNotNull(offered).all { it == 0.toByte() })
    }

    @Test
    fun embeddedLinesArePreservedAndRejectedInputIsNeverRetried() {
        var calls = 0
        var captured: ByteArray? = null
        var offered: ByteArray? = null

        val result = dispatchSshStartupCommand("first\nsecond") { bytes ->
            calls += 1
            offered = bytes
            captured = bytes.copyOf()
            false
        }

        assertEquals(SshStartupDispatchResult.REJECTED, result)
        assertEquals(1, calls)
        assertArrayEquals("first\nsecond\r".toByteArray(), captured)
        assertTrue(requireNotNull(offered).all { it == 0.toByte() })
    }

    @Test
    fun productionAttemptPublishesConnectedThenDispatchesOncePerFreshShell() {
        val events = mutableListOf<String>()

        fun freshAttempt(label: String) = ConnectionAttempt(
            ConnectionStatePublisher { state -> events += "$label:state:$state" },
        )

        fun ConnectionAttempt.connect(label: String): SshStartupDispatchResult? =
            publishConnectedAndDispatchStartup("echo once") { bytes ->
                events += "$label:write:${bytes.toString(Charsets.UTF_8)}"
                true
            }

        val original = freshAttempt("original")
        assertEquals(SshStartupDispatchResult.SENT, original.connect("original"))
        assertEquals(
            SshStartupDispatchResult.ALREADY_DISPATCHED,
            original.connect("original"),
        )
        assertEquals(SshStartupDispatchResult.SENT, freshAttempt("reconnect").connect("reconnect"))
        assertEquals(SshStartupDispatchResult.SENT, freshAttempt("duplicate").connect("duplicate"))

        assertEquals(
            listOf(
                "original:state:Connected",
                "original:write:echo once\r",
                "reconnect:state:Connected",
                "reconnect:write:echo once\r",
                "duplicate:state:Connected",
                "duplicate:write:echo once\r",
            ),
            events,
        )
    }

    @Test
    fun synchronousConnectedObserverInputCannotOvertakeStartupInput() {
        val writes = mutableListOf<ByteArray>()
        val observerInput = byteArrayOf(1, 2, 3)
        lateinit var gate: StartupFirstInputGate
        val attempt = ConnectionAttempt(
            ConnectionStatePublisher { state ->
                if (state is ConnectionState.Connected) {
                    assertTrue(gate.offer(observerInput))
                    observerInput.fill(9)
                }
            },
        )
        gate = StartupFirstInputGate(capacity = 4) { bytes ->
            writes += bytes.copyOf()
            true
        }

        val startupResult = attempt.publishConnectedAndDispatchStartup("echo startup") { bytes ->
            writes += bytes.copyOf()
            true
        }

        assertEquals(SshStartupDispatchResult.SENT, startupResult)
        assertEquals(listOf("echo startup\r"), writes.map { it.toString(Charsets.UTF_8) })
        assertTrue(gate.open())
        assertArrayEquals("echo startup\r".toByteArray(), writes[0])
        assertArrayEquals(byteArrayOf(1, 2, 3), writes[1])
        gate.close()
    }

    @Test
    fun startupGateIsBoundedAndWipesStagedCopiesOnRejectionOrClose() {
        val rejectingGate = StartupFirstInputGate(capacity = 2) { false }
        assertTrue(rejectingGate.offer(byteArrayOf(4, 5)))
        assertTrue(rejectingGate.offer(byteArrayOf(6, 7)))
        assertFalse(rejectingGate.offer(byteArrayOf(8, 9)))
        val rejectedCopies = rejectingGate.retainedStagedInputsForTest()

        assertFalse(rejectingGate.open())
        assertTrue(rejectedCopies.all { owned -> owned.all { it == 0.toByte() } })
        assertFalse(rejectingGate.offer(byteArrayOf(10)))

        val closingGate = StartupFirstInputGate(capacity = 1) { true }
        assertTrue(closingGate.offer(byteArrayOf(11, 12)))
        val closedCopy = closingGate.retainedStagedInputsForTest().single()
        closingGate.close()

        assertTrue(closedCopy.all { it == 0.toByte() })
        assertFalse(closingGate.open())
    }

    @Test
    fun configurationValidatesTermAndStartupWithoutPublishingCommandText() {
        val command = "tmux new-session -A -s private-work"
        val config = config(terminalType = "xterm-direct", startupCommand = command)

        assertEquals("xterm-direct", config.terminalType)
        assertEquals(command, config.startupCommand)
        assertFalse(config.toString().contains(command))
        assertThrows(IllegalArgumentException::class.java) {
            config(terminalType = "xterm direct")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(startupCommand = "printf ok\u0000")
        }
    }

    @Test
    fun moshBootstrapNeverConcatenatesTheSavedInteractiveStartupCommand() {
        val command = "printf SHOULD_NOT_REACH_MOSH"

        val built = buildMoshServerCommand(
            MoshBootstrapRequest(
                ssh = config(startupCommand = command),
            ),
        )

        assertFalse(built.contains(command))
        assertFalse(built.contains("SHOULD_NOT_REACH_MOSH"))
    }

    private fun config(
        terminalType: String = "xterm-256color",
        startupCommand: String? = null,
    ) = SshConnectionConfig(
        host = "example.test",
        port = 22,
        username = "alice",
        authentication = SshAuthentication.Password(byteArrayOf(1)),
        terminalType = terminalType,
        startupCommand = startupCommand,
    )
}

@Suppress("UNCHECKED_CAST")
private fun StartupFirstInputGate.retainedStagedInputsForTest(): List<ByteArray> {
    val field = javaClass.getDeclaredField("staged").apply { isAccessible = true }
    return (field.get(this) as java.util.ArrayDeque<ByteArray>).toList()
}
