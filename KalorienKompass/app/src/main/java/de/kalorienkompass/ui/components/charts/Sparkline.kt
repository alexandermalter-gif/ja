package de.kalorienkompass.ui.components.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * Tiny line chart without axes or interaction (for compact summary cards).
 * Size it via [modifier], e.g. `Modifier.size(width = 112.dp, height = 40.dp)`.
 */
@Composable
fun Sparkline(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    val data = remember(points) { points.filter { it.value.isFinite() }.sortedBy { it.date } }
    val dotFill = MaterialTheme.colorScheme.surface
    Spacer(
        modifier = modifier
            .clearAndSetSemantics { }
            .drawWithCache {
                val n = data.size
                val pad = 4.dp.toPx()
                if (n == 0 || size.width <= 2 * pad || size.height <= 2 * pad) {
                    return@drawWithCache onDrawBehind { }
                }
                val minValue = data.minOf { it.value }
                val maxValue = data.maxOf { it.value }
                val valueSpan = maxValue - minValue
                val firstDay = data.first().date.toEpochDay()
                val daySpan = (data.last().date.toEpochDay() - firstDay).toFloat()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                val xs = FloatArray(n) {
                    if (daySpan <= 0f) size.width / 2f else pad + (data[it].date.toEpochDay() - firstDay) / daySpan * w
                }
                val ys = FloatArray(n) {
                    if (valueSpan <= 1e-9) size.height / 2f else pad + (1f - ((data[it].value - minValue) / valueSpan).toFloat()) * h
                }
                val line = smoothLinePath(xs, ys)
                val fill = if (n >= 2) {
                    Path().apply {
                        addPath(line)
                        lineTo(xs[n - 1], size.height)
                        lineTo(xs[0], size.height)
                        close()
                    }
                } else {
                    null
                }
                val brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0f)),
                    startY = 0f,
                    endY = size.height,
                )
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val dotRadius = 3.dp.toPx()
                onDrawBehind {
                    if (fill != null) drawPath(fill, brush)
                    if (n >= 2) drawPath(line, lineColor, style = stroke)
                    val last = Offset(xs[n - 1], ys[n - 1])
                    drawCircle(dotFill, dotRadius, last)
                    drawCircle(lineColor, dotRadius, last, style = Stroke(width = 2.dp.toPx()))
                }
            },
    )
}
