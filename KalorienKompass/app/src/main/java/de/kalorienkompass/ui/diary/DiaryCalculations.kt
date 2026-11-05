package de.kalorienkompass.ui.diary

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.domain.StepCalories
import de.kalorienkompass.util.formatDateTime
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.monthLong
import de.kalorienkompass.util.weekdayLong
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Pure helpers of the diary feature (no Android dependencies, unit-tested in DiaryCalculationsTest).
 */

/** Plausible range for body weight input. */
internal const val MIN_WEIGHT_KG = 20.0
internal const val MAX_WEIGHT_KG = 350.0

/** Upper bound for manually entered steps per day. */
internal const val MAX_STEPS_PER_DAY = 200_000L

private const val MILLIS_PER_DAY = 86_400_000L

/** Calories and macros for one day as shown in the "Übersicht" card. */
data class DaySummary(
    val goalKcal: Int,
    val eatenKcal: Double,
    val burnedKcal: Double,
    /** whether [burnedKcal] is added to the daily budget (setting "addBurnedCalories") */
    val burnedIncluded: Boolean,
    val carbs: Double,
    val protein: Double,
    val fat: Double,
    val carbsGoal: Int,
    val proteinGoal: Int,
    val fatGoal: Int,
) {
    /** goal (+ burned kcal if included) */
    val budgetKcal: Double get() = goalKcal + if (burnedIncluded) burnedKcal else 0.0

    /** budget − eaten; negative when too much was eaten */
    val remainingKcal: Double get() = budgetKcal - eatenKcal

    /** true when the (rounded) remaining value is below zero */
    val isOver: Boolean get() = remainingKcal.roundToLong() < 0L

    /** share of the budget that was eaten, 0..1 */
    val ringProgress: Float
        get() = when {
            budgetKcal <= 0.0 -> if (eatenKcal > 0.0) 1f else 0f
            else -> (eatenKcal / budgetKcal).toFloat().coerceIn(0f, 1f)
        }
}

/** One row of the "Ernährung" section. */
data class MealSummary(
    val meal: MealType,
    val kcal: Double,
    val targetKcal: Int,
    val entryCount: Int,
    /** comma separated entry names ("Haferflocken, Banane") or "" */
    val preview: String,
) {
    val progress: Float
        get() = if (targetKcal > 0) (kcal / targetKcal).toFloat().coerceIn(0f, 1f) else 0f

    /** clearly above the meal's share of the daily goal */
    val isOver: Boolean get() = targetKcal > 0 && kcal > targetKcal * 1.1
}

/** Sum of the totals of [this] entries. */
internal fun List<DiaryEntryEntity>.totalNutrition(): Nutrition =
    fold(Nutrition.ZERO) { acc, e -> acc + Nutrition(e.kcal, e.protein, e.carbs, e.fat) }

/**
 * Burned calories of a day: active calories from Health Connect if present (and > 0),
 * otherwise estimated from the steps with [StepCalories].
 */
internal fun burnedKcal(activity: DailyActivityEntity?, weightKg: Double): Double {
    if (activity == null) return 0.0
    val active = activity.activeKcal
    if (active != null && active > 0.0) return active
    return StepCalories.kcal(activity.steps, weightKg)
}

/** True if [burnedKcal] uses the active calories reported by Health Connect. */
internal fun usesActiveCalories(activity: DailyActivityEntity?): Boolean =
    (activity?.activeKcal ?: 0.0) > 0.0

internal fun daySummary(
    entries: List<DiaryEntryEntity>,
    targets: DailyTargets,
    burned: Double,
    addBurned: Boolean,
): DaySummary {
    val total = entries.totalNutrition()
    return DaySummary(
        goalKcal = targets.calories,
        eatenKcal = total.kcal,
        burnedKcal = burned.coerceAtLeast(0.0),
        burnedIncluded = addBurned,
        carbs = total.carbs,
        protein = total.protein,
        fat = total.fat,
        carbsGoal = targets.carbsG,
        proteinGoal = targets.proteinG,
        fatGoal = targets.fatG,
    )
}

/** One summary per meal (always all four, in [MealType] order). */
internal fun mealSummaries(entries: List<DiaryEntryEntity>, targets: DailyTargets): List<MealSummary> {
    val byMeal = entries.groupBy { it.meal }
    return MealType.entries.map { meal ->
        val mealEntries = byMeal[meal].orEmpty()
        MealSummary(
            meal = meal,
            kcal = mealEntries.sumOf { it.kcal },
            targetKcal = targets.mealCalories[meal] ?: 0,
            entryCount = mealEntries.size,
            preview = mealEntries.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", "),
        )
    }
}

/** Share (0..1) of the daily calories that is planned for [meal]. */
internal fun mealShare(targets: DailyTargets, meal: MealType): Double {
    if (targets.calories <= 0) return 0.0
    return (targets.mealCalories[meal] ?: 0).toDouble() / targets.calories
}

/** Latest measurement on or before [date] (same order as the DAO: date, then createdAt). */
internal fun List<MeasurementEntity>.latestOnOrBefore(date: LocalDate): MeasurementEntity? =
    asSequence()
        .filter { !it.date.isAfter(date) }
        .maxWithOrNull(compareBy<MeasurementEntity>({ it.date }, { it.createdAt }, { it.id }))

internal fun roundToTenth(v: Double): Double = (v * 10.0).roundToLong() / 10.0

internal fun roundToHundredth(v: Double): Double = (v * 100.0).roundToLong() / 100.0

/** [current] + [delta], rounded to 0.1 kg and kept within the plausible range. */
internal fun adjustWeight(current: Double, delta: Double): Double =
    roundToTenth(current + delta).coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)

internal fun isPlausibleWeight(kg: Double): Boolean = kg in MIN_WEIGHT_KG..MAX_WEIGHT_KG

/** Kilograms with sign: "+0,4", "−2,1", "±0,0". */
internal fun formatSignedKg(v: Double): String {
    val r = roundToTenth(v)
    return when {
        r > 0.0 -> "+" + formatDecimal(r)
        r < 0.0 -> "−" + formatDecimal(-r)
        else -> "±" + formatDecimal(0.0)
    }
}

/** Progress from the start weight to the target weight (0..1) or null when start ≈ target. */
internal fun weightGoalProgress(currentKg: Double, startKg: Double, targetKg: Double): Float? {
    val total = startKg - targetKg
    if (abs(total) < 0.05) return null
    return ((startKg - currentKg) / total).toFloat().coerceIn(0f, 1f)
}

/** "Noch 5,4 kg bis zum Ziel", "Zielgewicht erreicht 🎉", "0,4 kg über dem Ziel" … */
internal fun weightGoalText(currentKg: Double, startKg: Double, targetKg: Double): String {
    val diff = roundToTenth(currentKg - targetKg)
    if (abs(diff) < 0.05) return "Zielgewicht erreicht 🎉"
    return when {
        targetKg < startKg - 0.05 ->
            if (diff > 0) "Noch ${formatDecimal(diff)} kg bis zum Ziel" else "Zielgewicht erreicht 🎉"
        targetKg > startKg + 0.05 ->
            if (diff < 0) "Noch ${formatDecimal(-diff)} kg bis zum Ziel" else "Zielgewicht erreicht 🎉"
        diff > 0 -> "${formatDecimal(diff)} kg über dem Ziel"
        else -> "${formatDecimal(-diff)} kg unter dem Ziel"
    }
}

/** True for entries that only carry calories (no amount). */
internal fun DiaryEntryEntity.isQuickEntry(): Boolean =
    source == EntrySource.QUICK || unitLabel.isBlank()

/** True if the amount is given in grams or millilitres. */
internal fun DiaryEntryEntity.isWeightUnit(): Boolean {
    val unit = unitLabel.trim().lowercase()
    return unit == "g" || unit == "ml"
}

/** "150 g", "250 ml", "2 × Scheibe (50 g)", "1,5 × Portion · 450 g", "Schnelleintrag". */
internal fun amountLabel(entry: DiaryEntryEntity): String {
    if (entry.isQuickEntry()) return "Schnelleintrag"
    val unit = entry.unitLabel.trim()
    if (entry.isWeightUnit()) return "${formatGrams(entry.amount)} $unit"
    val portion = if (unit.startsWith("1 ") && unit.length > 2) unit.substring(2) else unit
    return buildString {
        append(formatGrams(entry.amount)).append(" × ").append(portion)
        if (entry.source == EntrySource.RECIPE && entry.grams > 0.0) {
            append(" · ").append(formatGrams(entry.grams)).append(" g")
        }
    }
}

/**
 * The entry with a new amount; grams and nutrition are scaled proportionally
 * (same rule as DiaryRepository.updateAmount). Used for the live preview in the edit dialog.
 */
internal fun scaledEntry(entry: DiaryEntryEntity, newAmount: Double): DiaryEntryEntity {
    if (entry.amount <= 0.0) return entry.copy(amount = newAmount)
    val f = newAmount / entry.amount
    return entry.copy(
        amount = newAmount,
        grams = entry.grams * f,
        kcal = entry.kcal * f,
        protein = entry.protein * f,
        carbs = entry.carbs * f,
        fat = entry.fat * f,
    )
}

/** Step progress towards the goal, 0..1. */
internal fun stepProgress(steps: Long, goal: Int): Float =
    if (goal <= 0) 0f else (steps.toDouble() / goal).toFloat().coerceIn(0f, 1f)

/** Material3 DatePicker works with UTC midnight millis. */
internal fun LocalDate.toPickerMillis(): Long = toEpochDay() * MILLIS_PER_DAY

internal fun pickerMillisToDate(millis: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(millis, MILLIS_PER_DAY))

/** "Donnerstag, 5. November" (with the year if it differs from [today]'s year). */
internal fun fullDateLabel(date: LocalDate, today: LocalDate): String = buildString {
    append(weekdayLong(date)).append(", ")
    append(date.dayOfMonth).append(". ")
    append(monthLong(date))
    if (date.year != today.year) append(' ').append(date.year)
}

/** "heute, 14:05", "gestern, 09:30" or "03.11.2026, 14:05". */
internal fun lastSyncLabel(epochMillis: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val time = String.format(Locale.ROOT, "%02d:%02d", dateTime.hour, dateTime.minute)
    return when (dateTime.toLocalDate()) {
        today -> "heute, $time"
        today.minusDays(1) -> "gestern, $time"
        else -> formatDateTime(epochMillis)
    }
}

/** "1 Eintrag" / "3 Einträge" */
internal fun entryCountLabel(count: Int): String = if (count == 1) "1 Eintrag" else "$count Einträge"
