package de.kalorienkompass.ui.components.charts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatShortDate
import de.kalorienkompass.util.monthLong
import de.kalorienkompass.util.monthShort
import de.kalorienkompass.util.weekdayShort
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Pure helpers shared by LineChart, BarChart and Sparkline: axis ranges ("nice" grid values),
 * axis labels, hit testing and a few drawing primitives (tooltip, dashed goal line, smooth path).
 */

// ---------------------------------------------------------------------------------------------
// Value axis
// ---------------------------------------------------------------------------------------------

/** Drawn value domain [min]..[max] and the values of the labelled horizontal grid lines. */
internal data class ValueAxis(
    val min: Double,
    val max: Double,
    val ticks: List<Double>,
    val decimals: Int,
) {
    /** 0 at [min], 1 at [max]. */
    fun fraction(value: Double): Float =
        if (max > min) ((value - min) / (max - min)).toFloat() else 0.5f

    fun label(value: Double): String = formatDecimal(value, decimals)
}

/** Axis of a line chart; [goalInside] = the goal value lies within the drawn domain. */
internal data class LineAxis(val axis: ValueAxis, val goalInside: Boolean)

private val NICE_FACTORS = doubleArrayOf(1.0, 2.0, 2.5, 5.0)
private const val EPS = 1e-9

/** Nice step candidates (1 / 2 / 2,5 / 5 × 10ⁿ) around [span] / [intervals], ascending. */
private fun stepCandidates(span: Double, intervals: Int): List<Double> {
    val raw = (if (span > 0.0 && span.isFinite()) span else 1.0) / intervals.coerceAtLeast(1)
    val exp = floor(log10(raw)).toInt()
    return (exp - 1..exp + 2).flatMap { e -> NICE_FACTORS.map { f -> f * 10.0.pow(e) } }.sorted()
}

/** Rounds away floating point noise (0.2 × 8 = 1.6000000000000001). */
private fun roundTo(value: Double, decimals: Int): Double {
    val factor = 10.0.pow(decimals.coerceIn(0, 6))
    return Math.round(value * factor) / factor
}

/** Number of decimals needed to print multiples of [step] exactly (0–3). */
internal fun decimalsForStep(step: Double): Int {
    for (d in 0..3) {
        val scaled = step * 10.0.pow(d)
        if (abs(scaled - Math.round(scaled)) < 1e-6) return d
    }
    return 3
}

/**
 * Axis from a nice value ≤ [lo] to a nice value ≥ [hi] with at most [maxIntervals] intervals
 * (used by bar charts, which start at 0).
 */
internal fun niceAxis(lo: Double, hi: Double, maxIntervals: Int = 4): ValueAxis {
    var a = lo
    var b = hi
    if (!a.isFinite() || !b.isFinite()) {
        a = 0.0
        b = 1.0
    }
    if (b < a) {
        val t = a
        a = b
        b = t
    }
    if (b - a < EPS) b = a + max(abs(a) * 0.1, 1.0)
    for (step in stepCandidates(b - a, maxIntervals)) {
        val start = floor(a / step + EPS) * step
        val end = ceil(b / step - EPS) * step
        val intervals = ((end - start) / step).roundToInt()
        if (intervals in 1..maxIntervals) {
            val decimals = decimalsForStep(step)
            return ValueAxis(
                min = roundTo(start, decimals),
                max = roundTo(end, decimals),
                ticks = List(intervals + 1) { roundTo(start + it * step, decimals) },
                decimals = decimals,
            )
        }
    }
    return ValueAxis(a, b, listOf(a, b), 1)
}

/**
 * Axis whose domain is exactly [lo]..[hi]; the labelled grid lines lie at nice values inside
 * the domain (at most [maxTicks], normally 3–4).
 */
internal fun ticksWithin(lo: Double, hi: Double, maxTicks: Int = 4): ValueAxis {
    var a = lo
    var b = hi
    if (!a.isFinite() || !b.isFinite()) {
        a = 0.0
        b = 1.0
    }
    if (b - a < EPS) {
        val d = max(abs(a) * 0.05, 1.0)
        a -= d
        b += d
    }
    for (step in stepCandidates(b - a, maxTicks)) {
        val first = ceil(a / step - EPS) * step
        val count = floor((b - first) / step + EPS).toInt() + 1
        if (count in 2..maxTicks) {
            val decimals = decimalsForStep(step)
            return ValueAxis(a, b, List(count) { roundTo(first + it * step, decimals) }, decimals)
        }
    }
    return ValueAxis(a, b, listOf(a, b), 1)
}

/**
 * Value axis of a line chart: data range (at least ±1 % around flat data) plus 12 % padding.
 * The goal is only pulled into the domain when it is "near" the data (within one data span),
 * otherwise the chart shows an edge marker instead of squeezing the line.
 */
internal fun lineAxis(values: List<Double>, goal: Double?): LineAxis {
    val finite = values.filter { it.isFinite() }
    val g = goal?.takeIf { it.isFinite() }
    if (finite.isEmpty()) {
        val c = g ?: 0.0
        val d = max(abs(c) * 0.05, 1.0)
        return LineAxis(ticksWithin(c - d, c + d), g != null)
    }
    val dataMin = finite.min()
    val dataMax = finite.max()
    var lo = dataMin
    var hi = dataMax
    val minSpan = max(abs(hi) * 0.02, 0.5)
    if (hi - lo < minSpan) {
        val mid = (lo + hi) / 2.0
        lo = mid - minSpan / 2.0
        hi = mid + minSpan / 2.0
    }
    val span = hi - lo
    var goalInside = false
    if (g != null && g >= lo - span && g <= hi + span) {
        lo = min(lo, g)
        hi = max(hi, g)
        goalInside = true
    }
    val pad = (hi - lo) * 0.12
    var a = lo - pad
    val b = hi + pad
    if (dataMin >= 0.0 && (g == null || g >= 0.0) && a < 0.0) a = 0.0
    return LineAxis(ticksWithin(a, b), goalInside)
}

/** Value axis of a bar chart: 0 … a bit above the largest bar (or the goal). */
internal fun barAxis(values: List<Double>, goal: Double?): ValueAxis {
    val top = max(values.filter { it.isFinite() }.maxOrNull() ?: 0.0, goal?.takeIf { it.isFinite() } ?: 0.0)
    return niceAxis(0.0, if (top > 0.0) top * 1.06 else 100.0, maxIntervals = 4)
}

// ---------------------------------------------------------------------------------------------
// Date labels
// ---------------------------------------------------------------------------------------------

/** "03.11." for spans up to ~6 months, otherwise "Nov. ’26". */
internal fun axisDateLabel(date: LocalDate, spanDays: Long): String =
    if (spanDays > 185) {
        "${monthShort(date)} ’${(date.year % 100).toString().padStart(2, '0')}"
    } else {
        formatShortDate(date)
    }

/** Spacing of consecutive bars, inferred from their dates. */
internal enum class BarSpacing { DAILY, WEEKLY, MONTHLY }

internal fun inferSpacing(dates: List<LocalDate>): BarSpacing {
    if (dates.size < 2) return BarSpacing.DAILY
    val gaps = dates.zipWithNext { a, b -> abs(ChronoUnit.DAYS.between(a, b)) }.sorted()
    val median = gaps[gaps.size / 2]
    return when {
        median <= 1L -> BarSpacing.DAILY
        median <= 8L -> BarSpacing.WEEKLY
        else -> BarSpacing.MONTHLY
    }
}

/** Default x label of a bar: "Mo." (≤ 7 daily bars), "03.11." or "Nov.". */
internal fun defaultBarLabel(date: LocalDate, spacing: BarSpacing, count: Int): String = when (spacing) {
    BarSpacing.DAILY -> if (count <= 7) weekdayShort(date) else formatShortDate(date)
    BarSpacing.WEEKLY -> formatShortDate(date)
    BarSpacing.MONTHLY -> monthShort(date)
}

/** Default tooltip title of a bar: "Heute", "Woche ab 03.11." or "November 2026". */
internal fun defaultBarTooltipTitle(date: LocalDate, spacing: BarSpacing): String = when (spacing) {
    BarSpacing.DAILY -> dayLabel(date)
    BarSpacing.WEEKLY -> "Woche ab ${formatShortDate(date)}"
    BarSpacing.MONTHLY -> "${monthLong(date)} ${date.year}"
}

/** Indices of the bars that get an x label: at most [maxLabels], always including the last one. */
internal fun sparseLabelIndices(count: Int, maxLabels: Int = 7): List<Int> {
    if (count <= 0) return emptyList()
    if (count <= maxLabels) return (0 until count).toList()
    val step = ceil(count / maxLabels.toDouble()).toInt().coerceAtLeast(1)
    return (count - 1 downTo 0 step step).toList().reversed()
}

// ---------------------------------------------------------------------------------------------
// Hit testing & drawing
// ---------------------------------------------------------------------------------------------

/**
 * X positions of the data points, written while drawing and read by the gesture handlers.
 * Deliberately NOT observable state (writing state during drawing would cause redraw loops).
 */
internal class ChartHitTester {
    var xs: FloatArray = FloatArray(0)

    /** Index of the point closest to [x], or null if there are no points. */
    fun nearest(x: Float): Int? {
        val positions = xs
        if (positions.isEmpty()) return null
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in positions.indices) {
            val d = abs(positions[i] - x)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }
}

/** Smooth curve through the points (cubic segments with horizontal tangents → no overshoot). */
internal fun smoothLinePath(xs: FloatArray, ys: FloatArray): Path {
    val path = Path()
    val n = min(xs.size, ys.size)
    if (n == 0) return path
    path.moveTo(xs[0], ys[0])
    for (i in 1 until n) {
        val cx = (xs[i - 1] + xs[i]) / 2f
        path.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i])
    }
    return path
}

/** Text with a small rounded backdrop so it stays legible on top of bars and lines. */
internal fun DrawScope.drawLabelWithBackdrop(layout: TextLayoutResult, topLeft: Offset, backdrop: Color) {
    val padH = 4.dp.toPx()
    val padV = 1.dp.toPx()
    drawRoundRect(
        color = backdrop,
        topLeft = Offset(topLeft.x - padH, topLeft.y - padV),
        size = Size(layout.size.width + 2 * padH, layout.size.height + 2 * padV),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawText(layout, topLeft = topLeft)
}

/** Dashed horizontal goal line from [startX] to [endX]. */
internal fun DrawScope.drawDashedGoalLine(color: Color, y: Float, startX: Float, endX: Float) {
    drawLine(
        color = color,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f),
    )
}

/**
 * Two-line tooltip (title + value) in a rounded box above [anchor] (below it if there is no room),
 * kept inside the drawing area horizontally.
 */
internal fun DrawScope.drawChartTooltip(
    textMeasurer: TextMeasurer,
    title: String,
    value: String,
    anchor: Offset,
    titleStyle: TextStyle,
    valueStyle: TextStyle,
    background: Color,
) {
    val maxTextWidth = (size.width - 20.dp.toPx()).toInt().coerceAtLeast(1)
    val titleLayout = textMeasurer.measure(
        text = title,
        style = titleStyle,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxTextWidth),
    )
    val valueLayout = textMeasurer.measure(
        text = value,
        style = valueStyle,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxTextWidth),
    )
    val padH = 10.dp.toPx()
    val padV = 6.dp.toPx()
    val gap = 1.dp.toPx()
    val width = max(titleLayout.size.width, valueLayout.size.width) + padH * 2f
    val height = titleLayout.size.height + valueLayout.size.height + gap + padV * 2f
    val margin = 12.dp.toPx()
    val left = (anchor.x - width / 2f).coerceIn(0f, max(0f, size.width - width))
    var top = anchor.y - height - margin
    if (top < 0f) top = (anchor.y + margin).coerceAtMost(max(0f, size.height - height))
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.18f),
        topLeft = Offset(left, top + 2.dp.toPx()),
        size = Size(width, height),
        cornerRadius = CornerRadius(10.dp.toPx()),
    )
    drawRoundRect(
        color = background,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(10.dp.toPx()),
    )
    drawText(titleLayout, topLeft = Offset(left + padH, top + padV))
    drawText(valueLayout, topLeft = Offset(left + padH, top + padV + titleLayout.size.height + gap))
}
