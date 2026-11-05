package de.kalorienkompass.ui.progress

import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.ui.components.charts.ChartBar
import de.kalorienkompass.ui.components.charts.ChartPoint
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.formatShortDate
import de.kalorienkompass.util.monthLong
import de.kalorienkompass.util.monthShort
import de.kalorienkompass.util.weekdayShort
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Pure (Android-free) aggregation helpers of the progress feature. Everything here is unit-tested
 * on the JVM (ProgressCalculationsTest); dates are always passed in explicitly.
 */

// ---------------------------------------------------------------------------------------------
// Ranges & buckets
// ---------------------------------------------------------------------------------------------

/** Selectable periods of the progress tab. [inPeriod] completes sentences like "−1,2 kg in 1 Monat". */
enum class ProgressRange(val label: String, val inPeriod: String) {
    WEEK("1 W", "in 7 Tagen"),
    MONTH("1 M", "in 1 Monat"),
    QUARTER("3 M", "in 3 Monaten"),
    HALF_YEAR("6 M", "in 6 Monaten"),
    YEAR("1 J", "in 1 Jahr"),
    ALL("Alle", "insgesamt");

    /** First day (inclusive) of the period ending [today], or null for [ALL]. */
    fun startDate(today: LocalDate): LocalDate? = when (this) {
        WEEK -> today.minusDays(6)
        MONTH -> today.minusMonths(1).plusDays(1)
        QUARTER -> today.minusMonths(3).plusDays(1)
        HALF_YEAR -> today.minusMonths(6).plusDays(1)
        YEAR -> today.minusYears(1).plusDays(1)
        ALL -> null
    }
}

/** Size of one bar / averaged point. */
enum class Granularity { DAY, WEEK, MONTH }

/** "Alle" shows at least one week so that a brand-new diary does not result in a single fat bar. */
const val MIN_ALL_RANGE_DAYS: Long = 7

/** Start of the chart period: the range start, or for "Alle" the first day with data (≥ 1 week back). */
fun periodStart(range: ProgressRange, today: LocalDate, firstDataDate: LocalDate?): LocalDate =
    range.startDate(today)
        ?: minOf(firstDataDate ?: today, today.minusDays(MIN_ALL_RANGE_DAYS - 1))

/** Daily bars up to 31 days, weekly bars up to ~1 year, monthly bars above. */
fun granularityFor(from: LocalDate, to: LocalDate): Granularity {
    val days = ChronoUnit.DAYS.between(from, to) + 1
    return when {
        days <= 31 -> Granularity.DAY
        days <= 371 -> Granularity.WEEK
        else -> Granularity.MONTH
    }
}

/** Monday of the (ISO) week of [date]. */
fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** First day of the bucket that contains [date]. */
fun bucketStart(date: LocalDate, granularity: Granularity): LocalDate = when (granularity) {
    Granularity.DAY -> date
    Granularity.WEEK -> weekStart(date)
    Granularity.MONTH -> date.withDayOfMonth(1)
}

private fun nextBucket(start: LocalDate, granularity: Granularity): LocalDate = when (granularity) {
    Granularity.DAY -> start.plusDays(1)
    Granularity.WEEK -> start.plusWeeks(1)
    Granularity.MONTH -> start.plusMonths(1)
}

/**
 * Bars for [from]..[to] (both inclusive). DAY: one bar per day (0 = no data). WEEK / MONTH: the
 * average per day over the days WITH data (value > 0) of the bucket, 0 if the bucket has none.
 * The bar date is the first day of the bucket (may lie before [from] for the first week).
 */
fun bucketBars(
    values: Map<LocalDate, Double>,
    from: LocalDate,
    to: LocalDate,
    granularity: Granularity,
): List<ChartBar> {
    if (to < from) return emptyList()
    val grouped: Map<LocalDate, List<Double>> = values.entries
        .filter { (date, value) -> date in from..to && value > 0.0 && value.isFinite() }
        .groupBy({ bucketStart(it.key, granularity) }, { it.value })
    val bars = ArrayList<ChartBar>()
    var bucket = bucketStart(from, granularity)
    while (bucket <= to) {
        val bucketValues = grouped[bucket]
        bars += ChartBar(bucket, if (bucketValues.isNullOrEmpty()) 0.0 else bucketValues.average())
        bucket = nextBucket(bucket, granularity)
    }
    return bars
}

/** Average of the days with data; [excludedToday] = today was left out because it is still running. */
data class DayAverage(val average: Double?, val days: Int, val excludedToday: Boolean)

/**
 * Average over the days with data (value > 0). Today is left out while it is still running,
 * unless it is the only day with data (so a new user still sees a value).
 */
fun averageOfCompletedDays(values: Map<LocalDate, Double>, today: LocalDate): DayAverage {
    val withData = values.filter { (_, v) -> v > 0.0 && v.isFinite() }
    val excludeToday = today in withData && withData.size > 1
    val used = if (excludeToday) withData - today else withData
    return DayAverage(
        average = if (used.isEmpty()) null else used.values.average(),
        days = used.size,
        excludedToday = excludeToday,
    )
}

/** The days that [averageOfCompletedDays] uses (same rules). */
fun completedDays(values: Map<LocalDate, Double>, today: LocalDate): Set<LocalDate> {
    val withData = values.filter { (_, v) -> v > 0.0 && v.isFinite() }.keys
    return if (today in withData && withData.size > 1) withData - today else withData
}

// ---------------------------------------------------------------------------------------------
// Measurements
// ---------------------------------------------------------------------------------------------

/** One value per day: the most recently created measurement of each day, sorted by date. */
fun latestPerDay(measurements: List<MeasurementEntity>): List<ChartPoint> =
    measurements
        .groupBy { it.date }
        .map { (date, list) ->
            val latest = list.maxWith(compareBy<MeasurementEntity> { it.createdAt }.thenBy { it.id })
            ChartPoint(date, latest.value)
        }
        .sortedBy { it.date }

/** Chart points plus the granularity they were averaged to (DAY = raw daily values). */
data class SampledPoints(val points: List<ChartPoint>, val granularity: Granularity)

/**
 * Keeps daily points as they are up to [maxPoints]; otherwise averages them per week (or per month
 * if that is still too many). An averaged point lies at the mean date of its values.
 */
fun downsample(points: List<ChartPoint>, maxPoints: Int = 120): SampledPoints {
    if (points.size <= maxPoints) return SampledPoints(points, Granularity.DAY)
    val weekly = averagePer(points, Granularity.WEEK)
    if (weekly.size <= maxPoints) return SampledPoints(weekly, Granularity.WEEK)
    return SampledPoints(averagePer(points, Granularity.MONTH), Granularity.MONTH)
}

private fun averagePer(points: List<ChartPoint>, granularity: Granularity): List<ChartPoint> =
    points.groupBy { bucketStart(it.date, granularity) }
        .toSortedMap()
        .values
        .map { group ->
            val meanDay = group.map { it.date.toEpochDay().toDouble() }.average().roundToLong()
            ChartPoint(LocalDate.ofEpochDay(meanDay), group.map { it.value }.average())
        }

/** Statistics of a measurement series for a period. */
data class SeriesStats(
    /** first value in the period */
    val start: Double?,
    /** last value in the period */
    val end: Double?,
    /** end − start (needs ≥ 2 days with values) */
    val change: Double?,
)

fun seriesStats(pointsInPeriod: List<ChartPoint>): SeriesStats {
    val first = pointsInPeriod.firstOrNull()?.value
    val last = pointsInPeriod.lastOrNull()?.value
    val change = if (pointsInPeriod.size >= 2 && first != null && last != null) last - first else null
    return SeriesStats(first, last, change)
}

/** How far the user is from the target weight. */
sealed interface GoalStatus {
    /** [kg] still to lose / gain (> 0) */
    data class Remaining(val kg: Double) : GoalStatus
    data object Reached : GoalStatus
    /** goal "Gewicht halten": [diff] = current − target outside the ±1 kg band */
    data class OffTarget(val diff: Double) : GoalStatus
}

fun goalStatus(current: Double, target: Double, goalType: GoalType): GoalStatus = when (goalType) {
    GoalType.LOSE -> if (current <= target + 0.05) GoalStatus.Reached else GoalStatus.Remaining(current - target)
    GoalType.GAIN -> if (current >= target - 0.05) GoalStatus.Reached else GoalStatus.Remaining(target - current)
    GoalType.MAINTAIN -> if (abs(current - target) <= 1.0) GoalStatus.Reached else GoalStatus.OffTarget(current - target)
}

/** Share (0…1) of the way from [start] to [target] that is done; null if start ≈ target. */
fun goalProgress(start: Double, current: Double, target: Double): Double? {
    val total = target - start
    if (abs(total) < 0.05) return null
    return ((current - start) / total).coerceIn(0.0, 1.0)
}

/** Whether a weight change is good news for the user's goal. */
enum class Trend { GOOD, BAD, NEUTRAL }

fun weightTrend(change: Double, goalType: GoalType): Trend = when {
    abs(change) < 0.05 -> Trend.NEUTRAL
    goalType == GoalType.LOSE -> if (change < 0) Trend.GOOD else Trend.BAD
    goalType == GoalType.GAIN -> if (change > 0) Trend.GOOD else Trend.BAD
    else -> Trend.NEUTRAL
}

/** Range of values accepted in the measurement dialog. */
fun MeasurementType.plausibleRange(): ClosedFloatingPointRange<Double> = when (this) {
    MeasurementType.WEIGHT -> 20.0..400.0
    MeasurementType.BODY_FAT -> 2.0..75.0
    MeasurementType.WAIST, MeasurementType.HIP, MeasurementType.CHEST -> 30.0..250.0
    MeasurementType.UPPER_ARM -> 10.0..100.0
    MeasurementType.THIGH -> 20.0..150.0
}

/** Body measurements shown in "Körpermaße" (everything except the weight). */
val BODY_MEASUREMENT_TYPES: List<MeasurementType> = MeasurementType.entries.filter { it != MeasurementType.WEIGHT }

// ---------------------------------------------------------------------------------------------
// Nutrition
// ---------------------------------------------------------------------------------------------

/** Energy share of the macros in percent (sums to exactly 100). */
data class MacroSplit(val carbs: Int, val protein: Int, val fat: Int)

/** Energy split with 4 / 4 / 9 kcal per g, rounded with the largest-remainder method; null if no macros. */
fun macroEnergySplit(carbsG: Double, proteinG: Double, fatG: Double): MacroSplit? {
    val energy = doubleArrayOf(
        carbsG.coerceAtLeast(0.0) * 4.0,
        proteinG.coerceAtLeast(0.0) * 4.0,
        fatG.coerceAtLeast(0.0) * 9.0,
    )
    val total = energy.sum()
    if (total <= 0.0 || !total.isFinite()) return null
    val raw = energy.map { it / total * 100.0 }
    val result = raw.map { floor(it).toInt() }.toIntArray()
    var missing = 100 - result.sum()
    for (i in raw.indices.sortedByDescending { raw[it] - floor(raw[it]) }) {
        if (missing <= 0) break
        result[i]++
        missing--
    }
    return MacroSplit(result[0], result[1], result[2])
}

// ---------------------------------------------------------------------------------------------
// History list
// ---------------------------------------------------------------------------------------------

/** A measurement with the difference to the chronologically previous one. */
data class HistoryEntry(val measurement: MeasurementEntity, val delta: Double?)

data class HistoryMonth(val month: YearMonth, val entries: List<HistoryEntry>)

/** Newest first, grouped by month; delta = value − previous (older) value. */
fun buildHistory(measurements: List<MeasurementEntity>): List<HistoryMonth> {
    val sorted = measurements.sortedWith(compareBy<MeasurementEntity>({ it.date }, { it.createdAt }, { it.id }))
    val entries = sorted.mapIndexed { i, m ->
        HistoryEntry(m, if (i == 0) null else m.value - sorted[i - 1].value)
    }
    return entries.asReversed()
        .groupBy { YearMonth.from(it.measurement.date) }
        .map { (month, list) -> HistoryMonth(month, list) }
}

// ---------------------------------------------------------------------------------------------
// Labels (German)
// ---------------------------------------------------------------------------------------------

private const val MINUS = "−"

/** "+0,4", "−1,2", "±0,0" ([decimals] decimals). */
fun formatSigned(value: Double, decimals: Int = 1): String {
    val threshold = 0.5 * Math.pow(10.0, -decimals.toDouble())
    return when {
        !value.isFinite() -> "–"
        abs(value) < threshold -> "±" + formatDecimal(0.0, decimals)
        value > 0 -> "+" + formatDecimal(value, decimals)
        else -> MINUS + formatDecimal(-value, decimals)
    }
}

/** "+120", "−250", "±0" for kcal. */
fun formatSignedKcal(value: Double): String = when {
    !value.isFinite() -> "–"
    abs(value) < 0.5 -> "±0"
    value > 0 -> "+" + formatKcal(value)
    else -> MINUS + formatKcal(-value)
}

/** "83,4 kg", "22,5 %", "84,0 cm" */
fun formatMeasurement(value: Double, type: MeasurementType): String = "${formatDecimal(value, 1)} ${type.unit}"

/** "November 2026" */
fun monthTitle(month: YearMonth): String = "${monthLong(month.atDay(1))} ${month.year}"

/** Tooltip title of a bar / averaged point: "Heute", "Diese Woche", "Woche ab 27.10.", "November 2026". */
fun bucketTitle(date: LocalDate, granularity: Granularity, today: LocalDate): String = when (granularity) {
    Granularity.DAY -> dayLabel(date, today)
    Granularity.WEEK -> {
        val start = weekStart(date)
        when {
            start == weekStart(today) -> "Diese Woche"
            start.year != today.year -> "Woche ab ${formatShortDate(start)}${start.year}"
            else -> "Woche ab ${formatShortDate(start)}"
        }
    }
    Granularity.MONTH -> {
        if (YearMonth.from(date) == YearMonth.from(today)) "Dieser Monat" else monthTitle(YearMonth.from(date))
    }
}

/** X label of a bar: "Mo." (≤ 7 daily bars), "03.11." (daily / weekly) or "Nov." / "Nov. ’25". */
fun bucketAxisLabel(date: LocalDate, granularity: Granularity, barCount: Int, today: LocalDate): String =
    when (granularity) {
        Granularity.DAY -> if (barCount <= 7) weekdayShort(date) else formatShortDate(date)
        Granularity.WEEK -> formatShortDate(date)
        Granularity.MONTH ->
            if (date.year == today.year) {
                monthShort(date)
            } else {
                "${monthShort(date)} ’${(date.year % 100).toString().padStart(2, '0')}"
            }
    }
