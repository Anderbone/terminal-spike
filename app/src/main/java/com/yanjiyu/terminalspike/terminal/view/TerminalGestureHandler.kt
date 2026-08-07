package com.yanjiyu.terminalspike.terminal.view

import android.view.GestureDetector
import android.view.MotionEvent

class TerminalGestureHandler(
    private val stopFling: () -> Unit,
    private val scrollBy: (Float) -> Unit,
    private val fling: (Float) -> Unit,
    private val focusAndShowKeyboard: () -> Unit,
) : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(event: MotionEvent): Boolean {
        stopFling()
        focusAndShowKeyboard()
        return true
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        focusAndShowKeyboard()
        return true
    }

    override fun onScroll(
        firstEvent: MotionEvent?,
        currentEvent: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        scrollBy(distanceY)
        return true
    }

    override fun onFling(
        firstEvent: MotionEvent?,
        currentEvent: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        fling(velocityY)
        return true
    }
}
