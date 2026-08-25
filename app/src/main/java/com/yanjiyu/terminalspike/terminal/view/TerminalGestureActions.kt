package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionEndpoint

internal class TerminalGestureActions(
    private val stopFling: () -> Unit,
    private val requestFocus: () -> Unit,
    private val scrollBy: (Float, Float, Float, Int) -> Unit,
    private val fling: (Float, Float, Float) -> Unit,
    private val showKeyboard: () -> Unit,
    private val performClick: () -> Unit,
    private val handleTap: (Float, Float) -> Boolean,
    private val handleLongPress: (Float, Float) -> Boolean,
    private val selectionHandleAt: (Float, Float) -> TerminalSelectionEndpoint?,
    private val startSelection: (Float, Float) -> Boolean,
    private val dragSelection: (TerminalSelectionEndpoint, Float, Float) -> Unit,
    private val finishSelectionDrag: (committed: Boolean) -> Unit,
) {
    private var tapEligible = false
    private var selectionEndpoint: TerminalSelectionEndpoint? = null

    fun onTouchDown(x: Float, y: Float) {
        selectionEndpoint = selectionHandleAt(x, y)
        tapEligible = selectionEndpoint == null
        stopFling()
        requestFocus()
    }

    fun onScroll(distanceY: Float, x: Float, y: Float, pointerCount: Int = 1) {
        tapEligible = false
        val endpoint = selectionEndpoint
        if (endpoint != null && pointerCount == 1) {
            dragSelection(endpoint, x, y)
        } else {
            scrollBy(distanceY, x, y, pointerCount)
        }
    }

    fun onFling(velocityY: Float, x: Float = 0f, y: Float = 0f) {
        tapEligible = false
        if (selectionEndpoint == null) fling(velocityY, x, y)
    }

    fun onLongPress(x: Float, y: Float) {
        if (selectionEndpoint != null) return
        tapEligible = false
        if (handleLongPress(x, y)) return
        if (startSelection(x, y)) selectionEndpoint = TerminalSelectionEndpoint.END
    }

    fun onMultiPointerGesture() {
        tapEligible = false
        finishSelectionGesture(committed = false)
    }

    fun onCancel() {
        tapEligible = false
        finishSelectionGesture(committed = false)
    }

    fun onTouchUp() {
        finishSelectionGesture(committed = true)
    }

    fun onTapConfirmed(x: Float, y: Float): Boolean {
        if (!tapEligible) return false
        tapEligible = false
        if (!handleTap(x, y)) showKeyboard()
        performClick()
        return true
    }

    private fun finishSelectionGesture(committed: Boolean) {
        if (selectionEndpoint != null) finishSelectionDrag(committed)
        selectionEndpoint = null
    }
}
