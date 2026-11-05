package de.kalorienkompass.ui.progress

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.ui.components.charts.ChartPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Error(val message: String) : HistoryUiState
    data class Ready(val content: HistoryContent) : HistoryUiState
}

data class HistoryContent(
    val type: MeasurementType,
    val today: LocalDate,
    /** newest first, grouped by month */
    val months: List<HistoryMonth>,
    /** all data, one value per day (averaged per week / month for very long histories) */
    val chart: SampledPoints,
    val first: ChartPoint?,
    val latest: ChartPoint?,
    /** latest − first daily value */
    val totalChange: Double?,
    val count: Int,
    /** target weight (only for WEIGHT) */
    val goal: Double?,
    val goalType: GoalType,
    /** latest MANUAL value per day (the add dialog replaces it) */
    val manualValues: Map<LocalDate, Double>,
)

/** Pure builder of the history screen (unit-tested). */
fun buildHistoryContent(
    type: MeasurementType,
    measurements: List<MeasurementEntity>,
    profile: UserProfile,
    today: LocalDate,
): HistoryContent {
    val daily = latestPerDay(measurements)
    return HistoryContent(
        type = type,
        today = today,
        months = buildHistory(measurements),
        chart = downsample(daily, maxPoints = 150),
        first = daily.firstOrNull(),
        latest = daily.lastOrNull(),
        totalChange = if (daily.size >= 2) daily.last().value - daily.first().value else null,
        count = measurements.size,
        goal = if (type == MeasurementType.WEIGHT && profile.targetWeightKg > 0.0) profile.targetWeightKg else null,
        goalType = profile.goalType,
        manualValues = manualValuesByDate(measurements),
    )
}

/** All values of one [MeasurementType]: chart + list, add / edit / delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementHistoryViewModel(
    val type: MeasurementType,
    private val measurementRepository: MeasurementRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val reloadTrigger = MutableStateFlow(0)
    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-off user messages (snackbar). */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<HistoryUiState> = reloadTrigger
        .flatMapLatest {
            combine(measurementRepository.observe(type), settingsRepository.profile) { list, profile ->
                buildHistoryContent(type, list, profile, LocalDate.now())
            }
                .map<HistoryContent, HistoryUiState> { HistoryUiState.Ready(it) }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    Log.w(TAG, "Loading measurements failed", e)
                    emit(HistoryUiState.Error("Die Messwerte konnten gerade nicht geladen werden."))
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)

    fun retry() {
        reloadTrigger.update { it + 1 }
    }

    /** New MANUAL value (replaces an existing manual value of the same day). */
    fun add(date: LocalDate, value: Double) = runAndReport("Eintrag gespeichert") {
        measurementRepository.setDailyManualValue(type, date, value)
    }

    /** Edits an entry; it becomes the newest value of its day. */
    fun update(entry: MeasurementEntity, date: LocalDate, value: Double) = runAndReport("Eintrag aktualisiert") {
        measurementRepository.update(entry.copy(date = date, value = value, createdAt = System.currentTimeMillis()))
    }

    fun delete(entry: MeasurementEntity) = runAndReport("Eintrag gelöscht") {
        measurementRepository.delete(entry)
    }

    private fun runAndReport(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val message = try {
                block()
                success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving measurement failed", e)
                "Das hat leider nicht geklappt. Bitte versuche es erneut."
            }
            messageChannel.trySend(message)
        }
    }

    private companion object {
        const val TAG = "MeasurementHistoryVM"
    }
}
