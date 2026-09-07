package com.yanjiyu.terminalspike.terminal.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class VisibleRows(
    val first: Int,
    val lastExclusive: Int,
) {
    val count: Int get() = (lastExclusive - first).coerceAtLeast(0)
}

class TerminalViewport {
    var scrollY: Float = 0f
        private set
    var autoFollow: Boolean = true
        private set
    var viewportHeightPx: Int = 0
        private set
    var lineHeightPx: Float = 1f
        private set
    var lineCount: Int = 0
        private set
    var oldestLineId: Long? = null
        private set

    val maximumScrollY: Float
        get() = max(0f, lineCount * lineHeightPx - viewportHeightPx)

    fun updateGeometry(heightPx: Int, newLineHeightPx: Float) {
        viewportHeightPx = heightPx.coerceAtLeast(0)
        lineHeightPx = newLineHeightPx.coerceAtLeast(1f)
        scrollY = if (autoFollow) maximumScrollY else scrollY.coerceIn(0f, maximumScrollY)
    }

    fun updateContent(newLineCount: Int, newOldestLineId: Long?) {
        val removedLines = if (
            oldestLineId != null &&
            newOldestLineId != null &&
            newOldestLineId > oldestLineId!!
        ) {
            (newOldestLineId - oldestLineId!!).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
        val prependedLines = if (
            oldestLineId != null &&
            newOldestLineId != null &&
            newOldestLineId < oldestLineId!!
        ) {
            (oldestLineId!! - newOldestLineId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }

        lineCount = newLineCount.coerceAtLeast(0)
        oldestLineId = newOldestLineId
        scrollY = if (autoFollow) {
            maximumScrollY
        } else {
            (scrollY - removedLines * lineHeightPx + prependedLines * lineHeightPx)
                .coerceIn(0f, maximumScrollY)
        }
        if (lineCount == 0) {
            autoFollow = true
            scrollY = 0f
        }
    }

    fun scrollBy(deltaPx: Float): Float {
        scrollY = (scrollY + deltaPx).coerceIn(0f, maximumScrollY)
        autoFollow = isAtBottom()
        return scrollY
    }

    fun scrollTo(positionPx: Float): Float {
        scrollY = positionPx.coerceIn(0f, maximumScrollY)
        autoFollow = isAtBottom()
        return scrollY
    }

    fun jumpToBottom() {
        scrollY = maximumScrollY
        autoFollow = true
    }

    fun visibleRows(overscan: Int = 1): VisibleRows {
        if (lineCount == 0 || viewportHeightPx == 0) return VisibleRows(0, 0)
        val firstVisible = floor(scrollY / lineHeightPx).toInt()
        val visibleCount = ceil(viewportHeightPx / lineHeightPx).toInt() + 1
        return VisibleRows(
            first = (firstVisible - overscan).coerceAtLeast(0),
            lastExclusive = (firstVisible + visibleCount + overscan).coerceAtMost(lineCount),
        )
    }

    private fun isAtBottom(): Boolean = maximumScrollY - scrollY <= BOTTOM_EPSILON_PX

    companion object {
        private const val BOTTOM_EPSILON_PX = 0.5f
    }
}
