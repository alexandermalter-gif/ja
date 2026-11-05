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
import de.kalorienkompass.ui.components.charts.ChartBar
import de.kalorienkompass.ui.components.charts.ChartPoint
import java.time.LocalDate

sealed interface ProgressUiState {
    data object Loading : ProgressUiState
    data class Error(val message: String) : ProgressUiState
    data class Ready(val content: ProgressContent) : ProgressUiState
}

data class ProgressContent(
    val range: ProgressRange,
    val today: LocalDate,
    val weight: WeightCardState,
    val calories: CaloriesCardState,
    /** null = no completed day with entries in the period */
    val macros: MacrosCardState?,
    val steps: StepsCardState,
    /** only measurement types that have data */
    val body: List<BodyMeasurementState>,
    /** latest value per type (to prefill the add dialog) */
    val latestValues: Map<MeasurementType, Double>,
    /** latest MANUAL value per type and day (the add dialog replaces it) */
    val manualValues: Map<MeasurementType, Map<LocalDate, Double>>,
)

data class WeightCardState(
    val hasAnyData: Boolean,
    /** chart points of the period (daily, or weekly / monthly averages for long periods) */
    val points: List<ChartPoint>,
    val granularity: Granularity,
    val chartFrom: LocalDate,
    /** latest weight overall (not only in the period) */
    val current: Double?,
    val currentDate: LocalDate?,
    /** start / change within the period (daily values) */
    val stats: SeriesStats,
    val target: Double,
    val goalType: GoalType,
    val goalStatus: GoalStatus?,
    /** 0…1 from the profile's start weight to the target, null for "halten" */
    val goalProgress: Double?,
)

data class CaloriesCardState(
    val hasData: Boolean,
    val bars: List<ChartBar>,
    val granularity: Granularity,
    val goal: Int,
    val goalType: GoalType,
    val average: DayAverage,
    val daysOverGoal: Int,
    val daysUnderGoal: Int,
    val total: Double,
)

data class MacrosCardState(
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val split: MacroSplit,
    val goalSplit: MacroSplit,
    val goalCarbsG: Int,
    val goalProteinG: Int,
    val goalFatG: Int,
)

data class StepsCardState(
    val hasData: Boolean,
    val bars: List<ChartBar>,
    val granularity: Granularity,
    val goal: Int,
    val average: DayAverage,
    val total: Long,
    val daysGoalReached: Int,
    val bestDay: ChartBar?,
    val healthConnectEnabled: Boolean,
)

data class BodyMeasurementState(
    val type: MeasurementType,
    val latest: Double,
    val latestDate: LocalDate,
    /** change within the selected period (≥ 2 days with values) */
    val change: Double?,
    /** daily values of the period (for the sparkline) */
    val points: List<ChartPoint>,
)

/** Earliest date used for "Alle" queries. */
val EARLIEST_QUERY_DATE: LocalDate = LocalDate.of(1970, 1, 1)

/** Latest MANUAL value per day (that is the one `setDailyManualValue` replaces). */
fun manualValuesByDate(measurements: List<MeasurementEntity>): Map<LocalDate, Double> =
    measurements
        .filter { it.source == DataSource.MANUAL }
        .groupBy { it.date }
        .mapValues { (_, list) -> list.maxWith(compareBy<MeasurementEntity>({ it.createdAt }, { it.id })).value }

/**
 * Builds everything the progress tab shows from the raw data (pure, unit-tested).
 * [nutrition] and [activity] may contain days outside the period; they are filtered here.
 */
fun buildProgressContent(
    range: ProgressRange,
    today: LocalDate,
    nutrition: List<DailyNutritionTotals>,
    activity: List<DailyActivityEntity>,
    weights: List<MeasurementEntity>,
    body: Map<MeasurementType, List<MeasurementEntity>>,
    profile: UserProfile,
    settings: AppSettings,
    goals: NutritionGoalSettings,
    targets: DailyTargets,
): ProgressContent {
    val rangeStart = range.startDate(today)
    fun inPeriod(date: LocalDate): Boolean = date <= today && (rangeStart == null || date >= rangeStart)

    // ---- weight ----
    val weightDaily = latestPerDay(weights.filter { it.date <= today })
    val weightFrom = periodStart(range, today, weightDaily.firstOrNull()?.date)
    val weightInPeriod = weightDaily.filter { it.date >= weightFrom }
    val sampled = downsample(weightInPeriod)
    val currentWeight = weightDaily.lastOrNull()
    val weight = WeightCardState(
        hasAnyData = weightDaily.isNotEmpty(),
        points = sampled.points,
        granularity = sampled.granularity,
        chartFrom = weightFrom,
        current = currentWeight?.value,
        currentDate = currentWeight?.date,
        stats = seriesStats(weightInPeriod),
        target = profile.targetWeightKg,
        goalType = profile.goalType,
        goalStatus = currentWeight?.let { goalStatus(it.value, profile.targetWeightKg, profile.goalType) },
        goalProgress = currentWeight?.let {
            if (profile.goalType == GoalType.MAINTAIN) {
                null
            } else {
                goalProgress(profile.startWeightKg, it.value, profile.targetWeightKg)
            }
        },
    )

    // ---- calories & macros ----
    val nutritionInPeriod = nutrition.filter { inPeriod(it.date) }
    val kcalByDay: Map<LocalDate, Double> = nutritionInPeriod.associate { it.date to it.kcal }
    val caloriesFrom = periodStart(range, today, nutritionInPeriod.minOfOrNull { it.date })
    val caloriesGranularity = granularityFor(caloriesFrom, today)
    val completed = completedDays(kcalByDay, today)
    val calorieGoal = targets.calories
    val calories = CaloriesCardState(
        hasData = kcalByDay.values.any { it > 0.0 },
        bars = bucketBars(kcalByDay, caloriesFrom, today, caloriesGranularity),
        granularity = caloriesGranularity,
        goal = calorieGoal,
        goalType = profile.goalType,
        average = averageOfCompletedDays(kcalByDay, today),
        daysOverGoal = if (calorieGoal > 0) completed.count { (kcalByDay[it] ?: 0.0) > calorieGoal } else 0,
        daysUnderGoal = if (calorieGoal > 0) completed.count { (kcalByDay[it] ?: 0.0) <= calorieGoal } else 0,
        total = kcalByDay.values.filter { it > 0.0 }.sum(),
    )
    val macroDays = nutritionInPeriod.filter { it.date in completed }
    val macros = if (macroDays.isEmpty()) {
        null
    } else {
        val carbs = macroDays.map { it.carbs }.average()
        val protein = macroDays.map { it.protein }.average()
        val fat = macroDays.map { it.fat }.average()
        macroEnergySplit(carbs, protein, fat)?.let { split ->
            MacrosCardState(
                carbsG = carbs,
                proteinG = protein,
                fatG = fat,
                split = split,
                goalSplit = MacroSplit(goals.carbsPercent, goals.proteinPercent, goals.fatPercent),
                goalCarbsG = targets.carbsG,
                goalProteinG = targets.proteinG,
                goalFatG = targets.fatG,
            )
        }
    }

    // ---- steps ----
    val activityInPeriod = activity.filter { inPeriod(it.date) }
    val stepsByDay: Map<LocalDate, Double> = activityInPeriod.associate { it.date to it.steps.toDouble() }
    val stepsFrom = periodStart(range, today, activityInPeriod.minOfOrNull { it.date })
    val stepsGranularity = granularityFor(stepsFrom, today)
    val stepGoal = settings.stepGoal
    val steps = StepsCardState(
        hasData = stepsByDay.values.any { it > 0.0 },
        bars = bucketBars(stepsByDay, stepsFrom, today, stepsGranularity),
        granularity = stepsGranularity,
        goal = stepGoal,
        average = averageOfCompletedDays(stepsByDay, today),
        total = activityInPeriod.sumOf { it.steps.coerceAtLeast(0L) },
        daysGoalReached = if (stepGoal > 0) activityInPeriod.count { it.steps >= stepGoal } else 0,
        bestDay = activityInPeriod
            .filter { it.steps > 0L }
            .maxByOrNull { it.steps }
            ?.let { ChartBar(it.date, it.steps.toDouble()) },
        healthConnectEnabled = settings.healthConnectEnabled,
    )

    // ---- body measurements ----
    val bodyStates = BODY_MEASUREMENT_TYPES.mapNotNull { type ->
        val daily = latestPerDay(body[type].orEmpty().filter { it.date <= today })
        val latest = daily.lastOrNull() ?: return@mapNotNull null
        val pointsInPeriod = if (rangeStart == null) daily else daily.filter { it.date >= rangeStart }
        BodyMeasurementState(
            type = type,
            latest = latest.value,
            latestDate = latest.date,
            change = seriesStats(pointsInPeriod).change,
            points = pointsInPeriod,
        )
    }

    val latestValues = buildMap<MeasurementType, Double> {
        currentWeight?.let { put(MeasurementType.WEIGHT, it.value) }
        bodyStates.forEach { put(it.type, it.latest) }
    }
    val manualValues = buildMap<MeasurementType, Map<LocalDate, Double>> {
        put(MeasurementType.WEIGHT, manualValuesByDate(weights))
        BODY_MEASUREMENT_TYPES.forEach { type -> put(type, manualValuesByDate(body[type].orEmpty())) }
    }

    return ProgressContent(
        range = range,
        today = today,
        weight = weight,
        calories = calories,
        macros = macros,
        steps = steps,
        body = bodyStates,
        latestValues = latestValues,
        manualValues = manualValues,
    )
}
