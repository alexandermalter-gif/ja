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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatKcal
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** One bar of a [BarChart] (bars are laid out evenly in list order). */
data class ChartBar(val date: LocalDate, val value: Double)

/**
 * Bar chart drawn on a Canvas: rounded bars growing in on first display, bars above the goal in
 * [overColor], dashed goal line, labelled grid lines, sparse x labels and a tooltip on tap or
 * horizontal drag. Bars with a value ≤ 0 are treated as "no data".
 *
 * The default height is 180 dp (pass a height in [modifier] to override it).
 *
 * @param labelFormatter x label of a bar; default: weekday for ≤ 7 daily bars, "03.11." otherwise,
 *   month for monthly bars (spacing is inferred from the dates).
 * @param tooltipTitle tooltip title of a bar; default: "Heute" / "Woche ab 03.11." / "November 2026".
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    overColor: Color = AppColors.over,
    goalValue: Double? = null,
    valueFormatter: (Double) -> String = { formatKcal(it) },
    labelFormatter: ((LocalDate) -> String)? = null,
    tooltipTitle: ((ChartBar) -> String)? = null,
    goalColor: Color = MaterialTheme.colorScheme.secondary,
    goalLabel: String = "Ziel",
    emptyText: String = "Noch keine Daten",
    noDataText: String = "Keine Daten",
) {
    val data = remember(bars) { bars.map { if (it.value.isFinite()) it else it.copy(value = 0.0) } }
    val goal = goalValue?.takeIf { it.isFinite() && it > 0.0 }
    val axis = remember(data, goal) { barAxis(data.map { it.value }, goal) }
    val spacing = remember(data) { inferSpacing(data.map { it.date }) }
    val hasValues = remember(data) { data.any { it.value > 0.0 } }

    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val axisStyle = typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val goalStyle = typography.labelSmall.copy(color = goalColor, fontWeight = FontWeight.SemiBold)
    val emptyStyle = typography.bodyMedium.copy(color = colors.onSurfaceVariant)
    val tooltipTitleStyle = typography.labelSmall.copy(color = colors.inverseOnSurface.copy(alpha = 0.75f))
    val tooltipValueStyle = typography.labelLarge.copy(color = colors.inverseOnSurface, fontWeight = FontWeight.Bold)
    val gridColor = colors.outline.copy(alpha = 0.55f)
    val trackColor = colors.onSurface.copy(alpha = 0.05f)
    val tooltipBackground = colors.inverseSurface

    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing))
    }
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val hitTester = remember { ChartHitTester() }

    val description = remember(data, goal) {
        if (!hasValues) {
            "Balkendiagramm: $emptyText"
        } else {
            val withData = data.filter { it.value > 0.0 }
            buildString {
                append("Balkendiagramm mit ${data.size} Balken, ")
                append("höchster Wert ${valueFormatter(withData.maxOf { it.value })}")
                if (goal != null) append(", $goalLabel ${valueFormatter(goal)}")
            }
        }
    }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
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
                        selected = hitTester.nearest(change.position.x)
                    },
                )
            }
            .drawWithCache {
                val n = data.size
                val gap = 6.dp.toPx()

                // ---- layout ----
                val tickLayouts = axis.ticks.map { textMeasurer.measure(axis.label(it), axisStyle) }
                val gutter = (tickLayouts.maxOfOrNull { it.size.width } ?: 0).toFloat() + gap
                val labelHeight = textMeasurer.measure("0", axisStyle).size.height.toFloat()
                val top = max(8.dp.toPx(), labelHeight / 2f)
                val bottom = max(top + 1f, size.height - labelHeight - gap)
                val plotLeft = gutter
                val plotRight = size.width
                val slot = if (n > 0) (plotRight - plotLeft) / n else 0f
                val barWidth = if (n > 0) {
                    (slot * 0.62f).coerceIn(min(2.dp.toPx(), slot * 0.9f), 28.dp.toPx())
                } else {
                    0f
                }
                val corner = min(barWidth / 2f, 6.dp.toPx())
                val centers = FloatArray(n) { plotLeft + slot * (it + 0.5f) }
                hitTester.xs = centers

                fun yOf(value: Double): Float = bottom - axis.fraction(value) * (bottom - top)

                // ---- sparse x labels (always including the last bar), without overlaps ----
                val xLabels = ArrayList<Pair<TextLayoutResult, Float>>()
                val minGap = 6.dp.toPx()
                var nextLeftLimit = Float.MAX_VALUE
                for (i in sparseLabelIndices(n).asReversed()) {
                    val text = labelFormatter?.invoke(data[i].date) ?: defaultBarLabel(data[i].date, spacing, n)
                    val layout = textMeasurer.measure(text, axisStyle)
                    val w = layout.size.width.toFloat()
                    val left = (centers[i] - w / 2f).coerceIn(0f, max(0f, size.width - w))
                    if (left + w + minGap <= nextLeftLimit) {
                        xLabels += layout to left
                        nextLeftLimit = left
                    }
                }

                val goalY = goal?.let { yOf(it) }
                val goalLayout = goal?.let { textMeasurer.measure("$goalLabel ${valueFormatter(it)}", goalStyle) }
                val emptyLayout = if (!hasValues) textMeasurer.measure(emptyText, emptyStyle) else null
                val barPath = Path()
                val gridStroke = 1.dp.toPx()
                val stagger = 0.35f

                onDrawBehind {
                    // grid + y labels
                    for (i in axis.ticks.indices) {
                        val y = yOf(axis.ticks[i])
                        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = gridStroke)
                        val layout = tickLayouts[i]
                        drawText(layout, topLeft = Offset(gutter - gap - layout.size.width, y - layout.size.height / 2f))
                    }
                    for ((layout, left) in xLabels) {
                        drawText(layout, topLeft = Offset(left, size.height - layout.size.height))
                    }

                    // tracks (only when the bars are wide enough to look calm)
                    if (n in 1..31) {
                        for (i in 0 until n) {
                            drawRoundRect(
                                color = trackColor,
                                topLeft = Offset(centers[i] - barWidth / 2f, top),
                                size = Size(barWidth, bottom - top),
                                cornerRadius = CornerRadius(corner),
                            )
                        }
                    }

                    // bars (staggered growth)
                    val p = progress.value
                    val sel = selected
                    for (i in 0 until n) {
                        val value = data[i].value
                        if (value <= 0.0) continue
                        val local = if (n <= 1) {
                            p
                        } else {
                            ((p - stagger * i / (n - 1)) / (1f - stagger)).coerceIn(0f, 1f)
                        }
                        val fullHeight = (bottom - yOf(value)).coerceAtLeast(1.dp.toPx())
                        val h = fullHeight * local
                        if (h <= 0f) continue
                        val r = min(corner, min(h, barWidth / 2f))
                        val left = centers[i] - barWidth / 2f
                        barPath.reset()
                        barPath.addRoundRect(
                            RoundRect(
                                left = left,
                                top = bottom - h,
                                right = left + barWidth,
                                bottom = bottom,
                                topLeftCornerRadius = CornerRadius(r),
                                topRightCornerRadius = CornerRadius(r),
                                bottomRightCornerRadius = CornerRadius.Zero,
                                bottomLeftCornerRadius = CornerRadius.Zero,
                            ),
                        )
                        val color = if (goal != null && value > goal) overColor else barColor
                        val alpha = if (sel != null && sel != i) 0.4f else 1f
                        drawPath(barPath, color, alpha = alpha)
                    }

                    // goal line
                    if (goalY != null && goalLayout != null) {
                        drawDashedGoalLine(goalColor.copy(alpha = 0.9f), goalY, plotLeft, plotRight)
                        val labelGap = 2.dp.toPx()
                        val above = goalY - goalLayout.size.height - labelGap
                        drawText(
                            goalLayout,
                            topLeft = Offset(plotRight - goalLayout.size.width, if (above >= 0f) above else goalY + labelGap),
                        )
                    }

                    if (emptyLayout != null) {
                        drawText(
                            emptyLayout,
                            topLeft = Offset(
                                (plotLeft + plotRight - emptyLayout.size.width) / 2f,
                                (top + bottom - emptyLayout.size.height) / 2f,
                            ),
                        )
                    }

                    // tooltip
                    if (sel != null && sel in 0 until n) {
                        val bar = data[sel]
                        val anchorY = if (bar.value > 0.0) yOf(bar.value) else bottom
                        drawChartTooltip(
                            textMeasurer = textMeasurer,
                            title = tooltipTitle?.invoke(bar) ?: defaultBarTooltipTitle(bar.date, spacing),
                            value = if (bar.value > 0.0) valueFormatter(bar.value) else noDataText,
                            anchor = Offset(centers[sel], min(anchorY, bottom)),
                            titleStyle = tooltipTitleStyle,
                            valueStyle = tooltipValueStyle,
                            background = tooltipBackground,
                        )
                    }
                }
            },
    )
}
