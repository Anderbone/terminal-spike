package com.yanjiyu.terminalspike.terminal.view

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import kotlin.math.abs

/** Native pixel motion only: this object has no transport or remote-input callbacks. */
internal class HerdrNativeScroll(context: Context) {
    val reader = HerdrHistoryViewport()
    private val scroller = OverScroller(context)
    private val configuration = ViewConfiguration.get(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var eligible = false
    private var caughtReader = false
    private var historyTapEligible = false
    private var trackingTouch = false
    private var awaitingSource = false
    val hasActiveTouch: Boolean get() = trackingTouch

    fun reset() {
        cancelTouch()
        reader.clear()
    }

    fun cancelTouch() {
        scroller.forceFinished(true)
        velocity?.recycle()
        velocity = null
        dragging = false
        eligible = false
        caughtReader = false
        historyTapEligible = false
        trackingTouch = false
        awaitingSource = false
    }

    fun updateSource(source: HerdrPaneHistory?) {
        if (!reader.retainSource(source)) reset()
    }

    fun touch(
        event: MotionEvent,
        source: HerdrPaneHistory?,
        cellWidth: Float,
        lineHeight: Float,
        paddingX: Float,
        paddingY: Float,
        onHistoryTap: (Float, Float) -> Unit = { _, _ -> },
    ): Boolean {
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val consumed = dragging || caughtReader
            cancelTouch()
            return consumed
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val catchingFling = !scroller.isFinished
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                trackingTouch = true
                lastY = event.y
                dragging = false
                caughtReader = reader.snapshot != null
                val target = reader.snapshot ?: source
                awaitingSource = target == null
                eligible = target != null && containsDown(target, cellWidth, lineHeight, paddingX, paddingY)
                if (caughtReader && !eligible) reset()
                historyTapEligible = caughtReader && eligible && !catchingFling
                return caughtReader
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                if (awaitingSource && source != null) {
                    awaitingSource = false
                    eligible = containsDown(source, cellWidth, lineHeight, paddingX, paddingY)
                }
                velocity?.addMovement(event)
                if (abs(event.x - downX) > configuration.scaledTouchSlop ||
                    abs(event.y - downY) > configuration.scaledTouchSlop
                ) historyTapEligible = false
                if (!eligible) return false
                if (!dragging && abs(event.y - downY) > configuration.scaledTouchSlop &&
                    abs(event.y - downY) > abs(event.x - downX)
                ) {
                    val target = reader.snapshot ?: source ?: return false
                    reader.beginScroll(target, lineHeight, lastY - event.y)
                    dragging = true
                }
                if (!dragging) return caughtReader
                reader.scrollBy(lastY - event.y)
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val consumed = dragging || caughtReader
                val historyTap = historyTapEligible && !dragging &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                    abs(event.x - downX) <= configuration.scaledTouchSlop &&
                    abs(event.y - downY) <= configuration.scaledTouchSlop
                velocity?.addMovement(event)
                if (dragging && reader.snapshot != null) {
                    val tracker = velocity
                    tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val speed = -(tracker?.yVelocity ?: 0f).toInt()
                    if (abs(speed) >= configuration.scaledMinimumFlingVelocity) {
                        scroller.fling(0, reader.viewport.scrollY.toInt(), 0, speed, 0, 0,
                            0, reader.viewport.maximumScrollY.toInt())
                    }
                }
                // A tap also catches a fling. Keep the pinned pixel anchor after finger-up;
                // only scrolling to live bottom or an explicit live action releases history.
                velocity?.recycle()
                velocity = null
                dragging = false
                caughtReader = false
                eligible = false
                historyTapEligible = false
                trackingTouch = false
                awaitingSource = false
                if (historyTap) onHistoryTap(event.x, event.y)
                return consumed
            }
        }
        return dragging || caughtReader
    }

    private fun containsDown(source: HerdrPaneHistory, cellWidth: Float, lineHeight: Float, paddingX: Float, paddingY: Float) =
        downX >= paddingX + source.x * cellWidth && downX < paddingX + (source.x + source.columns) * cellWidth &&
            downY >= paddingY + source.y * lineHeight && downY < paddingY + (source.y + source.rows) * lineHeight

    fun animate(): Boolean {
        if (!scroller.computeScrollOffset()) return false
        reader.scrollTo(scroller.currY.toFloat())
        if (reader.viewport.autoFollow) {
            scroller.forceFinished(true)
        }
        return true
    }
}
