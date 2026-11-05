package de.kalorienkompass.domain

import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.data.settings.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

class TargetsUseCase(
    private val settings: SettingsRepository,
    private val measurements: MeasurementRepository,
) {
    /**
     * Targets for [date] using the latest weight on/before [date] (fallback: profile.startWeightKg)
     * and the customCalorieGoal override.
     */
    fun observeTargets(date: LocalDate): Flow<DailyTargets> =
        combine(
            settings.profile,
            settings.goalSettings,
            observeCurrentWeight(date),
        ) { profile, goals, weight ->
            GoalCalculator.targets(calorieGoal(profile, goals, weight, date), goals)
        }.distinctUntilChanged()

    /**
     * Latest weight on/before [date], or the profile's start weight if there is none
     * (a stored weight ≤ 0 or NaN counts as none).
     */
    fun observeCurrentWeight(date: LocalDate): Flow<Double> =
        combine(settings.profile, measurements.observeLatestWeightOnOrBefore(date)) { profile, weight ->
            currentWeight(weight, profile.startWeightKg)
        }.distinctUntilChanged()

    internal companion object {
        fun currentWeight(latestWeightKg: Double?, startWeightKg: Double): Double =
            latestWeightKg?.takeIf { it.isFinite() && it > 0.0 } ?: startWeightKg

        fun calorieGoal(profile: UserProfile, goals: NutritionGoalSettings, weightKg: Double, date: LocalDate): Int =
            goals.customCalorieGoal?.takeIf { it > 0 } ?: GoalCalculator.dailyCalories(profile, weightKg, date)
    }
}
