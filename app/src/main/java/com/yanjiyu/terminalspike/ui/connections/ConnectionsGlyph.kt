package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class ConnectionsGlyph {
    BACK,
    RESTORE,
    SEARCH,
    CLOSE,
    ADD,
    FILTER,
    MORE,
    HOST,
    KEY,
    SNIPPET,
    STAR,
    LOCK,
    WARNING,
    INFO,
    TABS,
    WINDOWS,
    IMAGE,
    KEYBOARD,
    MIC,
    STOP,
}

@Composable
internal fun ConnectionsGlyphIcon(
    glyph: ConnectionsGlyph,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val strokeWidth = minOf(width, height) * 0.09f
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun point(x: Float, y: Float) = Offset(width * x, height * y)

        when (glyph) {
            ConnectionsGlyph.BACK -> {
                drawLine(resolvedColor, point(0.75f, 0.18f), point(0.38f, 0.5f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.38f, 0.5f), point(0.75f, 0.82f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.RESTORE -> {
                drawArc(
                    color = resolvedColor,
                    startAngle = -55f,
                    sweepAngle = 255f,
                    useCenter = false,
                    topLeft = point(0.17f, 0.17f),
                    size = Size(width * 0.66f, height * 0.66f),
                    style = stroke,
                )
                val arrow = Path().apply {
                    moveTo(width * 0.17f, height * 0.38f)
                    lineTo(width * 0.38f, height * 0.17f)
                    moveTo(width * 0.17f, height * 0.38f)
                    lineTo(width * 0.44f, height * 0.4f)
                }
                drawPath(arrow, resolvedColor, style = stroke)
            }
            ConnectionsGlyph.SEARCH -> {
                drawCircle(
                    color = resolvedColor,
                    radius = width * 0.27f,
                    center = point(0.43f, 0.43f),
                    style = stroke,
                )
                drawLine(resolvedColor, point(0.63f, 0.63f), point(0.86f, 0.86f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.CLOSE -> {
                drawLine(resolvedColor, point(0.23f, 0.23f), point(0.77f, 0.77f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.77f, 0.23f), point(0.23f, 0.77f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.ADD -> {
                drawLine(resolvedColor, point(0.5f, 0.18f), point(0.5f, 0.82f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.18f, 0.5f), point(0.82f, 0.5f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.FILTER -> {
                val path = Path().apply {
                    moveTo(width * 0.12f, height * 0.2f)
                    lineTo(width * 0.88f, height * 0.2f)
                    lineTo(width * 0.61f, height * 0.52f)
                    lineTo(width * 0.61f, height * 0.79f)
                    lineTo(width * 0.39f, height * 0.9f)
                    lineTo(width * 0.39f, height * 0.52f)
                    close()
                }
                drawPath(path, resolvedColor, style = stroke)
            }
            ConnectionsGlyph.MORE -> {
                listOf(0.23f, 0.5f, 0.77f).forEach { y ->
                    drawCircle(resolvedColor, radius = width * 0.065f, center = point(0.5f, y))
                }
            }
            ConnectionsGlyph.HOST -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.1f, 0.17f),
                    size = Size(width * 0.8f, height * 0.62f),
                    cornerRadius = CornerRadius(width * 0.08f),
                    style = stroke,
                )
                drawLine(resolvedColor, point(0.25f, 0.44f), point(0.38f, 0.53f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.38f, 0.53f), point(0.25f, 0.62f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.49f, 0.63f), point(0.72f, 0.63f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.KEY -> {
                drawCircle(
                    color = resolvedColor,
                    radius = width * 0.2f,
                    center = point(0.34f, 0.35f),
                    style = stroke,
                )
                val path = Path().apply {
                    moveTo(width * 0.48f, height * 0.49f)
                    lineTo(width * 0.84f, height * 0.85f)
                    moveTo(width * 0.66f, height * 0.67f)
                    lineTo(width * 0.56f, height * 0.77f)
                    moveTo(width * 0.76f, height * 0.77f)
                    lineTo(width * 0.66f, height * 0.87f)
                }
                drawPath(path, resolvedColor, style = stroke)
            }
            ConnectionsGlyph.SNIPPET -> {
                val left = Path().apply {
                    moveTo(width * 0.39f, height * 0.14f)
                    cubicTo(width * 0.2f, height * 0.14f, width * 0.28f, height * 0.4f, width * 0.12f, height * 0.5f)
                    cubicTo(width * 0.28f, height * 0.6f, width * 0.2f, height * 0.86f, width * 0.39f, height * 0.86f)
                }
                val right = Path().apply {
                    moveTo(width * 0.61f, height * 0.14f)
                    cubicTo(width * 0.8f, height * 0.14f, width * 0.72f, height * 0.4f, width * 0.88f, height * 0.5f)
                    cubicTo(width * 0.72f, height * 0.6f, width * 0.8f, height * 0.86f, width * 0.61f, height * 0.86f)
                }
                drawPath(left, resolvedColor, style = stroke)
                drawPath(right, resolvedColor, style = stroke)
            }
            ConnectionsGlyph.STAR -> {
                val star = Path()
                repeat(10) { index ->
                    val angle = -PI / 2 + index * PI / 5
                    val radius = if (index % 2 == 0) width * 0.43f else width * 0.19f
                    val x = width * 0.5f + cos(angle).toFloat() * radius
                    val y = height * 0.5f + sin(angle).toFloat() * radius
                    if (index == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, resolvedColor)
            }
            ConnectionsGlyph.LOCK -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.18f, 0.44f),
                    size = Size(width * 0.64f, height * 0.45f),
                    cornerRadius = CornerRadius(width * 0.08f),
                    style = stroke,
                )
                drawArc(
                    color = resolvedColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = point(0.3f, 0.1f),
                    size = Size(width * 0.4f, height * 0.62f),
                    style = stroke,
                )
            }
            ConnectionsGlyph.WARNING -> {
                val triangle = Path().apply {
                    moveTo(width * 0.5f, height * 0.1f)
                    lineTo(width * 0.91f, height * 0.86f)
                    lineTo(width * 0.09f, height * 0.86f)
                    close()
                }
                drawPath(triangle, resolvedColor, style = stroke)
                drawLine(resolvedColor, point(0.5f, 0.35f), point(0.5f, 0.6f), strokeWidth, StrokeCap.Round)
                drawCircle(resolvedColor, radius = strokeWidth * 0.55f, center = point(0.5f, 0.73f))
            }
            ConnectionsGlyph.INFO -> {
                drawCircle(resolvedColor, radius = width * 0.39f, center = point(0.5f, 0.5f), style = stroke)
                drawCircle(resolvedColor, radius = strokeWidth * 0.55f, center = point(0.5f, 0.31f))
                drawLine(resolvedColor, point(0.5f, 0.46f), point(0.5f, 0.72f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.TABS -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.1f, 0.2f),
                    size = Size(width * 0.8f, height * 0.65f),
                    cornerRadius = CornerRadius(width * 0.07f),
                    style = stroke,
                )
                drawLine(resolvedColor, point(0.1f, 0.38f), point(0.9f, 0.38f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.34f, 0.2f), point(0.34f, 0.38f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.62f, 0.2f), point(0.62f, 0.38f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.WINDOWS -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.12f, 0.25f),
                    size = Size(width * 0.58f, height * 0.58f),
                    cornerRadius = CornerRadius(width * 0.07f),
                    style = stroke,
                )
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.3f, 0.1f),
                    size = Size(width * 0.58f, height * 0.58f),
                    cornerRadius = CornerRadius(width * 0.07f),
                    style = stroke,
                )
            }
            ConnectionsGlyph.IMAGE -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.1f, 0.16f),
                    size = Size(width * 0.8f, height * 0.68f),
                    cornerRadius = CornerRadius(width * 0.07f),
                    style = stroke,
                )
                drawCircle(
                    color = resolvedColor,
                    radius = width * 0.085f,
                    center = point(0.68f, 0.36f),
                    style = stroke,
                )
                val landscape = Path().apply {
                    moveTo(width * 0.18f, height * 0.72f)
                    lineTo(width * 0.39f, height * 0.48f)
                    lineTo(width * 0.54f, height * 0.62f)
                    lineTo(width * 0.65f, height * 0.52f)
                    lineTo(width * 0.82f, height * 0.72f)
                }
                drawPath(landscape, resolvedColor, style = stroke)
            }
            ConnectionsGlyph.KEYBOARD -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.08f, 0.2f),
                    size = Size(width * 0.84f, height * 0.6f),
                    cornerRadius = CornerRadius(width * 0.08f),
                    style = stroke,
                )
                listOf(0.25f, 0.42f, 0.59f, 0.76f).forEach { x ->
                    drawCircle(resolvedColor, radius = strokeWidth * 0.55f, center = point(x, 0.4f))
                }
                drawLine(
                    resolvedColor,
                    point(0.27f, 0.62f),
                    point(0.73f, 0.62f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
            ConnectionsGlyph.MIC -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.34f, 0.08f),
                    size = Size(width * 0.32f, height * 0.5f),
                    cornerRadius = CornerRadius(width * 0.16f),
                    style = stroke,
                )
                drawArc(
                    color = resolvedColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = point(0.2f, 0.28f),
                    size = Size(width * 0.6f, height * 0.45f),
                    style = stroke,
                )
                drawLine(resolvedColor, point(0.5f, 0.72f), point(0.5f, 0.9f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, point(0.34f, 0.9f), point(0.66f, 0.9f), strokeWidth, StrokeCap.Round)
            }
            ConnectionsGlyph.STOP -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = point(0.27f, 0.27f),
                    size = Size(width * 0.46f, height * 0.46f),
                    cornerRadius = CornerRadius(width * 0.07f),
                )
            }
        }
    }
}
