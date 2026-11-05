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

    /** Daily energy change for the selected goal (kcal/day, negative when losing weight). */
    fun dailyAdjustment(goalType: GoalType, weeklyRateKg: Double): Double = when (goalType) {
        GoalType.LOSE -> -weeklyRateKg * KCAL_PER_KG / 7.0
        GoalType.GAIN -> weeklyRateKg * KCAL_PER_KG / 7.0
        GoalType.MAINTAIN -> 0.0
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
        val rounded = roundTo10(raw)
        return maxOf(rounded, minCalories(profile.sex))
    }

    /** Carbs and protein 4 kcal/g, fat 9 kcal/g; meal split by the configured percentages. */
    fun targets(calories: Int, goals: NutritionGoalSettings): DailyTargets {
        val kcal = calories.toDouble()
        return DailyTargets(
            calories = calories,
            carbsG = (kcal * goals.carbsPercent / 100.0 / 4.0).roundToInt(),
            proteinG = (kcal * goals.proteinPercent / 100.0 / 4.0).roundToInt(),
            fatG = (kcal * goals.fatPercent / 100.0 / 9.0).roundToInt(),
            mealCalories = mapOf(
                MealType.BREAKFAST to (kcal * goals.breakfastPercent / 100.0).roundToInt(),
                MealType.LUNCH to (kcal * goals.lunchPercent / 100.0).roundToInt(),
                MealType.DINNER to (kcal * goals.dinnerPercent / 100.0).roundToInt(),
                MealType.SNACK to (kcal * goals.snackPercent / 100.0).roundToInt(),
            ),
        )
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
