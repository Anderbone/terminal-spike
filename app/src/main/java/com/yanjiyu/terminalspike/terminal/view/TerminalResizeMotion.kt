package com.yanjiyu.terminalspike.terminal.view

import kotlin.math.max
import kotlin.math.min

/** Only moves the retained picture. It never changes history, terminal dimensions or scrollY. */
internal class TerminalResizeMotion(
    private val sourceHeight: Int,
    private val sourceGridHeight: Int,
    private val anchor: Anchor,
    private val anchorPixels: Float,
    private val sourceScrollY: Float,
) {
    enum class Anchor { BOTTOM, CURSOR, CONTENT }

    private var hasTarget = false
    private var fromHeight = sourceHeight
    private var fromGrid = sourceGridHeight.toFloat()
    private var targetHeight = sourceHeight
    private var targetGrid = sourceGridHeight.toFloat()
    private var lastHeight = sourceHeight
    private var lastGrid = sourceGridHeight.toFloat()
    private var settleStarted: Long? = null

    fun retarget(currentHeight: Int, nextHeight: Int, nextGrid: Int, nowMillis: Long) {
        if (hasTarget && targetHeight == nextHeight && targetGrid == nextGrid.toFloat()) return
        val currentGrid = gridAt(currentHeight, nowMillis)
        if (nextHeight == currentHeight) {
            fromHeight = currentHeight
            fromGrid = currentGrid
            settleStarted = if (fromGrid != nextGrid.toFloat()) nowMillis else null
        } else {
            fromHeight = lastHeight
            fromGrid = lastGrid
            settleStarted = null
        }
        targetHeight = nextHeight
        targetGrid = nextGrid.toFloat()
        hasTarget = true
    }

    fun offset(height: Int, nowMillis: Long): Float {
        val grid = gridAt(height, nowMillis)
        lastHeight = height
        lastGrid = grid
        return when (anchor) {
            Anchor.BOTTOM -> grid - sourceGridHeight
            Anchor.CURSOR -> min(0f, grid - anchorPixels)
            Anchor.CONTENT -> sourceScrollY - max(0f, anchorPixels - grid)
        }
    }

    fun remainingMillis(nowMillis: Long): Long =
        settleStarted?.let { (it + SETTLE_MILLIS - nowMillis).coerceAtLeast(0L) } ?: 0L

    private fun gridAt(height: Int, nowMillis: Long): Float {
        if (!hasTarget) return sourceGridHeight + (height - sourceHeight).toFloat()
        val started = settleStarted
        val fraction = if (started != null) {
            val t = ((nowMillis - started).toFloat() / SETTLE_MILLIS).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        } else if (targetHeight == fromHeight) 1f else {
            ((height - fromHeight).toFloat() / (targetHeight - fromHeight)).coerceIn(0f, 1f)
        }
        return fromGrid + (targetGrid - fromGrid) * fraction
    }

    companion object { private const val SETTLE_MILLIS = 96L }
}
