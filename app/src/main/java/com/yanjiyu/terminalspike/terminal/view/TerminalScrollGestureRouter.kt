package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.core.model.TouchScrollMode

/** The terminal hot path destination for one vertical touch gesture update. */
internal enum class TerminalScrollDestination {
    LOCAL_SCROLLBACK,
    REMOTE_MOUSE,
    NONE,
}

/**
 * Keeps profile-driven touch routing independent from Android gesture and rendering objects.
 *
 * A two-finger override is latched for the rest of its gesture. This prevents lifting one finger
 * from accidentally sending a final remote wheel report to tmux, Vim, or another mouse-aware app.
 */
internal class TerminalScrollGestureRouter(
    touchMode: TouchScrollMode = TouchScrollMode.AUTO,
    twoFingerLocalScrollOverride: Boolean = true,
) {
    private var touchMode = touchMode
    private var twoFingerLocalScrollOverride = twoFingerLocalScrollOverride
    private var localOverrideLatched = false

    fun updateConfiguration(
        touchMode: TouchScrollMode,
        twoFingerLocalScrollOverride: Boolean,
    ): Boolean {
        if (
            this.touchMode == touchMode &&
            this.twoFingerLocalScrollOverride == twoFingerLocalScrollOverride
        ) {
            return false
        }
        this.touchMode = touchMode
        this.twoFingerLocalScrollOverride = twoFingerLocalScrollOverride
        if (!twoFingerLocalScrollOverride) localOverrideLatched = false
        return true
    }

    fun onGestureStart() {
        localOverrideLatched = false
    }

    fun observePointerCount(pointerCount: Int) {
        if (twoFingerLocalScrollOverride && pointerCount >= 2) localOverrideLatched = true
    }

    fun destination(remoteMouseTrackingEnabled: Boolean): TerminalScrollDestination {
        if (localOverrideLatched) return TerminalScrollDestination.LOCAL_SCROLLBACK
        return when (touchMode) {
            TouchScrollMode.AUTO -> if (remoteMouseTrackingEnabled) {
                TerminalScrollDestination.REMOTE_MOUSE
            } else {
                TerminalScrollDestination.LOCAL_SCROLLBACK
            }
            TouchScrollMode.LOCAL_SCROLLBACK -> TerminalScrollDestination.LOCAL_SCROLLBACK
            TouchScrollMode.REMOTE_MOUSE -> if (remoteMouseTrackingEnabled) {
                TerminalScrollDestination.REMOTE_MOUSE
            } else {
                // A shell that has not negotiated mouse tracking cannot consume remote wheel
                // reports. Keep its already-captured history reachable instead of dropping touch.
                TerminalScrollDestination.LOCAL_SCROLLBACK
            }
        }
    }

    fun onGestureEnd() {
        localOverrideLatched = false
    }
}

/** Converts touch distance to a bounded number of terminal wheel reports per MotionEvent. */
internal class TerminalMouseWheelAccumulator(
    private val maximumStepsPerEvent: Int,
) {
    private var remainderPx = 0f

    init {
        require(maximumStepsPerEvent > 0)
    }

    fun reset() {
        remainderPx = 0f
    }

    /** Negative steps are wheel-up reports; positive steps are wheel-down reports. */
    fun consume(distanceY: Float, stepPx: Float): Int {
        if (!distanceY.isFinite() || !stepPx.isFinite() || stepPx <= 0f) {
            reset()
            return 0
        }
        remainderPx += distanceY
        var steps = 0
        while (
            kotlin.math.abs(remainderPx) >= stepPx &&
            kotlin.math.abs(steps) < maximumStepsPerEvent
        ) {
            val direction = if (remainderPx < 0f) -1 else 1
            remainderPx -= direction * stepPx
            steps += direction
        }
        return steps
    }
}
