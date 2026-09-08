package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
import org.junit.Assert.*
import org.junit.Test

class TmuxTaskMonitorTest {
    private var now = 2_000_000_000L
    private val monitor = TmuxTaskMonitor { now }

    private fun refresh(rows: String) {
        now += 1_000_000_001L
        monitor.refresh(TmuxCommandRunner { TmuxExecOutput(rows.toByteArray(), 0) }, "/usr/bin/tmux")
    }

    @Test fun inactivePanesAggregateWithoutHidingAnotherPanesCompletion() {
        refresh("$1|%1|80|1|0|⠋ work\n$1|%2|80|1|0|⠋ other")
        refresh("$1|%1|80|1|0|work\n$1|%2|80|1|0|⠙ other")
        assertEquals(TerminalTaskStatus(running = true, finished = true), monitor.sessionStatus("$1"))
        monitor.acknowledge("%2")
        assertTrue(monitor.sessionStatus("$1").finished)
        monitor.acknowledge("%1")
        assertFalse(monitor.sessionStatus("$1").finished)
        assertTrue(monitor.sessionStatus("$1").running)
    }

    @Test fun staleBellDoesNotReappearAfterAcknowledgmentAndServerRestartResetsIdentity() {
        refresh("$1|%1|80|0|1|shell")
        assertTrue(monitor.paneStatus("%1").needsAttention)
        monitor.acknowledge("%1")
        refresh("$1|%1|80|0|1|shell")
        assertFalse(monitor.paneStatus("%1").needsAttention)
        refresh("$1|%1|80|1|0|⠋ work")
        refresh("$1|%1|81|1|0|work")
        assertEquals(TerminalTaskStatus(), monitor.paneStatus("%1"))
    }

    @Test fun closingMonitorWhileQueryIsInFlightCannotRestoreAStaleSpinner() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            monitor.refresh(TmuxCommandRunner {
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                TmuxExecOutput("$1|%1|80|1|0|⠋ work".toByteArray(), 0)
            }, "/usr/bin/tmux")
        }
        worker.start()
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            monitor.clear()
        } finally {
            release.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(TerminalTaskStatus(), monitor.paneStatus("%1"))
    }

    @Test fun plainShellCannotInheritAFormerCodexWorkingTitle() {
        refresh("$1|%1|80|0|0|⠋ work")
        assertEquals(TerminalTaskStatus(), monitor.paneStatus("%1"))
    }
}
