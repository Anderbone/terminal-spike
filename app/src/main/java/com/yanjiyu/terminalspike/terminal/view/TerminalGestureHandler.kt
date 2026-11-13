package com.yanjiyu.terminalspike.terminal.view

import android.view.GestureDetector
import android.view.MotionEvent

internal class TerminalGestureHandler(
    private val actions: TerminalGestureActions,
    private val shouldDeferSingleTap: (Float, Float) -> Boolean = { _, _ -> false },
) : GestureDetector.SimpleOnGestureListener() {
    private var deferredTap = false

    fun cancelPendingTap() { deferredTap = false }

    override fun onDown(event: MotionEvent): Boolean {
        cancelPendingTap()
        actions.onTouchDown(event.x, event.y)
        return true
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        // A Space click can replace the whole list. Keep it local until the
        // detector decides whether this is a single tap or a close double tap.
        deferredTap = shouldDeferSingleTap(event.x, event.y)
        return if (deferredTap) true else actions.onTapConfirmed(event.x, event.y)
    }

    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        if (!deferredTap) return false
        cancelPendingTap()
        return actions.onTapConfirmed(event.x, event.y)
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        cancelPendingTap()
        return actions.onDoubleTap(event.x, event.y)
    }

    override fun onLongPress(event: MotionEvent) {
        actions.onLongPress(event.x, event.y)
    }

    override fun onScroll(
        firstEvent: MotionEvent?,
        currentEvent: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        actions.onScroll(distanceY, currentEvent.x, currentEvent.y, currentEvent.pointerCount)
        return true
    }

    override fun onFling(
        firstEvent: MotionEvent?,
        currentEvent: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        actions.onFling(velocityY, currentEvent.x, currentEvent.y)
        return true
    }
}
