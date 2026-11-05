package de.kalorienkompass.ui.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDate
import de.kalorienkompass.util.formatDecimal
import java.time.LocalDate
import kotlin.math.max

/** One value of a [LineChart]; the x position is proportional to the date. */
data class ChartPoint(val date: LocalDate, val value: Double)

/**
 * Line chart drawn on a Canvas: proportional date x-axis (labels first / middle / last),
 * auto-scaled y-axis with 3–4 labelled grid lines, dashed goal line, gradient fill below the
 * line, smooth entry animation and a tooltip (date + value) on tap or horizontal drag.
 * Handles 0 points (hint text) and 1 point (single dot).
 *
 * The default height is 200 dp (pass a height in [modifier] to override it).
 *
 * @param dateRange optional x domain, e.g. the selected period; the data extent is always included.
 * @param tooltipDateFormatter title of the tooltip (e.g. "Woche ab 03.11." for weekly averages).
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    goalValue: Double? = null,
    valueFormatter: (Double) -> String = { formatDecimal(it) },
    showDots: Boolean = true,
    dateRange: ClosedRange<LocalDate>? = null,
    goalColor: Color = MaterialTheme.colorScheme.secondary,
    goalLabel: String = "Ziel",
    tooltipDateFormatter: (LocalDate) -> String = { dayLabel(it) },
    emptyText: String = "Noch keine Daten",
) {
    val data = remember(points) { points.filter { it.value.isFinite() }.sortedBy { it.date } }
    val goal = goalValue?.takeIf { it.isFinite() }
    val axisInfo = remember(data, goal) { lineAxis(data.map { it.value }, goal) }
    val domain: LongRange? = remember(data, dateRange) {
        var start = data.firstOrNull()?.date?.toEpochDay()
        var end = data.lastOrNull()?.date?.toEpochDay()
        if (dateRange != null) {
            val rangeStart = dateRange.start.toEpochDay()
            val rangeEnd = dateRange.endInclusive.toEpochDay()
            start = if (start == null) rangeStart else minOf(start, rangeStart)
            end = if (end == null) rangeEnd else maxOf(end, rangeEnd)
        }
        if (start == null || end == null) null else start..maxOf(start, end)
    }

    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val axisStyle = typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val goalStyle = typography.labelSmall.copy(color = goalColor, fontWeight = FontWeight.SemiBold)
    val emptyStyle = typography.bodyMedium.copy(color = colors.onSurfaceVariant)
    val tooltipTitleStyle = typography.labelSmall.copy(color = colors.inverseOnSurface.copy(alpha = 0.75f))
    val tooltipValueStyle = typography.labelLarge.copy(color = colors.inverseOnSurface, fontWeight = FontWeight.Bold)
    val gridColor = colors.outline.copy(alpha = 0.55f)
    val surfaceColor = colors.surface
    val labelBackdrop = colors.surface.copy(alpha = 0.85f)
    val tooltipBackground = colors.inverseSurface

    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val hitTester = remember { ChartHitTester() }
    val haptic = LocalHapticFeedback.current

    val description = remember(data, goal) {
        if (data.isEmpty()) {
            "Liniendiagramm: $emptyText"
        } else {
            buildString {
                append("Liniendiagramm mit ${data.size} ")
                append(if (data.size == 1) "Wert" else "Werten")
                append(" vom ${formatDate(data.first().date)} bis ${formatDate(data.last().date)}, ")
                append("zuletzt ${valueFormatter(data.last().value)}")
                if (goal != null) append(", $goalLabel ${valueFormatter(goal)}")
            }
        }
    }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(data) {
                detectTapGestures(
                    onTap = { offset ->
                        val index = hitTester.nearest(offset.x)
                        selected = if (index == null || index == selected) null else index
                    },
                )
            }
            .pointerInput(data) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> selected = hitTester.nearest(offset.x) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val index = hitTester.nearest(change.position.x)
                        if (index != selected) {
                            selected = index
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
            }
            .drawWithCache {
                val n = data.size
                val axis = axisInfo.axis
                val dotRadius = 3.5.dp.toPx()
                val strokeWidth = 2.5.dp.toPx()
                val gap = 6.dp.toPx()

                // ---- layout ----
                val tickLayouts = axis.ticks.map { textMeasurer.measure(axis.label(it), axisStyle) }
                val gutter = (tickLayouts.maxOfOrNull { it.size.width } ?: 0).toFloat() + gap
                val labelHeight = textMeasurer.measure("0", axisStyle).size.height.toFloat()
                val top = max(8.dp.toPx(), labelHeight / 2f)
                val bottom = max(top + 1f, size.height - labelHeight - gap)
                val plotLeft = gutter
                val plotRight = size.width
                val inset = dotRadius + strokeWidth
                val xStart = plotLeft + inset
                val xEnd = max(xStart, plotRight - inset)

                fun yOf(value: Double): Float = bottom - axis.fraction(value) * (bottom - top)
                fun xOf(day: Long): Float {
                    if (domain == null || domain.last == domain.first) return (xStart + xEnd) / 2f
                    val f = (day - domain.first).toFloat() / (domain.last - domain.first).toFloat()
                    return xStart + f * (xEnd - xStart)
                }

                val xs = FloatArray(n) { xOf(data[it].date.toEpochDay()) }
                val ys = FloatArray(n) { yOf(data[it].value) }
                hitTester.xs = xs

                val linePath = smoothLinePath(xs, ys)
                val fillPath = if (n >= 2) {
                    Path().apply {
                        addPath(linePath)
                        lineTo(xs[n - 1], bottom)
                        lineTo(xs[0], bottom)
                        close()
                    }
                } else {
                    null
                }
                val fillBrush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0f)),
                    startY = top,
                    endY = bottom,
                )

                // ---- x labels: first / middle / last date of the domain ----
                val xLabels = ArrayList<Pair<TextLayoutResult, Float>>()
                if (domain != null) {
                    val span = domain.last - domain.first
                    val days = when {
                        span == 0L -> listOf(domain.first)
                        span >= 2L -> listOf(domain.first, domain.first + span / 2, domain.last)
                        else -> listOf(domain.first, domain.last)
                    }
                    days.forEachIndexed { index, day ->
                        val layout = textMeasurer.measure(axisDateLabel(LocalDate.ofEpochDay(day), span), axisStyle)
                        val w = layout.size.width.toFloat()
                        val x = xOf(day)
                        val left = when {
                            days.size == 1 -> x - w / 2f
                            index == 0 -> x - inset
                            index == days.lastIndex -> x + inset - w
                            else -> x - w / 2f
                        }.coerceIn(0f, max(0f, size.width - w))
                        xLabels += layout to left
                    }
                    val minGap = 8.dp.toPx()
                    if (xLabels.size == 3) {
                        val (first, firstLeft) = xLabels[0]
                        val (middle, middleLeft) = xLabels[1]
                        val lastLeft = xLabels[2].second
                        if (middleLeft < firstLeft + first.size.width + minGap ||
                            middleLeft + middle.size.width + minGap > lastLeft
                        ) {
                            xLabels.removeAt(1)
                        }
                    }
                    if (xLabels.size == 2 && xLabels[0].second + xLabels[0].first.size.width + minGap > xLabels[1].second) {
                        xLabels.removeAt(0)
                    }
                }

                // ---- goal ----
                // goal value + its label ("Ziel 75,0 kg", with an arrow if it lies outside the domain)
                val goalMarker: Pair<Double, TextLayoutResult>? = goal?.let {
                    val arrow = when {
                        axisInfo.goalInside -> ""
                        it < axis.min -> " ↓"
                        else -> " ↑"
                    }
                    it to textMeasurer.measure("$goalLabel ${valueFormatter(it)}$arrow", goalStyle)
                }
                val goalY = if (goal != null && axisInfo.goalInside) yOf(goal) else null
                val emptyLayout = if (n == 0) textMeasurer.measure(emptyText, emptyStyle) else null
                val lineStroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val dotStroke = Stroke(width = 2.dp.toPx())
                val gridStroke = 1.dp.toPx()

                onDrawBehind {
                    // grid + y labels
                    for (i in axis.ticks.indices) {
                        val y = yOf(axis.ticks[i])
                        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = gridStroke)
                        val layout = tickLayouts[i]
                        drawText(layout, topLeft = Offset(gutter - gap - layout.size.width, y - layout.size.height / 2f))
                    }
                    // x labels
                    for ((layout, left) in xLabels) {
                        drawText(layout, topLeft = Offset(left, size.height - layout.size.height))
                    }
                    // goal line or edge marker
                    if (goalMarker != null) {
                        val (goalAt, goalLayout) = goalMarker
                        val labelGap = 2.dp.toPx()
                        val labelLeft = plotRight - goalLayout.size.width - 4.dp.toPx()
                        if (goalY != null) {
                            drawDashedGoalLine(goalColor.copy(alpha = 0.9f), goalY, plotLeft, plotRight)
                            val above = goalY - goalLayout.size.height - labelGap
                            drawLabelWithBackdrop(
                                layout = goalLayout,
                                topLeft = Offset(labelLeft, if (above >= 0f) above else goalY + labelGap),
                                backdrop = labelBackdrop,
                            )
                        } else {
                            val labelTop = if (goalAt < axis.min) bottom - goalLayout.size.height - labelGap else labelGap
                            drawLabelWithBackdrop(goalLayout, Offset(labelLeft, labelTop), labelBackdrop)
                        }
                    }
                    if (emptyLayout != null) {
                        drawText(
                            emptyLayout,
                            topLeft = Offset(
                                (plotLeft + plotRight - emptyLayout.size.width) / 2f,
                                (top + bottom - emptyLayout.size.height) / 2f,
                            ),
                        )
                        return@onDrawBehind
                    }

                    // series (revealed from left to right)
                    val p = progress.value
                    val revealRight = if (n == 1) size.width else plotLeft + (plotRight - plotLeft) * p
                    val dotAlpha = if (n == 1) p else 1f
                    clipRect(left = 0f, top = 0f, right = revealRight, bottom = size.height) {
                        if (fillPath != null) drawPath(fillPath, fillBrush)
                        if (n >= 2) drawPath(linePath, lineColor, style = lineStroke)
                        if (showDots || n == 1) {
                            for (i in 0 until n) {
                                val c = Offset(xs[i], ys[i])
                                drawCircle(surfaceColor, dotRadius, c, alpha = dotAlpha)
                                drawCircle(lineColor, dotRadius, c, alpha = dotAlpha, style = dotStroke)
                            }
                        }
                    }

                    // selection + tooltip
                    val sel = selected
                    if (sel != null && sel in 0 until n) {
                        val c = Offset(xs[sel], ys[sel])
                        drawLine(
                            lineColor.copy(alpha = 0.45f),
                            Offset(c.x, top),
                            Offset(c.x, bottom),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawCircle(lineColor.copy(alpha = 0.22f), dotRadius * 2.8f, c)
                        drawCircle(lineColor, dotRadius * 1.5f, c)
                        drawCircle(surfaceColor, dotRadius * 0.7f, c)
                        drawChartTooltip(
                            textMeasurer = textMeasurer,
                            title = tooltipDateFormatter(data[sel].date),
                            value = valueFormatter(data[sel].value),
                            anchor = c,
                            titleStyle = tooltipTitleStyle,
                            valueStyle = tooltipValueStyle,
                            background = tooltipBackground,
                        )
                    }
                }
            },
    )
}
