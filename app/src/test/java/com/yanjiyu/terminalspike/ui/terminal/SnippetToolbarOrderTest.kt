package com.yanjiyu.terminalspike.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetToolbarOrderTest {
    @Test
    fun savedOrderSurvivesReloadAndNewSnippetsAppend() {
        assertEquals(listOf(0L, 2L, 1L, 3L), snippetToolbarOrder(listOf(0, 2, 1), listOf(1, 2, 3, 0)))
    }

    @Test
    fun deletedAndDuplicateIdsDoNotLeaveGapsOrDuplicateButtons() {
        assertEquals(listOf(2L, 0L, 3L), snippetToolbarOrder(listOf(9, 2, 2, 0), listOf(2, 3, 0)))
    }
}
