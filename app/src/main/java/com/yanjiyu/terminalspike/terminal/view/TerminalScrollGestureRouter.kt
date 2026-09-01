package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.core.model.TouchScrollMode

/** The terminal hot path destination for one vertical touch gesture update. */
internal enum class TerminalScrollDestination {
    LOCAL_SCROLLBACK,
    REMOTE_MOUSE,
    NONE,
}

/** Exact reason the first classified move chose its terminal scroll destination. */
internal enum class TerminalScrollDecisionReason {
    TWO_FINGER_LOCAL_OVERRIDE,
    AUTO_TMUX_LOCAL_READY,
    AUTO_TMUX_LOCAL_PENDING,
    AUTO_REMOTE_MOUSE_TRACKING,
    AUTO_LOCAL_NO_MOUSE_TRACKING,
    EXPLICIT_LOCAL_SCROLLBACK,
    EXPLICIT_REMOTE_MOUSE,
    EXPLICIT_REMOTE_FALLBACK_LOCAL,
}

internal data class TerminalScrollDecision(
    val destination: TerminalScrollDestination,
    val reason: TerminalScrollDecisionReason,
)

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
    private var tmuxDecisionLatched: TerminalScrollDecision? = null

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
        tmuxDecisionLatched = null
    }

    fun observePointerCount(pointerCount: Int) {
        if (twoFingerLocalScrollOverride && pointerCount >= 2) localOverrideLatched = true
    }

    fun destination(
        remoteMouseTrackingEnabled: Boolean,
        confirmedTmuxSession: Boolean = false,
        tmuxLocalScrollAvailable: Boolean = false,
    ): TerminalScrollDestination = decision(
        remoteMouseTrackingEnabled = remoteMouseTrackingEnabled,
        confirmedTmuxSession = confirmedTmuxSession,
        tmuxLocalScrollAvailable = tmuxLocalScrollAvailable,
    ).destination

    fun decision(
        remoteMouseTrackingEnabled: Boolean,
        confirmedTmuxSession: Boolean = false,
        tmuxLocalScrollAvailable: Boolean = false,
    ): TerminalScrollDecision {
        if (localOverrideLatched) {
            return TerminalScrollDecision(
                destination = TerminalScrollDestination.LOCAL_SCROLLBACK,
                reason = TerminalScrollDecisionReason.TWO_FINGER_LOCAL_OVERRIDE,
            )
        }
        if (confirmedTmuxSession) {
            tmuxDecisionLatched?.let { return it }
        }
        val resolved = when (touchMode) {
            TouchScrollMode.AUTO -> when {
                confirmedTmuxSession && tmuxLocalScrollAvailable ->
                    TerminalScrollDecision(
                        destination = TerminalScrollDestination.LOCAL_SCROLLBACK,
                        reason = TerminalScrollDecisionReason.AUTO_TMUX_LOCAL_READY,
                    )
                confirmedTmuxSession -> TerminalScrollDecision(
                    destination = TerminalScrollDestination.NONE,
                    reason = TerminalScrollDecisionReason.AUTO_TMUX_LOCAL_PENDING,
                )
                remoteMouseTrackingEnabled -> TerminalScrollDecision(
                    destination = TerminalScrollDestination.REMOTE_MOUSE,
                    reason = TerminalScrollDecisionReason.AUTO_REMOTE_MOUSE_TRACKING,
                )
                else -> TerminalScrollDecision(
                    destination = TerminalScrollDestination.LOCAL_SCROLLBACK,
                    reason = TerminalScrollDecisionReason.AUTO_LOCAL_NO_MOUSE_TRACKING,
                )
            }
            TouchScrollMode.LOCAL_SCROLLBACK -> TerminalScrollDecision(
                destination = TerminalScrollDestination.LOCAL_SCROLLBACK,
                reason = TerminalScrollDecisionReason.EXPLICIT_LOCAL_SCROLLBACK,
            )
            TouchScrollMode.REMOTE_MOUSE -> if (remoteMouseTrackingEnabled) {
                TerminalScrollDecision(
                    destination = TerminalScrollDestination.REMOTE_MOUSE,
                    reason = TerminalScrollDecisionReason.EXPLICIT_REMOTE_MOUSE,
                )
            } else {
                // A shell that has not negotiated mouse tracking cannot consume remote wheel
                // reports. Keep its already-captured history reachable instead of dropping touch.
                TerminalScrollDecision(
                    destination = TerminalScrollDestination.LOCAL_SCROLLBACK,
                    reason = TerminalScrollDecisionReason.EXPLICIT_REMOTE_FALLBACK_LOCAL,
                )
            }
        }
        if (confirmedTmuxSession && resolved.destination != TerminalScrollDestination.NONE) {
            tmuxDecisionLatched = resolved
        }
        return resolved
    }

    fun onGestureEnd() {
        localOverrideLatched = false
        tmuxDecisionLatched = null
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
