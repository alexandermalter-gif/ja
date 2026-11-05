package de.kalorienkompass.ui.progress

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DailyNutritionTotals
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.domain.DailyTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [buildProgressContent] and [buildHistoryContent] with realistic data. */
class ProgressContentTest {

    private val today = LocalDate.of(2026, 11, 5)
    private val profile = UserProfile(
        onboardingDone = true,
        startWeightKg = 90.0,
        targetWeightKg = 80.0,
        goalType = GoalType.LOSE,
    )
    private val targets = DailyTargets(calories = 2000, carbsG = 250, proteinG = 100, fatG = 67, mealCalories = emptyMap())

    private fun build(
        range: ProgressRange = ProgressRange.WEEK,
        nutrition: List<DailyNutritionTotals> = emptyList(),
        activity: List<DailyActivityEntity> = emptyList(),
        weights: List<MeasurementEntity> = emptyList(),
        body: Map<MeasurementType, List<MeasurementEntity>> = emptyMap(),
        settings: AppSettings = AppSettings(stepGoal = 10_000),
    ) = buildProgressContent(
        range = range,
        today = today,
        nutrition = nutrition,
        activity = activity,
        weights = weights,
        body = body,
        profile = profile,
        settings = settings,
        goals = NutritionGoalSettings(),
        targets = targets,
    )

    @Test
    fun withoutAnyData_everyCardIsEmpty() {
        val content = build()
        assertFalse(content.weight.hasAnyData)
        assertNull(content.weight.current)
        assertTrue(content.weight.points.isEmpty())
        assertFalse(content.calories.hasData)
        assertEquals(7, content.calories.bars.size)
        assertNull(content.calories.average.average)
        assertNull(content.macros)
        assertFalse(content.steps.hasData)
        assertEquals(7, content.steps.bars.size)
        assertTrue(content.body.isEmpty())
        assertTrue(content.latestValues.isEmpty())
    }

    @Test
    fun weightCard_statsOfThePeriodAndGoal() {
        val weights = listOf(
            weight(1, today.minusDays(40), 88.0),
            weight(2, today.minusDays(5), 86.0),
            weight(3, today.minusDays(2), 85.5),
            weight(4, today, 85.2),
        )
        val week = build(range = ProgressRange.WEEK, weights = weights).weight
        assertTrue(week.hasAnyData)
        assertEquals(3, week.points.size)
        assertEquals(Granularity.DAY, week.granularity)
        assertEquals(today.minusDays(6), week.chartFrom)
        assertEquals(85.2, week.current!!, 1e-9)
        assertEquals(today, week.currentDate)
        assertEquals(86.0, week.stats.start!!, 1e-9)
        assertEquals(-0.8, week.stats.change!!, 1e-9)
        assertEquals(GoalStatus.Remaining(85.2 - 80.0), week.goalStatus)
        assertEquals((85.2 - 90.0) / (80.0 - 90.0), week.goalProgress!!, 1e-9)

        val all = build(range = ProgressRange.ALL, weights = weights).weight
        assertEquals(4, all.points.size)
        assertEquals(today.minusDays(40), all.chartFrom)
        assertEquals(-2.8, all.stats.change!!, 1e-9)
    }

    @Test
    fun weightCard_latestWeightIsShownEvenWithoutMeasurementInThePeriod() {
        val weight = build(range = ProgressRange.WEEK, weights = listOf(weight(1, today.minusDays(30), 84.0))).weight
        assertTrue(weight.hasAnyData)
        assertTrue(weight.points.isEmpty())
        assertEquals(84.0, weight.current!!, 1e-9)
        assertNull(weight.stats.start)
        assertNull(weight.stats.change)
    }

    @Test
    fun weightCard_futureEntriesAreIgnored() {
        val weight = build(weights = listOf(weight(1, today, 84.0), weight(2, today.plusDays(1), 70.0))).weight
        assertEquals(84.0, weight.current!!, 1e-9)
        assertEquals(1, weight.points.size)
    }

    @Test
    fun caloriesCard_averagesCompletedDaysAndCountsGoalDays() {
        val nutrition = listOf(
            totals(today.minusDays(10), 3000.0), // outside the week
            totals(today.minusDays(3), 2400.0, carbs = 300.0, protein = 100.0, fat = 80.0),
            totals(today.minusDays(1), 1600.0, carbs = 200.0, protein = 80.0, fat = 50.0),
            totals(today, 500.0, carbs = 60.0, protein = 20.0, fat = 10.0), // still running
        )
        val content = build(range = ProgressRange.WEEK, nutrition = nutrition)
        val calories = content.calories
        assertTrue(calories.hasData)
        assertEquals(Granularity.DAY, calories.granularity)
        assertEquals(7, calories.bars.size)
        assertEquals(2400.0, calories.bars[3].value, 1e-9)
        assertEquals(2000.0, calories.average.average!!, 1e-9)
        assertEquals(2, calories.average.days)
        assertTrue(calories.average.excludedToday)
        assertEquals(1, calories.daysOverGoal)
        assertEquals(1, calories.daysUnderGoal)
        assertEquals(4500.0, calories.total, 1e-9)

        val macros = content.macros!!
        assertEquals(250.0, macros.carbsG, 1e-9)
        assertEquals(90.0, macros.proteinG, 1e-9)
        assertEquals(65.0, macros.fatG, 1e-9)
        assertEquals(100, macros.split.carbs + macros.split.protein + macros.split.fat)
        assertEquals(MacroSplit(50, 20, 30), macros.goalSplit)
        assertEquals(250, macros.goalCarbsG)
    }

    @Test
    fun caloriesCard_longPeriodsUseWeeklyBars() {
        val calories = build(range = ProgressRange.YEAR, nutrition = listOf(totals(today.minusDays(100), 2000.0))).calories
        assertEquals(Granularity.WEEK, calories.granularity)
        assertTrue(calories.bars.size in 52..54)
        assertEquals(2000.0, calories.bars.single { it.value > 0.0 }.value, 1e-9)
    }

    @Test
    fun caloriesCard_allUsesMonthlyBarsForMultiYearDiaries() {
        val nutrition = listOf(totals(today.minusYears(2), 2100.0), totals(today.minusDays(1), 1900.0))
        val calories = build(range = ProgressRange.ALL, nutrition = nutrition).calories
        assertEquals(Granularity.MONTH, calories.granularity)
        assertEquals(25, calories.bars.size)
    }

    @Test
    fun stepsCard_averageTotalGoalDaysAndBestDay() {
        val activity = listOf(
            steps(today.minusDays(2), 12_000),
            steps(today.minusDays(1), 8_000),
            steps(today, 3_000),
        )
        val content = build(range = ProgressRange.WEEK, activity = activity, settings = AppSettings(stepGoal = 10_000, healthConnectEnabled = true))
        val stepsCard = content.steps
        assertTrue(stepsCard.hasData)
        assertEquals(10_000.0, stepsCard.average.average!!, 1e-9)
        assertEquals(23_000L, stepsCard.total)
        assertEquals(1, stepsCard.daysGoalReached)
        assertEquals(12_000.0, stepsCard.bestDay!!.value, 1e-9)
        assertEquals(10_000, stepsCard.goal)
        assertTrue(stepsCard.healthConnectEnabled)
    }

    @Test
    fun bodyMeasurements_onlyTypesWithDataAndChangeInPeriod() {
        val body = mapOf(
            MeasurementType.WAIST to listOf(
                measurement(10, MeasurementType.WAIST, today.minusDays(20), 92.0),
                measurement(11, MeasurementType.WAIST, today.minusDays(3), 90.5),
            ),
            MeasurementType.HIP to emptyList(),
        )
        val content = build(range = ProgressRange.MONTH, body = body)
        val waist = content.body.single()
        assertEquals(MeasurementType.WAIST, waist.type)
        assertEquals(90.5, waist.latest, 1e-9)
        assertEquals(today.minusDays(3), waist.latestDate)
        assertEquals(-1.5, waist.change!!, 1e-9)
        assertEquals(2, waist.points.size)
        assertEquals(90.5, content.latestValues[MeasurementType.WAIST]!!, 1e-9)
        assertEquals(92.0, content.manualValues[MeasurementType.WAIST]!![today.minusDays(20)]!!, 1e-9)

        val week = build(range = ProgressRange.WEEK, body = body).body.single()
        assertNull(week.change)
        assertEquals(1, week.points.size)
    }

    @Test
    fun historyContent_chartListAndGoal() {
        val list = listOf(
            weight(1, today.minusDays(9), 82.0, createdAt = 1),
            weight(2, today.minusDays(9), 81.8, createdAt = 5),
            weight(3, today, 81.0, createdAt = 9, source = DataSource.HEALTH_CONNECT),
        )
        val history = buildHistoryContent(MeasurementType.WEIGHT, list, profile, today)
        assertEquals(3, history.count)
        assertEquals(2, history.chart.points.size)
        assertEquals(81.0, history.latest!!.value, 1e-9)
        assertEquals(81.8, history.first!!.value, 1e-9)
        assertEquals(-0.8, history.totalChange!!, 1e-9)
        assertEquals(80.0, history.goal!!, 1e-9)
        assertEquals(mapOf(today.minusDays(9) to 81.8), history.manualValues)
        assertEquals(2, history.months.size)
        assertEquals(3L, history.months.first().entries.first().measurement.id)

        val empty = buildHistoryContent(MeasurementType.WAIST, emptyList(), profile, today)
        assertNull(empty.goal)
        assertNull(empty.latest)
        assertNull(empty.totalChange)
        assertTrue(empty.months.isEmpty())
    }

    private fun weight(
        id: Long,
        date: LocalDate,
        value: Double,
        createdAt: Long = id,
        source: DataSource = DataSource.MANUAL,
    ) = measurement(id, MeasurementType.WEIGHT, date, value, createdAt, source)

    private fun measurement(
        id: Long,
        type: MeasurementType,
        date: LocalDate,
        value: Double,
        createdAt: Long = id,
        source: DataSource = DataSource.MANUAL,
    ) = MeasurementEntity(id = id, type = type, date = date, value = value, source = source, createdAt = createdAt)

    private fun totals(date: LocalDate, kcal: Double, carbs: Double = 0.0, protein: Double = 0.0, fat: Double = 0.0) =
        DailyNutritionTotals(date = date, kcal = kcal, protein = protein, carbs = carbs, fat = fat)

    private fun steps(date: LocalDate, count: Long) =
        DailyActivityEntity(date = date, steps = count, source = DataSource.HEALTH_CONNECT)
}
