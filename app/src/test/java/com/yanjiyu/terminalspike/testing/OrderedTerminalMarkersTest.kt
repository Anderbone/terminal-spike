package com.yanjiyu.terminalspike.testing

import org.junit.Assert.assertThrows
import org.junit.Test

class OrderedTerminalMarkersTest {
    private val rows = (1..200).map { "ROW_" + it.toString().padStart(3, '0') }

    @Test fun acceptsAllRowsWithUnrelatedOutputAndTrailingSpaces() {
        assertOrderedTerminalMarkers(listOf("prompt") + rows.map { "$it  " } + "done", "ROW_", 200)
    }

    @Test fun rejectsMissingMiddleEvenWhenBothEndpointsSurvive() {
        assertThrows(AssertionError::class.java) {
            assertOrderedTerminalMarkers(rows.filter { it != "ROW_100" }, "ROW_", 200)
        }
    }

    @Test fun rejectsReorderedMiddleRows() {
        val reordered = rows.toMutableList().apply { this[99] = rows[100]; this[100] = rows[99] }
        assertThrows(AssertionError::class.java) {
            assertOrderedTerminalMarkers(reordered, "ROW_", 200)
        }
    }

    @Test fun echoedOrPartialMarkersCannotReplaceAnOutputRow() {
        assertThrows(AssertionError::class.java) {
            assertOrderedTerminalMarkers(rows.map { if (it == "ROW_100") "printf ROW_100" else it }, "ROW_", 200)
        }
    }
}
