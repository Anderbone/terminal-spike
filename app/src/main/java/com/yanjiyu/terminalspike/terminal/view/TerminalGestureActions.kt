package com.yanjiyu.terminalspike.terminal.view

internal class TerminalGestureActions(
    private val stopFling: () -> Unit,
    private val requestFocus: () -> Unit,
    private val scrollBy: (Float, Float, Float) -> Unit,
    private val fling: (Float) -> Unit,
    private val showKeyboard: () -> Unit,
    private val performClick: () -> Unit,
) {
    private var tapEligible = false

    fun onTouchDown() {
        tapEligible = true
        stopFling()
        requestFocus()
    }

    fun onScroll(distanceY: Float, x: Float, y: Float) {
        tapEligible = false
        scrollBy(distanceY, x, y)
    }

    fun onFling(velocityY: Float) {
        tapEligible = false
        fling(velocityY)
    }

    fun onMultiPointerGesture() {
        tapEligible = false
    }

    fun onCancel() {
        tapEligible = false
    }

    fun onTapConfirmed(): Boolean {
        if (!tapEligible) return false
        tapEligible = false
        showKeyboard()
        performClick()
        return true
    }
}
