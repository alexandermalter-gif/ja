package de.kalorienkompass.ui.progress

import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.ui.components.charts.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ProgressCalculationsTest {

    /** A Thursday; the week starts on Monday 2.11.2026. */
    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun rangeStartDates_areCalendarBased() {
        assertEquals(LocalDate.of(2026, 10, 30), ProgressRange.WEEK.startDate(today))
        assertEquals(LocalDate.of(2026, 10, 6), ProgressRange.MONTH.startDate(today))
        assertEquals(LocalDate.of(2026, 8, 6), ProgressRange.QUARTER.startDate(today))
        assertEquals(LocalDate.of(2026, 5, 6), ProgressRange.HALF_YEAR.startDate(today))
        assertEquals(LocalDate.of(2025, 11, 6), ProgressRange.YEAR.startDate(today))
        assertNull(ProgressRange.ALL.startDate(today))
    }

    @Test
    fun periodStart_allStartsAtFirstDataButShowsAtLeastOneWeek() {
        assertEquals(LocalDate.of(2026, 1, 10), periodStart(ProgressRange.ALL, today, LocalDate.of(2026, 1, 10)))
        assertEquals(today.minusDays(6), periodStart(ProgressRange.ALL, today, today.minusDays(2)))
        assertEquals(today.minusDays(6), periodStart(ProgressRange.ALL, today, null))
        assertEquals(today.minusDays(6), periodStart(ProgressRange.WEEK, today, LocalDate.of(2020, 1, 1)))
    }

    @Test
    fun granularity_dailyWeeklyMonthly() {
        assertEquals(Granularity.DAY, granularityFor(ProgressRange.WEEK.startDate(today)!!, today))
        assertEquals(Granularity.DAY, granularityFor(ProgressRange.MONTH.startDate(today)!!, today))
        assertEquals(Granularity.WEEK, granularityFor(ProgressRange.QUARTER.startDate(today)!!, today))
        assertEquals(Granularity.WEEK, granularityFor(ProgressRange.HALF_YEAR.startDate(today)!!, today))
        assertEquals(Granularity.WEEK, granularityFor(ProgressRange.YEAR.startDate(today)!!, today))
        assertEquals(Granularity.MONTH, granularityFor(today.minusYears(2), today))
    }

    @Test
    fun weekStart_isMonday() {
        assertEquals(LocalDate.of(2026, 11, 2), weekStart(today))
        assertEquals(LocalDate.of(2026, 11, 2), weekStart(LocalDate.of(2026, 11, 2)))
        assertEquals(LocalDate.of(2026, 11, 2), weekStart(LocalDate.of(2026, 11, 8)))
    }

    @Test
    fun bucketBars_daily_fillsDaysWithoutDataWithZero() {
        val values = mapOf(
            today to 1800.0,
            today.minusDays(2) to 2100.0,
            today.minusDays(10) to 999.0, // outside the period
        )
        val bars = bucketBars(values, today.minusDays(6), today, Granularity.DAY)
        assertEquals(7, bars.size)
        assertEquals(today.minusDays(6), bars.first().date)
        assertEquals(today, bars.last().date)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 2100.0, 0.0, 1800.0), bars.map { it.value })
    }

    @Test
    fun bucketBars_weekly_averagesOnlyDaysWithData() {
        val values = mapOf(
            LocalDate.of(2026, 10, 27) to 2000.0,
            LocalDate.of(2026, 10, 28) to 1000.0,
            LocalDate.of(2026, 10, 29) to 0.0, // no data → ignored
            LocalDate.of(2026, 11, 3) to 1500.0,
        )
        val bars = bucketBars(values, LocalDate.of(2026, 10, 21), today, Granularity.WEEK)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)),
            bars.map { it.date },
        )
        assertEquals(listOf(0.0, 1500.0, 1500.0), bars.map { it.value })
    }

    @Test
    fun bucketBars_monthly_ignoresDaysBeforeThePeriod() {
        val values = mapOf(
            LocalDate.of(2026, 9, 1) to 2000.0, // before "from"
            LocalDate.of(2026, 9, 30) to 1000.0,
            LocalDate.of(2026, 11, 1) to 1800.0,
        )
        val bars = bucketBars(values, LocalDate.of(2026, 9, 15), today, Granularity.MONTH)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)),
            bars.map { it.date },
        )
        assertEquals(listOf(1000.0, 0.0, 1800.0), bars.map { it.value })
    }

    @Test
    fun bucketBars_emptyWhenPeriodIsInverted() {
        assertTrue(bucketBars(mapOf(today to 1.0), today, today.minusDays(1), Granularity.DAY).isEmpty())
    }

    @Test
    fun averageOfCompletedDays_leavesOutTheRunningDay() {
        val values = mapOf(
            today to 500.0,
            today.minusDays(1) to 2000.0,
            today.minusDays(2) to 1800.0,
            today.minusDays(3) to 0.0,
        )
        val average = averageOfCompletedDays(values, today)
        assertEquals(1900.0, average.average!!, 1e-9)
        assertEquals(2, average.days)
        assertTrue(average.excludedToday)
        assertEquals(setOf(today.minusDays(1), today.minusDays(2)), completedDays(values, today))
    }

    @Test
    fun averageOfCompletedDays_keepsTodayIfItIsTheOnlyDay() {
        val average = averageOfCompletedDays(mapOf(today to 640.0), today)
        assertEquals(640.0, average.average!!, 1e-9)
        assertEquals(1, average.days)
        assertFalse(average.excludedToday)
    }

    @Test
    fun averageOfCompletedDays_withoutData() {
        val average = averageOfCompletedDays(mapOf(today to 0.0), today)
        assertNull(average.average)
        assertEquals(0, average.days)
        assertFalse(average.excludedToday)
    }

    @Test
    fun latestPerDay_takesTheNewestEntryOfEachDay() {
        val list = listOf(
            measurement(1, LocalDate.of(2026, 11, 1), 80.0, createdAt = 100),
            measurement(2, LocalDate.of(2026, 11, 1), 79.6, createdAt = 300),
            measurement(3, LocalDate.of(2026, 11, 1), 79.9, createdAt = 200),
            measurement(4, LocalDate.of(2026, 10, 30), 80.4, createdAt = 50),
        )
        assertEquals(
            listOf(ChartPoint(LocalDate.of(2026, 10, 30), 80.4), ChartPoint(LocalDate.of(2026, 11, 1), 79.6)),
            latestPerDay(list),
        )
        assertTrue(latestPerDay(emptyList()).isEmpty())
    }

    @Test
    fun downsample_keepsShortSeries() {
        val points = (0 until 50).map { ChartPoint(today.minusDays(49L - it), 80.0 + it) }
        val sampled = downsample(points, maxPoints = 120)
        assertEquals(Granularity.DAY, sampled.granularity)
        assertEquals(points, sampled.points)
    }

    @Test
    fun downsample_averagesPerWeekAtTheMeanDate() {
        val monday = LocalDate.of(2026, 10, 12)
        val points = (0 until 21).map { ChartPoint(monday.plusDays(it.toLong()), (it + 1).toDouble()) }
        val sampled = downsample(points, maxPoints = 10)
        assertEquals(Granularity.WEEK, sampled.granularity)
        assertEquals(listOf(4.0, 11.0, 18.0), sampled.points.map { it.value })
        assertEquals(
            listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29)),
            sampled.points.map { it.date },
        )
    }

    @Test
    fun downsample_fallsBackToMonths() {
        val start = LocalDate.of(2024, 1, 1)
        val points = (0 until 800).map { ChartPoint(start.plusDays(it.toLong()), 70.0) }
        val sampled = downsample(points, maxPoints = 60)
        assertEquals(Granularity.MONTH, sampled.granularity)
        assertTrue(sampled.points.size <= 60)
        assertTrue(sampled.points.all { it.value == 70.0 })
    }

    @Test
    fun seriesStats_startEndAndChange() {
        val stats = seriesStats(
            listOf(
                ChartPoint(today.minusDays(5), 82.0),
                ChartPoint(today.minusDays(2), 81.0),
                ChartPoint(today, 80.6),
            ),
        )
        assertEquals(82.0, stats.start!!, 1e-9)
        assertEquals(80.6, stats.end!!, 1e-9)
        assertEquals(-1.4, stats.change!!, 1e-9)

        val single = seriesStats(listOf(ChartPoint(today, 80.0)))
        assertEquals(80.0, single.start!!, 1e-9)
        assertNull(single.change)
        assertNull(seriesStats(emptyList()).start)
    }

    @Test
    fun goalStatus_dependsOnGoalType() {
        assertEquals(GoalStatus.Remaining(5.0), goalStatus(80.0, 75.0, GoalType.LOSE))
        assertEquals(GoalStatus.Reached, goalStatus(74.9, 75.0, GoalType.LOSE))
        assertEquals(GoalStatus.Remaining(3.0), goalStatus(62.0, 65.0, GoalType.GAIN))
        assertEquals(GoalStatus.Reached, goalStatus(65.2, 65.0, GoalType.GAIN))
        assertEquals(GoalStatus.Reached, goalStatus(70.8, 70.0, GoalType.MAINTAIN))
        assertEquals(GoalStatus.OffTarget(2.0), goalStatus(72.0, 70.0, GoalType.MAINTAIN))
    }

    @Test
    fun goalProgress_shareOfTheWay() {
        assertEquals(1.0 / 3.0, goalProgress(90.0, 85.0, 75.0)!!, 1e-9)
        assertEquals(0.4, goalProgress(60.0, 62.0, 65.0)!!, 1e-9)
        assertEquals(0.0, goalProgress(90.0, 92.0, 75.0)!!, 1e-9)
        assertEquals(1.0, goalProgress(90.0, 70.0, 75.0)!!, 1e-9)
        assertNull(goalProgress(75.0, 75.0, 75.0))
    }

    @Test
    fun weightTrend_dependsOnGoal() {
        assertEquals(Trend.GOOD, weightTrend(-0.8, GoalType.LOSE))
        assertEquals(Trend.BAD, weightTrend(0.8, GoalType.LOSE))
        assertEquals(Trend.GOOD, weightTrend(0.8, GoalType.GAIN))
        assertEquals(Trend.BAD, weightTrend(-0.8, GoalType.GAIN))
        assertEquals(Trend.NEUTRAL, weightTrend(0.8, GoalType.MAINTAIN))
        assertEquals(Trend.NEUTRAL, weightTrend(0.01, GoalType.LOSE))
    }

    @Test
    fun macroEnergySplit_usesEnergyAndSumsTo100() {
        // 1000 + 400 + 630 kcal → 49,26 % / 19,70 % / 31,03 %
        val split = macroEnergySplit(carbsG = 250.0, proteinG = 100.0, fatG = 70.0)!!
        assertEquals(MacroSplit(49, 20, 31), split)

        val thirds = macroEnergySplit(carbsG = 9.0, proteinG = 9.0, fatG = 4.0)!!
        assertEquals(100, thirds.carbs + thirds.protein + thirds.fat)

        assertNull(macroEnergySplit(0.0, 0.0, 0.0))
    }

    @Test
    fun buildHistory_newestFirstGroupedByMonthWithDeltaToPrevious() {
        val list = listOf(
            measurement(1, LocalDate.of(2026, 10, 28), 81.0, createdAt = 10),
            measurement(2, LocalDate.of(2026, 11, 2), 80.2, createdAt = 20),
            measurement(3, LocalDate.of(2026, 11, 4), 80.5, createdAt = 30),
        )
        val months = buildHistory(list)
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10)), months.map { it.month })
        assertEquals(listOf(3L, 2L), months[0].entries.map { it.measurement.id })
        assertEquals(0.3, months[0].entries[0].delta!!, 1e-9)
        assertEquals(-0.8, months[0].entries[1].delta!!, 1e-9)
        assertNull(months[1].entries.single().delta)
        assertTrue(buildHistory(emptyList()).isEmpty())
    }

    @Test
    fun manualValuesByDate_onlyManualAndNewestOfTheDay() {
        val list = listOf(
            measurement(1, today, 80.0, createdAt = 1),
            measurement(2, today, 79.8, createdAt = 2),
            measurement(3, today.minusDays(1), 81.0, createdAt = 3, source = DataSource.HEALTH_CONNECT),
        )
        assertEquals(mapOf(today to 79.8), manualValuesByDate(list))
    }

    @Test
    fun germanFormatting() {
        assertEquals("+0,4", formatSigned(0.4))
        assertEquals("−1,2", formatSigned(-1.2))
        assertEquals("±0,0", formatSigned(0.01))
        assertEquals("+1.250", formatSignedKcal(1250.0))
        assertEquals("−250", formatSignedKcal(-250.0))
        assertEquals("±0", formatSignedKcal(0.2))
        assertEquals("83,4 kg", formatMeasurement(83.44, MeasurementType.WEIGHT))
        assertEquals("22,5 %", formatMeasurement(22.5, MeasurementType.BODY_FAT))
        assertEquals("November 2026", monthTitle(YearMonth.of(2026, 11)))
        assertEquals("Heute, 05.11.2026", dialogDateText(today, today))
        assertEquals("Montag, 02.11.2026", dialogDateText(LocalDate.of(2026, 11, 2), today))
    }

    @Test
    fun bucketTitles() {
        assertEquals("Heute", bucketTitle(today, Granularity.DAY, today))
        assertEquals("Diese Woche", bucketTitle(LocalDate.of(2026, 11, 2), Granularity.WEEK, today))
        assertEquals("Woche ab 26.10.", bucketTitle(LocalDate.of(2026, 10, 26), Granularity.WEEK, today))
        assertEquals("Woche ab 29.12.2025", bucketTitle(LocalDate.of(2025, 12, 29), Granularity.WEEK, today))
        assertEquals("Dieser Monat", bucketTitle(LocalDate.of(2026, 11, 1), Granularity.MONTH, today))
        assertEquals("Oktober 2026", bucketTitle(LocalDate.of(2026, 10, 1), Granularity.MONTH, today))
    }

    @Test
    fun bucketAxisLabels() {
        assertEquals("Do.", bucketAxisLabel(today, Granularity.DAY, 7, today))
        assertEquals("05.11.", bucketAxisLabel(today, Granularity.DAY, 30, today))
        assertEquals("02.11.", bucketAxisLabel(LocalDate.of(2026, 11, 2), Granularity.WEEK, 13, today))
        assertEquals("Nov.", bucketAxisLabel(LocalDate.of(2026, 11, 1), Granularity.MONTH, 12, today))
        assertEquals("Dez. ’25", bucketAxisLabel(LocalDate.of(2025, 12, 1), Granularity.MONTH, 24, today))
    }

    @Test
    fun plausibleRanges() {
        assertTrue(83.4 in MeasurementType.WEIGHT.plausibleRange())
        assertFalse(4.0 in MeasurementType.WEIGHT.plausibleRange())
        assertTrue(22.5 in MeasurementType.BODY_FAT.plausibleRange())
        assertFalse(90.0 in MeasurementType.BODY_FAT.plausibleRange())
        assertTrue(91.5 in MeasurementType.WAIST.plausibleRange())
        assertTrue(34.0 in MeasurementType.UPPER_ARM.plausibleRange())
        assertEquals(MeasurementType.entries.size - 1, BODY_MEASUREMENT_TYPES.size)
        assertFalse(MeasurementType.WEIGHT in BODY_MEASUREMENT_TYPES)
    }

    @Test
    fun datePickerMillisRoundTrip() {
        assertEquals(today, utcMillisToLocalDate(today.toUtcMillis()))
        assertEquals(today, utcMillisToLocalDate(today.toUtcMillis() + 23L * 3_600_000L))
        assertEquals(LocalDate.of(1969, 12, 31), utcMillisToLocalDate(-1L))
    }

    private fun measurement(
        id: Long,
        date: LocalDate,
        value: Double,
        createdAt: Long,
        type: MeasurementType = MeasurementType.WEIGHT,
        source: DataSource = DataSource.MANUAL,
    ) = MeasurementEntity(id = id, type = type, date = date, value = value, source = source, createdAt = createdAt)
}
