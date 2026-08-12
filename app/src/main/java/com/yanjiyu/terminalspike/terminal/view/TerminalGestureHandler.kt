package com.yanjiyu.terminalspike.terminal.view

import android.view.GestureDetector
import android.view.MotionEvent

internal class TerminalGestureHandler(
    private val actions: TerminalGestureActions,
) : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(event: MotionEvent): Boolean {
        actions.onTouchDown(event.x, event.y)
        return true
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean = actions.onTapConfirmed(event.x, event.y)

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
        actions.onFling(velocityY)
        return true
    }
}
