package com.yanjiyu.terminalspike.ui.project

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** Small native glyphs, with shape as well as colour distinguishing files from folders. */
@Composable
internal fun ProjectEntryIcon(directory: Boolean, expanded: Boolean, modifier: Modifier = Modifier) {
    val color = if (directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val outline = Path().apply {
            if (directory) {
                moveTo(w * .08f, h * .22f)
                lineTo(w * .4f, h * .22f)
                lineTo(w * .5f, h * .34f)
                lineTo(w * .92f, h * .34f)
                lineTo(w * .92f, h * .82f)
                lineTo(w * .08f, h * .82f)
            } else {
                moveTo(w * .22f, h * .08f)
                lineTo(w * .62f, h * .08f)
                lineTo(w * .82f, h * .3f)
                lineTo(w * .82f, h * .92f)
                lineTo(w * .22f, h * .92f)
            }
            close()
        }
        drawPath(outline, color.copy(alpha = if (directory && expanded) .24f else .08f))
        drawPath(outline, color, style = Stroke(w * .075f, join = StrokeJoin.Round))
        if (!directory) {
            drawLine(color, Offset(w * .36f, h * .5f), Offset(w * .68f, h * .5f), w * .06f)
            drawLine(color, Offset(w * .36f, h * .68f), Offset(w * .62f, h * .68f), w * .06f)
        }
    }
}
