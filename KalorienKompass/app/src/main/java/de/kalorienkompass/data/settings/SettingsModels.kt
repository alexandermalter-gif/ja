package de.kalorienkompass.data.settings

import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.model.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val onboardingDone: Boolean = false,
    val name: String = "",
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 1990,
    val heightCm: Double = 175.0,
    val startWeightKg: Double = 80.0,
    val targetWeightKg: Double = 75.0,
    val activityLevel: ActivityLevel = ActivityLevel.LIGHT,
    val goalType: GoalType = GoalType.LOSE,
    /** 0.25 / 0.5 / 0.75 / 1.0 */
    val weeklyRateKg: Double = 0.5,
)

@Serializable
data class NutritionGoalSettings(
    /** overrides the computed goal */
    val customCalorieGoal: Int? = null,
    val carbsPercent: Int = 50,
    val proteinPercent: Int = 20,
    val fatPercent: Int = 30,
    val breakfastPercent: Int = 30,
    val lunchPercent: Int = 40,
    val dinnerPercent: Int = 25,
    val snackPercent: Int = 5,
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val stepGoal: Int = 10000,
    val addBurnedCalories: Boolean = true,
    val healthConnectEnabled: Boolean = false,
    val writeWeightToHealthConnect: Boolean = false,
    val stepSensorEnabled: Boolean = false,
    val aiModel: AiModel = AiModel.OPUS,
    val lastHealthSyncAt: Long? = null,
)
