package com.yanjiyu.terminalspike.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxClientDiscoveryTest {
    @Test
    fun matchesTheExactClientAndPaneNotTheNewestSession() {
        assertEquals(
            TmuxClientObservation.Attached(TmuxClientIdentity("/dev/pts/8", "$12", "%41", 100)),
            parseTmuxClientObservation("TERMINAL_SPIKE_CLIENTS\n/dev/pts/8|$12|%41|100\n"),
        )
    }

    @Test
    fun detachIsDistinctFromAnUnavailableOrAmbiguousProbe() {
        assertEquals(TmuxClientObservation.Detached, parseTmuxClientObservation("TERMINAL_SPIKE_CLIENTS\n"))
        listOf(
            "", "shell noise\nTERMINAL_SPIKE_CLIENTS\n",
            "TERMINAL_SPIKE_CLIENTS\n/dev/pts/8|$12|%41|100\n/dev/pts/9|$13|%42|100\n",
            "TERMINAL_SPIKE_CLIENTS\n/dev/pts/8;bad|$12|%41|100\n",
            "TERMINAL_SPIKE_CLIENTS\n/dev/pts/8|name|%41|100\n",
            "TERMINAL_SPIKE_CLIENTS\n/dev/pts/8|$12|%41|0\n",
        ).forEach { assertEquals(TmuxClientObservation.Unavailable, parseTmuxClientObservation(it)) }
    }
}
