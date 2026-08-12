package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceActivityTimeTest {
    @Test
    fun relativeActivityLabelsAdvanceAtStableMinuteHourAndDayBoundaries() {
        val now = 10L * 86_400_000L

        assertEquals(
            uiText(R.string.workspace_last_activity_just_now),
            formatWorkspaceLastActivity(now - 59_999L, now),
        )
        assertEquals(
            quantityText(R.plurals.workspace_last_activity_minutes, 1),
            formatWorkspaceLastActivity(now - 60_000L, now),
        )
        assertEquals(
            quantityText(R.plurals.workspace_last_activity_hours, 1),
            formatWorkspaceLastActivity(now - 3_600_000L, now),
        )
        assertEquals(
            quantityText(R.plurals.workspace_last_activity_days, 1),
            formatWorkspaceLastActivity(now - 86_400_000L, now),
        )
    }

    @Test
    fun clockSkewNeverProducesAFutureOrNegativeLabel() {
        assertEquals(
            uiText(R.string.workspace_last_activity_just_now),
            formatWorkspaceLastActivity(
                lastActivityAtEpochMillis = 2_000L,
                nowEpochMillis = 1_000L,
            ),
        )
    }
}
