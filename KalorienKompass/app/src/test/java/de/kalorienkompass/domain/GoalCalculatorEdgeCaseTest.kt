package de.kalorienkompass.domain

import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Edge cases of [GoalCalculator], [StepCalories] and the [TargetsUseCase] helpers (see also [GoalCalculatorTest]). */
class GoalCalculatorEdgeCaseTest {

    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun mealCalories_addUpToCaloriesExactly() {
        val splits = listOf(
            NutritionGoalSettings(),
            NutritionGoalSettings(breakfastPercent = 33, lunchPercent = 33, dinnerPercent = 33, snackPercent = 1),
            NutritionGoalSettings(breakfastPercent = 25, lunchPercent = 25, dinnerPercent = 25, snackPercent = 25),
            NutritionGoalSettings(breakfastPercent = 17, lunchPercent = 41, dinnerPercent = 29, snackPercent = 13),
        )
        for (goals in splits) {
            for (calories in 1200..3600 step 7) {
                val t = GoalCalculator.targets(calories, goals)
                assertEquals("sum for $calories / $goals", calories, t.mealCalories.values.sum())
                assertEquals(MealType.entries.toSet(), t.mealCalories.keys)
            }
        }
    }

    @Test
    fun mealCalories_defaultSplitOf2210_isDistributedByLargestRemainder() {
        // 30/40/25/5 % of 2210 = 663 / 884 / 552.5 / 110.5 → plain rounding would give 2211
        val t = GoalCalculator.targets(2210, NutritionGoalSettings())
        assertEquals(663, t.mealCalories[MealType.BREAKFAST])
        assertEquals(884, t.mealCalories[MealType.LUNCH])
        assertEquals(553, t.mealCalories[MealType.DINNER])
        assertEquals(110, t.mealCalories[MealType.SNACK])
    }

    @Test
    fun macroGrams_matchCalories() {
        for (calories in 1200..4000 step 13) {
            val t = GoalCalculator.targets(calories, NutritionGoalSettings())
            val kcalFromMacros = 4 * t.carbsG + 4 * t.proteinG + 9 * t.fatG
            // each gram value is rounded by at most 0.5 g: 2 + 2 + 4.5 kcal
            assertTrue("$calories vs $kcalFromMacros", abs(kcalFromMacros - calories) <= 8.5)
        }
    }

    @Test
    fun percentagesNotAddingUpTo100_areNormalized() {
        // 60/30/40 = 130 % → scaled to 46.2/23.1/30.8 %
        val goals = NutritionGoalSettings(
            carbsPercent = 60, proteinPercent = 30, fatPercent = 40,
            breakfastPercent = 50, lunchPercent = 50, dinnerPercent = 50, snackPercent = 50,
        )
        val t = GoalCalculator.targets(2000, goals)
        assertEquals(231, t.carbsG) // 2000 * 60/130 / 4 = 230.8
        assertEquals(115, t.proteinG) // 2000 * 30/130 / 4 = 115.4
        assertEquals(68, t.fatG) // 2000 * 40/130 / 9 = 68.4
        assertTrue(abs(4 * t.carbsG + 4 * t.proteinG + 9 * t.fatG - 2000) <= 8.5)
        assertEquals(listOf(500, 500, 500, 500), MealType.entries.map { t.mealCalories[it] })
    }

    @Test
    fun allZeroOrNegativePercentages_fallBackToDefaults() {
        val zero = NutritionGoalSettings(
            carbsPercent = 0, proteinPercent = 0, fatPercent = 0,
            breakfastPercent = 0, lunchPercent = -10, dinnerPercent = 0, snackPercent = 0,
        )
        val t = GoalCalculator.targets(2000, zero)
        val defaults = GoalCalculator.targets(2000, NutritionGoalSettings())
        assertEquals(defaults, t)

        val negativeFat = GoalCalculator.targets(2000, NutritionGoalSettings(carbsPercent = 80, proteinPercent = 20, fatPercent = -30))
        assertEquals(0, negativeFat.fatG)
        assertEquals(400, negativeFat.carbsG)
        assertEquals(100, negativeFat.proteinG)
    }

    @Test
    fun targets_zeroAndNegativeCalories() {
        val zero = GoalCalculator.targets(0, NutritionGoalSettings())
        assertEquals(0, zero.calories)
        assertEquals(0, zero.mealCalories.values.sum())
        assertEquals(0, zero.carbsG + zero.proteinG + zero.fatG)
        val negative = GoalCalculator.targets(-500, NutritionGoalSettings())
        assertEquals(0, negative.calories)
        assertTrue(negative.mealCalories.values.all { it == 0 })
    }

    @Test
    fun dailyCalories_invalidInputDoesNotCrash() {
        val p = UserProfile(sex = Sex.FEMALE, heightCm = Double.NaN)
        assertEquals(GoalCalculator.MIN_CALORIES_FEMALE, GoalCalculator.dailyCalories(p, 70.0, today))
        assertEquals(GoalCalculator.MIN_CALORIES_MALE, GoalCalculator.dailyCalories(UserProfile(), Double.NaN, today))
        assertEquals(
            GoalCalculator.MIN_CALORIES_MALE,
            GoalCalculator.dailyCalories(UserProfile(), Double.POSITIVE_INFINITY, today),
        )
    }

    @Test
    fun weeklyRate_isClampedAndInvalidRatesCountAsZero() {
        assertEquals(0.0, GoalCalculator.dailyAdjustment(GoalType.LOSE, -0.5), 1e-9)
        assertEquals(0.0, GoalCalculator.dailyAdjustment(GoalType.GAIN, Double.NaN), 1e-9)
        assertEquals(-1650.0, GoalCalculator.dailyAdjustment(GoalType.LOSE, 5.0), 1e-9) // clamped to 1.5 kg
        assertEquals(550.0, GoalCalculator.dailyAdjustment(GoalType.GAIN, 0.5), 1e-9)
        assertEquals(0.0, GoalCalculator.dailyAdjustment(GoalType.MAINTAIN, 1.0), 1e-9)
    }

    @Test
    fun dailyCalories_neverBelowMinimumAndRoundedToTen() {
        for (weight in listOf(40.0, 55.5, 80.0, 123.4, 180.0)) {
            for (level in ActivityLevel.entries) {
                for (sex in Sex.entries) {
                    val p = UserProfile(sex = sex, birthYear = 1980, heightCm = 170.0, activityLevel = level, weeklyRateKg = 1.0)
                    val kcal = GoalCalculator.dailyCalories(p, weight, today)
                    assertEquals(0, kcal % 10)
                    assertTrue(kcal >= GoalCalculator.minCalories(sex))
                }
            }
        }
    }

    @Test
    fun age_isClamped() {
        assertEquals(0, GoalCalculator.age(2030, today))
        assertEquals(120, GoalCalculator.age(1800, today))
        assertEquals(0, GoalCalculator.age(today.year, today))
    }

    @Test
    fun stepCalories_invalidInput() {
        assertEquals(0.0, StepCalories.kcal(-100, 80.0), 1e-9)
        assertEquals(0.0, StepCalories.kcal(10_000, 0.0), 1e-9)
        assertEquals(0.0, StepCalories.kcal(10_000, -80.0), 1e-9)
        assertEquals(0.0, StepCalories.kcal(10_000, Double.NaN), 1e-9)
        assertEquals(0.0, StepCalories.kcal(10_000, Double.POSITIVE_INFINITY), 1e-9)
        assertEquals(200.0, StepCalories.kcal(5_000, 80.0), 1e-9)
    }

    @Test
    fun targetsUseCase_weightFallbackAndCustomGoal() {
        assertEquals(82.5, TargetsUseCase.currentWeight(82.5, 90.0), 1e-9)
        assertEquals(90.0, TargetsUseCase.currentWeight(null, 90.0), 1e-9)
        assertEquals(90.0, TargetsUseCase.currentWeight(0.0, 90.0), 1e-9)
        assertEquals(90.0, TargetsUseCase.currentWeight(-3.0, 90.0), 1e-9)
        assertEquals(90.0, TargetsUseCase.currentWeight(Double.NaN, 90.0), 1e-9)

        val profile = UserProfile(birthYear = 1996, heightCm = 180.0, activityLevel = ActivityLevel.MODERATE)
        val computed = GoalCalculator.dailyCalories(profile, 80.0, today)
        assertEquals(1800, TargetsUseCase.calorieGoal(profile, NutritionGoalSettings(customCalorieGoal = 1800), 80.0, today))
        assertEquals(computed, TargetsUseCase.calorieGoal(profile, NutritionGoalSettings(customCalorieGoal = 0), 80.0, today))
        assertEquals(computed, TargetsUseCase.calorieGoal(profile, NutritionGoalSettings(customCalorieGoal = -5), 80.0, today))
        assertEquals(computed, TargetsUseCase.calorieGoal(profile, NutritionGoalSettings(), 80.0, today))
    }
}
