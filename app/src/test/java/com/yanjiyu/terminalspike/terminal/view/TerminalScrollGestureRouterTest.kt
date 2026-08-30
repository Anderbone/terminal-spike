package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollGestureRouterTest {
    @Test
    fun autoPreservesOrdinaryShellAndMouseAwareRouting() {
        val router = TerminalScrollGestureRouter()

        router.onGestureStart()

        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(false))
        assertEquals(TerminalScrollDestination.REMOTE_MOUSE, router.destination(true))
    }

    @Test
    fun explicitLocalModeNeverRoutesToRemoteMouse() {
        val router = TerminalScrollGestureRouter(touchMode = TouchScrollMode.LOCAL_SCROLLBACK)

        router.onGestureStart()

        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(false))
        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(true))
    }

    @Test
    fun explicitRemoteModeFallsBackToLocalHistoryWithoutNegotiatedMouseTracking() {
        val router = TerminalScrollGestureRouter(touchMode = TouchScrollMode.REMOTE_MOUSE)

        router.onGestureStart()

        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(false))
        assertEquals(TerminalScrollDestination.REMOTE_MOUSE, router.destination(true))
    }

    @Test
    fun twoFingerOverrideStaysLocalUntilGestureEnds() {
        val router = TerminalScrollGestureRouter(touchMode = TouchScrollMode.REMOTE_MOUSE)

        router.onGestureStart()
        router.observePointerCount(2)
        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(true))

        router.observePointerCount(1)
        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(true))

        router.onGestureEnd()
        assertEquals(TerminalScrollDestination.REMOTE_MOUSE, router.destination(true))
    }

    @Test
    fun configurableOverrideCanBeDisabledWithoutChangingSelectedMode() {
        val router = TerminalScrollGestureRouter(
            touchMode = TouchScrollMode.REMOTE_MOUSE,
            twoFingerLocalScrollOverride = false,
        )

        router.onGestureStart()
        router.observePointerCount(2)

        assertEquals(TerminalScrollDestination.REMOTE_MOUSE, router.destination(true))
    }

    @Test
    fun runtimeConfigurationChangeIsReportedAndClearsDisabledOverride() {
        val router = TerminalScrollGestureRouter()
        router.onGestureStart()
        router.observePointerCount(2)
        assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, router.destination(true))

        assertTrue(router.updateConfiguration(TouchScrollMode.REMOTE_MOUSE, false))
        assertEquals(TerminalScrollDestination.REMOTE_MOUSE, router.destination(true))
        assertFalse(router.updateConfiguration(TouchScrollMode.REMOTE_MOUSE, false))
    }

    @Test
    fun remoteWheelStepsAreThresholdedAndBoundedPerEvent() {
        val accumulator = TerminalMouseWheelAccumulator(maximumStepsPerEvent = 6)

        assertEquals(0, accumulator.consume(distanceY = 14f, stepPx = 15f))
        assertEquals(1, accumulator.consume(distanceY = 1f, stepPx = 15f))
        assertEquals(-2, accumulator.consume(distanceY = -30f, stepPx = 15f))
        assertEquals(6, accumulator.consume(distanceY = 1_000f, stepPx = 15f))
    }

    @Test
    fun invalidWheelGeometryCannotCreateUnboundedReports() {
        val accumulator = TerminalMouseWheelAccumulator(maximumStepsPerEvent = 6)

        assertEquals(0, accumulator.consume(distanceY = Float.POSITIVE_INFINITY, stepPx = 10f))
        assertEquals(0, accumulator.consume(distanceY = 100f, stepPx = 0f))
        assertEquals(-1, accumulator.consume(distanceY = -10f, stepPx = 10f))
    }
}
