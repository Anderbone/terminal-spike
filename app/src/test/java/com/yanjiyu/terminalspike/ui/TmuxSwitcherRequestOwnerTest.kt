package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSwitcherRequestOwnerTest {
    @Test fun closingAndReopeningTheSameSessionRejectsOldCompletions() {
        val gate = TmuxSwitcherRequestOwner()
        val first = gate.open(11)
        val old = requireNotNull(gate.beginRequest(first))
        gate.close(first)
        val second = gate.open(11)
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(requireNotNull(gate.beginRequest(second))))
    }

    @Test fun changingHostRejectsTheOldOwnerEvenWithTheSameRemoteSessionId() {
        val gate = TmuxSwitcherRequestOwner()
        val firstHost = gate.open(11)
        val oldDelete = requireNotNull(gate.beginOperation(firstHost))
        val secondHost = gate.open(12)
        assertFalse(gate.isCurrent(oldDelete))
        assertNull(gate.beginOperation(firstHost))
        assertTrue(gate.isCurrent(secondHost))
    }

    @Test fun outOfOrderRefreshAndPreviewResultsCannotReplaceTheLatestCatalog() {
        val gate = TmuxSwitcherRequestOwner()
        val owner = gate.open(11)
        val preview = requireNotNull(gate.beginRequest(owner))
        val poll = requireNotNull(gate.beginRequest(owner))
        assertFalse(gate.isCurrent(preview))
        assertTrue(gate.isCurrent(poll))
    }

    @Test fun pollingAndDoubleClicksCannotSupersedeAnAcceptedOperation() {
        val gate = TmuxSwitcherRequestOwner()
        val owner = gate.open(11)
        val earlierPoll = requireNotNull(gate.beginRequest(owner))
        val delete = requireNotNull(gate.beginOperation(owner))
        assertFalse(gate.isCurrent(earlierPoll))
        assertNull(gate.beginRequest(owner))
        assertNull(gate.beginOperation(owner))
        assertTrue(gate.isCurrent(delete))
        gate.finishOperation(delete)
        assertNotNull(gate.beginRequest(owner))
    }

    @Test fun anOldOwnerCannotCloseOrUnblockTheReplacementOperation() {
        val gate = TmuxSwitcherRequestOwner()
        val first = gate.open(11)
        val old = requireNotNull(gate.beginOperation(first))
        val replacement = gate.open(11)
        val current = requireNotNull(gate.beginOperation(replacement))
        gate.close(first)
        gate.finishOperation(old)
        assertTrue(gate.isCurrent(current))
        assertNull(gate.beginRequest(replacement))
        gate.finishOperation(current)
        assertNotNull(gate.beginRequest(replacement))
    }

    @Test fun refreshingTheSameOwnerIdUsesANewOpeningAndRejectsThePreviousRequest() {
        val gate = TmuxSwitcherRequestOwner()
        val oldOwner = gate.open(11)
        val old = requireNotNull(gate.beginRequest(oldOwner))
        val refreshedOwner = gate.open(11)
        assertFalse(gate.isCurrent(old))
        assertNull(gate.beginRequest(oldOwner))
        assertTrue(gate.isCurrent(requireNotNull(gate.beginRequest(refreshedOwner))))
    }
}
