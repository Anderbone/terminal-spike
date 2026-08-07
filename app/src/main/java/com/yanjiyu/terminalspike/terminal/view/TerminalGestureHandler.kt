package com.yanjiyu.terminalspike.terminal.view

import android.view.GestureDetector
import android.view.MotionEvent

internal class TerminalGestureHandler(
    private val actions: TerminalGestureActions,
) : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(event: MotionEvent): Boolean {
        actions.onTouchDown()
        return true
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean = actions.onTapConfirmed()

    override fun onScroll(
        firstEvent: MotionEvent?,
        currentEvent: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        actions.onScroll(distanceY, currentEvent.x, currentEvent.y)
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
