package de.kalorienkompass.domain

import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.SettingsRepository
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
            val calories = goals.customCalorieGoal?.takeIf { it > 0 }
                ?: GoalCalculator.dailyCalories(profile, weight, date)
            GoalCalculator.targets(calories, goals)
        }.distinctUntilChanged()

    /** Latest weight on/before [date], or the profile's start weight if there is none. */
    fun observeCurrentWeight(date: LocalDate): Flow<Double> =
        combine(settings.profile, measurements.observeLatestWeightOnOrBefore(date)) { profile, weight ->
            weight ?: profile.startWeightKg
        }.distinctUntilChanged()
}
