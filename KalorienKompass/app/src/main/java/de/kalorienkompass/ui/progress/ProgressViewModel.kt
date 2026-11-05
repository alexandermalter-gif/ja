package de.kalorienkompass.ui.progress

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.domain.TargetsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** State of the "Fortschritt" tab: selected period + aggregated data of all cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModel(
    private val diaryRepository: DiaryRepository,
    private val measurementRepository: MeasurementRepository,
    private val activityRepository: ActivityRepository,
    private val settingsRepository: SettingsRepository,
    private val targetsUseCase: TargetsUseCase,
) : ViewModel() {

    private val selectedRange = MutableStateFlow(ProgressRange.MONTH)
    val range: StateFlow<ProgressRange> = selectedRange.asStateFlow()

    /** Refreshed on every resume so that the periods move on after midnight. */
    private val today = MutableStateFlow(LocalDate.now())
    private val reloadTrigger = MutableStateFlow(0)

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-off user messages (snackbar). */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<ProgressUiState> =
        combine(selectedRange, today, reloadTrigger) { range, day, _ -> range to day }
            .flatMapLatest { (range, day) -> observeContent(range, day) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState.Loading)

    fun selectRange(range: ProgressRange) {
        selectedRange.value = range
    }

    fun refreshToday() {
        today.value = LocalDate.now()
    }

    fun retry() {
        reloadTrigger.update { it + 1 }
    }

    /** Saves the MANUAL value of [type] for [date] (replaces an existing manual value of that day). */
    fun saveMeasurement(type: MeasurementType, date: LocalDate, value: Double) {
        viewModelScope.launch {
            val message = try {
                measurementRepository.setDailyManualValue(type, date, value)
                "${type.label} gespeichert: ${formatMeasurement(value, type)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving measurement failed", e)
                "Speichern fehlgeschlagen. Bitte versuche es erneut."
            }
            messageChannel.trySend(message)
        }
    }

    private fun observeContent(range: ProgressRange, day: LocalDate): Flow<ProgressUiState> {
        val queryFrom = range.startDate(day) ?: EARLIEST_QUERY_DATE
        val bodyFlow: Flow<Map<MeasurementType, List<MeasurementEntity>>> =
            combine(BODY_MEASUREMENT_TYPES.map { type -> measurementRepository.observe(type).map { type to it } }) {
                it.toMap()
            }
        val preferencesFlow: Flow<Preferences> = combine(
            settingsRepository.profile,
            settingsRepository.settings,
            settingsRepository.goalSettings,
            targetsUseCase.observeTargets(day),
        ) { profile, settings, goals, targets -> Preferences(profile, settings, goals, targets) }

        return combine(
            diaryRepository.observeDailyTotals(queryFrom, day),
            activityRepository.observeRange(queryFrom, day),
            measurementRepository.observe(MeasurementType.WEIGHT),
            bodyFlow,
            preferencesFlow,
        ) { nutrition, activity, weights, body, prefs ->
            buildProgressContent(
                range = range,
                today = day,
                nutrition = nutrition,
                activity = activity,
                weights = weights,
                body = body,
                profile = prefs.profile,
                settings = prefs.settings,
                goals = prefs.goals,
                targets = prefs.targets,
            )
        }
            .map<ProgressContent, ProgressUiState> { ProgressUiState.Ready(it) }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                Log.w(TAG, "Loading progress data failed", e)
                emit(ProgressUiState.Error("Deine Daten konnten gerade nicht geladen werden."))
            }
    }

    private data class Preferences(
        val profile: UserProfile,
        val settings: AppSettings,
        val goals: NutritionGoalSettings,
        val targets: DailyTargets,
    )

    private companion object {
        const val TAG = "ProgressViewModel"
    }
}
