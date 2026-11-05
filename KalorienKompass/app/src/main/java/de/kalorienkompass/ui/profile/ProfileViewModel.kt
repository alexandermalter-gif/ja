package de.kalorienkompass.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.domain.TargetsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class ProfileUiState(
    val profile: UserProfile,
    val goals: NutritionGoalSettings,
    val settings: AppSettings,
    val targets: DailyTargets,
    /** latest weight on/before today, or the start weight */
    val currentWeightKg: Double,
    /** true if at least one weight entry exists */
    val hasWeightEntry: Boolean,
    val today: LocalDate,
) {
    val age: Int get() = GoalCalculator.age(profile.birthYear, today)
    val bmi: Double get() = GoalCalculator.bmi(currentWeightKg, profile.heightCm)
    val usesCustomGoal: Boolean get() = (goals.customCalorieGoal ?: 0) > 0
}

class ProfileViewModel(
    settings: SettingsRepository,
    targets: TargetsUseCase,
    measurements: MeasurementRepository,
) : ViewModel() {

    /** null while loading. "today" is re-evaluated whenever the screen subscribes again. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProfileUiState?> = flow { emit(LocalDate.now()) }
        .flatMapLatest { today ->
            combine(
                settings.profile,
                settings.goalSettings,
                settings.settings,
                targets.observeTargets(today),
                measurements.observeLatestWeightOnOrBefore(today),
            ) { profile, goals, appSettings, dailyTargets, latestWeight ->
                ProfileUiState(
                    profile = profile,
                    goals = goals,
                    settings = appSettings,
                    targets = dailyTargets,
                    currentWeightKg = latestWeight ?: profile.startWeightKg,
                    hasWeightEntry = latestWeight != null,
                    today = today,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
