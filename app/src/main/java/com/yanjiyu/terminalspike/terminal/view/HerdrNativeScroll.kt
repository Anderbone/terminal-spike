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

    fun reset() {
        scroller.forceFinished(true)
        velocity?.recycle()
        velocity = null
        dragging = false
        eligible = false
        caughtReader = false
        reader.clear()
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
    ): Boolean {
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val consumed = dragging || caughtReader
            reset()
            return consumed
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                caughtReader = reader.snapshot != null
                val target = reader.snapshot ?: source
                eligible = target != null && event.x >= paddingX + target.x * cellWidth &&
                    event.x < paddingX + (target.x + target.columns) * cellWidth &&
                    event.y >= paddingY + target.y * lineHeight &&
                    event.y < paddingY + (target.y + target.rows) * lineHeight
                if (caughtReader && !eligible) reset()
                return caughtReader
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!eligible) return false
                if (!dragging && abs(event.y - downY) > configuration.scaledTouchSlop &&
                    abs(event.y - downY) > abs(event.x - downX)
                ) {
                    val target = source ?: return false
                    if (reader.snapshot == null) reader.begin(target, lineHeight)
                    dragging = true
                }
                if (!dragging) return caughtReader
                reader.viewport.scrollBy(lastY - event.y)
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val consumed = dragging || caughtReader
                velocity?.addMovement(event)
                if (dragging) {
                    val tracker = velocity
                    tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val speed = -(tracker?.yVelocity ?: 0f).toInt()
                    if (abs(speed) >= configuration.scaledMinimumFlingVelocity) {
                        scroller.fling(0, reader.viewport.scrollY.toInt(), 0, speed, 0, 0,
                            0, reader.viewport.maximumScrollY.toInt())
                    } else if (reader.viewport.autoFollow) reader.clear()
                } else if (caughtReader) reader.clear()
                velocity?.recycle()
                velocity = null
                dragging = false
                caughtReader = false
                eligible = false
                return consumed
            }
        }
        return dragging || caughtReader
    }

    fun animate(): Boolean {
        if (!scroller.computeScrollOffset()) return false
        reader.viewport.scrollTo(scroller.currY.toFloat())
        if (reader.viewport.autoFollow) {
            scroller.forceFinished(true)
            reader.clear()
        }
        return true
    }
}
