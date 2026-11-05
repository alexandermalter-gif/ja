package de.kalorienkompass.domain

import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GoalCalculatorTest {

    private val today = LocalDate.of(2026, 6, 1)

    private fun profile(
        sex: Sex = Sex.MALE,
        birthYear: Int = 1996,
        heightCm: Double = 180.0,
        level: ActivityLevel = ActivityLevel.MODERATE,
        goal: GoalType = GoalType.LOSE,
        rate: Double = 0.5,
    ) = UserProfile(
        onboardingDone = true,
        sex = sex,
        birthYear = birthYear,
        heightCm = heightCm,
        activityLevel = level,
        goalType = goal,
        weeklyRateKg = rate,
    )

    @Test
    fun age_isYearDifference() {
        assertEquals(36, GoalCalculator.age(1990, LocalDate.of(2026, 11, 5)))
        assertEquals(30, GoalCalculator.age(1996, today))
    }

    @Test
    fun bmr_mifflinStJeor_male() {
        // 10*80 + 6.25*180 - 5*30 + 5 = 1780
        assertEquals(1780.0, GoalCalculator.bmr(Sex.MALE, 80.0, 180.0, 30), 1e-9)
    }

    @Test
    fun bmr_mifflinStJeor_female() {
        // 10*60 + 6.25*165 - 5*25 - 161 = 1345.25
        assertEquals(1345.25, GoalCalculator.bmr(Sex.FEMALE, 60.0, 165.0, 25), 1e-9)
    }

    @Test
    fun tdee_multipliesPal() {
        assertEquals(2759.0, GoalCalculator.tdee(1780.0, ActivityLevel.MODERATE), 1e-9)
        assertEquals(2136.0, GoalCalculator.tdee(1780.0, ActivityLevel.SEDENTARY), 1e-9)
        assertEquals(3382.0, GoalCalculator.tdee(1780.0, ActivityLevel.VERY_ACTIVE), 1e-9)
    }

    @Test
    fun dailyCalories_lose_subtractsWeeklyRateAndRoundsToTen() {
        // TDEE 2759 - 0.5*7700/7 (= 550) = 2209 -> 2210
        assertEquals(2210, GoalCalculator.dailyCalories(profile(), 80.0, today))
    }

    @Test
    fun dailyCalories_maintain_isTdeeRounded() {
        // 2759 -> 2760
        assertEquals(2760, GoalCalculator.dailyCalories(profile(goal = GoalType.MAINTAIN), 80.0, today))
    }

    @Test
    fun dailyCalories_gain_addsWeeklyRate() {
        // 2759 + 0.25*7700/7 (= 275) = 3034 -> 3030
        assertEquals(3030, GoalCalculator.dailyCalories(profile(goal = GoalType.GAIN, rate = 0.25), 80.0, today))
    }

    @Test
    fun dailyCalories_usesCurrentWeight() {
        // 70 kg: BMR 1680, TDEE 2604, -550 = 2054 -> 2050
        assertEquals(2050, GoalCalculator.dailyCalories(profile(), 70.0, today))
    }

    @Test
    fun dailyCalories_femaleMinimumIs1200() {
        // BMR = 500 + 968.75 - 300 - 161 = 1007.75; TDEE 1209.3; -1100 -> 109 -> clamped
        val p = profile(sex = Sex.FEMALE, birthYear = 1966, heightCm = 155.0, level = ActivityLevel.SEDENTARY, rate = 1.0)
        assertEquals(1200, GoalCalculator.dailyCalories(p, 50.0, today))
    }

    @Test
    fun dailyCalories_maleMinimumIs1500() {
        val p = profile(sex = Sex.MALE, birthYear = 1956, heightCm = 165.0, level = ActivityLevel.SEDENTARY, rate = 1.0)
        assertEquals(1500, GoalCalculator.dailyCalories(p, 60.0, today))
    }

    @Test
    fun targets_defaultSplit() {
        val t = GoalCalculator.targets(2000, NutritionGoalSettings())
        assertEquals(2000, t.calories)
        assertEquals(250, t.carbsG) // 50 % / 4
        assertEquals(100, t.proteinG) // 20 % / 4
        assertEquals(67, t.fatG) // 30 % / 9 = 66.7
        assertEquals(600, t.mealCalories[MealType.BREAKFAST])
        assertEquals(800, t.mealCalories[MealType.LUNCH])
        assertEquals(500, t.mealCalories[MealType.DINNER])
        assertEquals(100, t.mealCalories[MealType.SNACK])
    }

    @Test
    fun targets_customSplit_roundsGrams() {
        val goals = NutritionGoalSettings(carbsPercent = 40, proteinPercent = 30, fatPercent = 30)
        val t = GoalCalculator.targets(2500, goals)
        assertEquals(250, t.carbsG)
        assertEquals(188, t.proteinG) // 187.5 -> 188
        assertEquals(83, t.fatG) // 83.3 -> 83
    }

    @Test
    fun bmi_andCategory() {
        assertEquals(24.69, GoalCalculator.bmi(80.0, 180.0), 0.01)
        assertEquals("Normalgewicht", GoalCalculator.bmiCategory(24.69))
        assertEquals("Übergewicht", GoalCalculator.bmiCategory(27.0))
        assertEquals("Untergewicht", GoalCalculator.bmiCategory(17.0))
        assertEquals("Adipositas", GoalCalculator.bmiCategory(31.0))
    }

    @Test
    fun stepCalories_estimate() {
        assertEquals(400.0, StepCalories.kcal(10_000, 80.0), 1e-9)
        assertEquals(0.0, StepCalories.kcal(0, 80.0), 1e-9)
    }
}
