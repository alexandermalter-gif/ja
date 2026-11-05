package de.kalorienkompass.domain

import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import java.time.LocalDate
import kotlin.math.roundToInt

data class DailyTargets(
    val calories: Int,
    val carbsG: Int,
    val proteinG: Int,
    val fatG: Int,
    val mealCalories: Map<MealType, Int>,
)

object GoalCalculator {

    /** kcal per kg of body fat */
    const val KCAL_PER_KG = 7700.0
    const val MIN_CALORIES_FEMALE = 1200
    const val MIN_CALORIES_MALE = 1500
    /** Highest weekly rate (kg) used for the goal calculation. */
    const val MAX_WEEKLY_RATE_KG = 1.5

    fun age(birthYear: Int, today: LocalDate): Int = (today.year - birthYear).coerceIn(0, 120)

    /** Mifflin-St Jeor: 10·kg + 6,25·cm − 5·Jahre + 5 (Männer) bzw. − 161 (Frauen). */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, age: Int): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
        return when (sex) {
            Sex.MALE -> base + 5.0
            Sex.FEMALE -> base - 161.0
        }
    }

    fun tdee(bmr: Double, level: ActivityLevel): Double = bmr * level.pal

    /**
     * Daily energy change for the selected goal (kcal/day, negative when losing weight).
     * The weekly rate is clamped to 0…[MAX_WEEKLY_RATE_KG] kg (invalid values count as 0).
     */
    fun dailyAdjustment(goalType: GoalType, weeklyRateKg: Double): Double {
        val rate = if (weeklyRateKg.isFinite()) weeklyRateKg.coerceIn(0.0, MAX_WEEKLY_RATE_KG) else 0.0
        return when (goalType) {
            GoalType.LOSE -> -rate * KCAL_PER_KG / 7.0
            GoalType.GAIN -> rate * KCAL_PER_KG / 7.0
            GoalType.MAINTAIN -> 0.0
        }
    }

    fun minCalories(sex: Sex): Int = if (sex == Sex.FEMALE) MIN_CALORIES_FEMALE else MIN_CALORIES_MALE

    /**
     * TDEE − / + weeklyRateKg·7700/7 (LOSE / GAIN), MAINTAIN = TDEE;
     * at least 1200 kcal (women) / 1500 kcal (men); rounded to the nearest 10.
     */
    fun dailyCalories(profile: UserProfile, currentWeightKg: Double, today: LocalDate): Int {
        val age = age(profile.birthYear, today)
        val bmr = bmr(profile.sex, currentWeightKg, profile.heightCm, age)
        val raw = tdee(bmr, profile.activityLevel) + dailyAdjustment(profile.goalType, profile.weeklyRateKg)
        val min = minCalories(profile.sex)
        // Invalid input (NaN weight/height) must not crash roundToInt(); fall back to the minimum.
        if (!raw.isFinite()) return min
        return maxOf(roundTo10(raw), min)
    }

    /**
     * Carbs and protein 4 kcal/g, fat 9 kcal/g; meal split by the configured percentages.
     * Percentages that do not add up to 100 are scaled proportionally (all 0 → defaults), so the
     * macro grams always add up to ≈ [calories]. The meal targets add up to [calories] exactly
     * (rounding remainders go to the meals with the largest fractions).
     */
    fun targets(calories: Int, goals: NutritionGoalSettings): DailyTargets {
        val total = calories.coerceAtLeast(0)
        val kcal = total.toDouble()
        val defaults = NutritionGoalSettings()
        val macros = weightsOrDefault(
            listOf(goals.carbsPercent, goals.proteinPercent, goals.fatPercent),
            listOf(defaults.carbsPercent, defaults.proteinPercent, defaults.fatPercent),
        )
        val macroSum = macros.sum().toDouble()
        val meals = weightsOrDefault(
            listOf(goals.breakfastPercent, goals.lunchPercent, goals.dinnerPercent, goals.snackPercent),
            listOf(defaults.breakfastPercent, defaults.lunchPercent, defaults.dinnerPercent, defaults.snackPercent),
        )
        val mealKcal = distribute(total, meals)
        return DailyTargets(
            calories = total,
            carbsG = (kcal * macros[0] / macroSum / 4.0).roundToInt(),
            proteinG = (kcal * macros[1] / macroSum / 4.0).roundToInt(),
            fatG = (kcal * macros[2] / macroSum / 9.0).roundToInt(),
            mealCalories = mapOf(
                MealType.BREAKFAST to mealKcal[0],
                MealType.LUNCH to mealKcal[1],
                MealType.DINNER to mealKcal[2],
                MealType.SNACK to mealKcal[3],
            ),
        )
    }

    /** Percentages as non-negative weights; [fallback] if they are all 0 (or negative). */
    private fun weightsOrDefault(percents: List<Int>, fallback: List<Int>): List<Int> {
        val weights = percents.map { it.coerceAtLeast(0) }
        return if (weights.sumOf { it.toLong() } > 0L) weights else fallback
    }

    /**
     * Splits [total] in proportion to [weights] (sum > 0) into integers that add up to [total]
     * exactly: largest remainder method in exact integer arithmetic (ties → earlier meal).
     */
    private fun distribute(total: Int, weights: List<Int>): List<Int> {
        val sum = weights.sumOf { it.toLong() }
        val result = IntArray(weights.size) { (total.toLong() * weights[it] / sum).toInt() }
        val remainders = LongArray(weights.size) { total.toLong() * weights[it] % sum }
        var remaining = total - result.sum()
        for (i in weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })) {
            if (remaining <= 0) break
            result[i]++
            remaining--
        }
        return result.toList()
    }

    /** Body mass index (kg/m²); 0 if the height is unknown. */
    fun bmi(weightKg: Double, heightCm: Double): Double {
        if (heightCm <= 0.0) return 0.0
        val m = heightCm / 100.0
        return weightKg / (m * m)
    }

    /** WHO category in German. */
    fun bmiCategory(bmi: Double): String = when {
        bmi <= 0.0 -> "–"
        bmi < 18.5 -> "Untergewicht"
        bmi < 25.0 -> "Normalgewicht"
        bmi < 30.0 -> "Übergewicht"
        else -> "Adipositas"
    }

    private fun roundTo10(value: Double): Int = (value / 10.0).roundToInt() * 10
}
