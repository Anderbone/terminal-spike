package com.yanjiyu.terminalspike.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetToolbarOrderTest {
    @Test
    fun builtInsRemainAvailableAlongsideSavedSnippetsAndStaleOrder() {
        val defaults = com.yanjiyu.terminalspike.settings.DefaultTerminalSnippets.all
        val available = listOf(42L) + defaults.map { it.id }
        assertEquals(listOf(-1L, 42L, 0L, -2L), snippetToolbarOrder(listOf(-1L, 99L), available))
        val textActions = defaults.filter { it.id != 0L }
        textActions.forEach { snippet ->
            var submitted: Boolean? = null
            val outcome = com.yanjiyu.terminalspike.ui.dispatchSnippet(snippet, confirmed = true) {
                submitted = it
                true
            }
            assertEquals(com.yanjiyu.terminalspike.ui.SnippetDispatchOutcome.INSERTED, outcome)
            assertEquals(false, submitted)
        }
        assertEquals("continue", textActions.first().command)
    }

    @Test
    fun savedOrderSurvivesReloadAndNewSnippetsAppend() {
        assertEquals(listOf(0L, 2L, 1L, 3L), snippetToolbarOrder(listOf(0, 2, 1), listOf(1, 2, 3, 0)))
    }

    @Test
    fun deletedAndDuplicateIdsDoNotLeaveGapsOrDuplicateButtons() {
        assertEquals(listOf(2L, 0L, 3L), snippetToolbarOrder(listOf(9, 2, 2, 0), listOf(2, 3, 0)))
    }
}
