package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationPolicyTest {
    @Test
    fun terminalBackReturnsToTheWorkspaceOwner() {
        val owner = terminalOwnerForEntry(AppRoute.WORKSPACE, TerminalOwner.CONNECTIONS)

        assertEquals(TerminalOwner.WORKSPACE, owner)
        assertEquals(AppRoute.WORKSPACE, shellBackDestination(AppRoute.TERMINAL_DETAIL, owner))
    }

    @Test
    fun terminalBackReturnsToTheConnectionsOwner() {
        val owner = terminalOwnerForEntry(AppRoute.CONNECTIONS, TerminalOwner.WORKSPACE)

        assertEquals(TerminalOwner.CONNECTIONS, owner)
        assertEquals(AppRoute.CONNECTIONS, shellBackDestination(AppRoute.TERMINAL_DETAIL, owner))
    }

    @Test
    fun openingAnotherSessionFromTerminalPreservesItsOwner() {
        assertEquals(
            TerminalOwner.CONNECTIONS,
            terminalOwnerForEntry(AppRoute.TERMINAL_DETAIL, TerminalOwner.CONNECTIONS),
        )
    }

    @Test
    fun developerTerminalReturnsToSettingsOwner() {
        val owner = terminalOwnerForEntry(AppRoute.SETTINGS, TerminalOwner.WORKSPACE)

        assertEquals(TerminalOwner.SETTINGS, owner)
        assertEquals(AppRoute.SETTINGS, shellBackDestination(AppRoute.TERMINAL_DETAIL, owner))
    }

    @Test
    fun topLevelBackReturnsHomeAndWorkspaceFallsThroughToTheActivity() {
        assertEquals(
            AppRoute.WORKSPACE,
            shellBackDestination(AppRoute.CONNECTIONS, TerminalOwner.CONNECTIONS),
        )
        assertEquals(
            AppRoute.WORKSPACE,
            shellBackDestination(AppRoute.SETTINGS, TerminalOwner.WORKSPACE),
        )
        assertNull(shellBackDestination(AppRoute.WORKSPACE, TerminalOwner.WORKSPACE))
    }

    @Test
    fun connectionsCatalogBackRestoresItsExplicitEntryRoute() {
        assertEquals(
            AppRoute.WORKSPACE,
            catalogReturnDestinationForEntry(AppRoute.WORKSPACE),
        )
        assertEquals(
            AppRoute.TERMINAL_DETAIL,
            catalogReturnDestinationForEntry(AppRoute.TERMINAL_DETAIL),
        )
        assertEquals(
            AppRoute.WORKSPACE,
            shellBackDestination(
                current = AppRoute.CONNECTIONS,
                terminalOwner = TerminalOwner.WORKSPACE,
                catalogReturnDestination = AppRoute.WORKSPACE,
            ),
        )
        assertEquals(
            AppRoute.TERMINAL_DETAIL,
            shellBackDestination(
                current = AppRoute.CONNECTIONS,
                terminalOwner = TerminalOwner.WORKSPACE,
                catalogReturnDestination = AppRoute.TERMINAL_DETAIL,
            ),
        )

        val predictiveBack = checkNotNull(
            startShellBackGesture(
                current = AppRoute.CONNECTIONS,
                terminalOwner = TerminalOwner.WORKSPACE,
                catalogReturnDestination = AppRoute.TERMINAL_DETAIL,
            ),
        )
        assertEquals(AppRoute.TERMINAL_DETAIL, predictiveBack.target)
    }

    @Test
    fun settingsBackRestoresTheExactTerminalEntryRoute() {
        assertEquals(
            AppRoute.TERMINAL_DETAIL,
            shellBackDestination(
                current = AppRoute.SETTINGS,
                terminalOwner = TerminalOwner.WORKSPACE,
                settingsReturnDestination = AppRoute.TERMINAL_DETAIL,
            ),
        )

        val predictiveBack = checkNotNull(
            startShellBackGesture(
                current = AppRoute.SETTINGS,
                terminalOwner = TerminalOwner.WORKSPACE,
                settingsReturnDestination = AppRoute.TERMINAL_DETAIL,
            ),
        )
        assertEquals(AppRoute.TERMINAL_DETAIL, predictiveBack.target)
    }

    @Test
    fun predictiveBackProgressIsBoundedAndMirrorsTheSwipeEdge() {
        val gesture = checkNotNull(
            startShellBackGesture(AppRoute.TERMINAL_DETAIL, TerminalOwner.CONNECTIONS),
        )

        val belowRange = checkNotNull(
            progressShellBackGesture(gesture, -0.5f, ShellBackSwipeEdge.LEFT).preview,
        )
        assertEquals(0f, belowRange.progress, 0f)
        assertEquals(0f, belowRange.translationFraction, 0f)
        assertEquals(1f, belowRange.scale, 0f)
        assertEquals(0f, belowRange.destinationAlpha, 0f)

        val left = checkNotNull(
            progressShellBackGesture(gesture, 2f, ShellBackSwipeEdge.LEFT).preview,
        )
        val right = checkNotNull(
            progressShellBackGesture(gesture, 2f, ShellBackSwipeEdge.RIGHT).preview,
        )
        assertEquals(1f, left.progress, 0f)
        assertEquals(0.08f, left.translationFraction, 0.0001f)
        assertEquals(-0.08f, right.translationFraction, 0.0001f)
        assertEquals(0.96f, left.scale, 0.0001f)
        assertEquals(1f, left.destinationAlpha, 0f)
        assertTrue(left.scale in 0.96f..1f)
    }

    @Test
    fun predictiveBackTreatsInvalidProgressAsTheSafeStartState() {
        val gesture = checkNotNull(
            startShellBackGesture(AppRoute.SETTINGS, TerminalOwner.SETTINGS),
        )

        val preview = checkNotNull(
            progressShellBackGesture(gesture, Float.NaN, ShellBackSwipeEdge.LEFT).preview,
        )

        assertEquals(0f, preview.progress, 0f)
        assertEquals(0f, preview.translationFraction, 0f)
        assertEquals(1f, preview.scale, 0f)
        assertEquals(0f, preview.destinationAlpha, 0f)
    }

    @Test
    fun cancelledPredictiveBackClearsPreviewAndRetainsTheOrigin() {
        val gesture = progressShellBackGesture(
            gesture = checkNotNull(
                startShellBackGesture(AppRoute.TERMINAL_DETAIL, TerminalOwner.SETTINGS),
            ),
            rawProgress = 0.75f,
            edge = ShellBackSwipeEdge.LEFT,
        )

        val cancelled = finishShellBackGesture(gesture, completed = false)

        assertEquals(AppRoute.TERMINAL_DETAIL, cancelled.destination)
        assertNull(cancelled.preview)
    }

    @Test
    fun completedPredictiveBackCommitsEachResolvedTerminalOwner() {
        val expectedTargets = mapOf(
            TerminalOwner.WORKSPACE to AppRoute.WORKSPACE,
            TerminalOwner.CONNECTIONS to AppRoute.CONNECTIONS,
            TerminalOwner.SETTINGS to AppRoute.SETTINGS,
        )

        expectedTargets.forEach { (owner, expectedTarget) ->
            val gesture = checkNotNull(startShellBackGesture(AppRoute.TERMINAL_DETAIL, owner))
            assertEquals(expectedTarget, gesture.target)

            val completed = finishShellBackGesture(gesture, completed = true)
            assertEquals(expectedTarget, completed.destination)
            assertNull(completed.preview)
        }
    }

    @Test
    fun workspaceDoesNotStartAnAppLevelBackGesture() {
        assertNull(startShellBackGesture(AppRoute.WORKSPACE, TerminalOwner.WORKSPACE))
    }
}
