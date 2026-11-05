package de.kalorienkompass.ui.diary

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.domain.DailyTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

data class MealDetailUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** entries of this meal, in the order they were added */
    val entries: List<DiaryEntryEntity> = emptyList(),
    val total: Nutrition = Nutrition.ZERO,
    /** the meal's share of the daily calorie goal */
    val targetKcal: Int = 0,
    /** the meal's share of the daily macro goals (g) */
    val carbsGoal: Double = 0.0,
    val proteinGoal: Double = 0.0,
    val fatGoal: Double = 0.0,
    /** entries of the same meal on the previous day (for "Von gestern kopieren") */
    val previousDayCount: Int = 0,
    val previousDayKcal: Double = 0.0,
    val isCopying: Boolean = false,
)

sealed interface MealDetailEvent {
    /** An entry was deleted (snackbar with "Rückgängig"). */
    data class Deleted(val entry: DiaryEntryEntity) : MealDetailEvent

    /** Entries were copied from the previous day (snackbar with "Rückgängig"). */
    data class Copied(val count: Int, val entryIds: List<Long>) : MealDetailEvent

    /** An entry was moved to another meal. */
    data class Moved(val name: String, val meal: MealType) : MealDetailEvent

    data class Message(val text: String) : MealDetailEvent
}

/** Entries of one meal on one day: edit amount/meal, delete (with undo), copy from the previous day. */
class MealDetailViewModel(
    val date: LocalDate,
    val meal: MealType,
    private val diaryRepository: DiaryRepository,
    targets: Flow<DailyTargets>,
) : ViewModel() {

    private val reloadTrigger = MutableStateFlow(0)
    private val copying = MutableStateFlow(false)

    private val _events = Channel<MealDetailEvent>(Channel.BUFFERED)
    val events: Flow<MealDetailEvent> = _events.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val content: Flow<MealDetailUiState> = reloadTrigger.flatMapLatest {
        combine(
            diaryRepository.observeDay(date),
            diaryRepository.observeDay(date.minusDays(1)),
            targets,
        ) { dayEntries, previousDayEntries, dailyTargets ->
            val entries = dayEntries.filter { it.meal == meal }
            val previous = previousDayEntries.filter { it.meal == meal }
            val share = mealShare(dailyTargets, meal)
            MealDetailUiState(
                isLoading = false,
                entries = entries,
                total = entries.totalNutrition(),
                targetKcal = dailyTargets.mealCalories[meal] ?: 0,
                carbsGoal = dailyTargets.carbsG * share,
                proteinGoal = dailyTargets.proteinG * share,
                fatGoal = dailyTargets.fatG * share,
                previousDayCount = previous.size,
                previousDayKcal = previous.sumOf { it.kcal },
            )
        }.catch { e ->
            Log.e(TAG, "Loading meal $meal on $date failed", e)
            emit(MealDetailUiState(isLoading = false, errorMessage = "Die Einträge konnten nicht geladen werden."))
        }
    }

    val uiState: StateFlow<MealDetailUiState> = combine(content, copying) { state, isCopying ->
        state.copy(isCopying = isCopying)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MealDetailUiState(),
    )

    fun retry() {
        reloadTrigger.update { it + 1 }
    }

    /** Saves a new amount (nutrition scaled proportionally) and/or moves the entry to [newMeal]. */
    fun updateEntry(entry: DiaryEntryEntity, newAmount: Double, newMeal: MealType) {
        if (newAmount <= 0.0) return
        val amountChanged = abs(newAmount - entry.amount) > 1e-9
        val moved = newMeal != entry.meal
        if (!amountChanged && !moved) return
        launchSafely("Der Eintrag konnte nicht gespeichert werden.") {
            val base = entry.copy(meal = newMeal)
            if (amountChanged) diaryRepository.updateAmount(base, newAmount) else diaryRepository.update(base)
            if (moved) _events.send(MealDetailEvent.Moved(entry.name, newMeal))
        }
    }

    /** Quick entries only carry calories: saves the new kcal value (macros unchanged) and/or the meal. */
    fun updateQuickEntry(entry: DiaryEntryEntity, newKcal: Double, newMeal: MealType) {
        if (newKcal < 0.0) return
        val kcalChanged = abs(newKcal - entry.kcal) > 1e-9
        val moved = newMeal != entry.meal
        if (!kcalChanged && !moved) return
        launchSafely("Der Eintrag konnte nicht gespeichert werden.") {
            diaryRepository.update(entry.copy(kcal = newKcal, meal = newMeal))
            if (moved) _events.send(MealDetailEvent.Moved(entry.name, newMeal))
        }
    }

    fun delete(entry: DiaryEntryEntity) {
        launchSafely("Der Eintrag konnte nicht gelöscht werden.") {
            diaryRepository.delete(entry)
            _events.send(MealDetailEvent.Deleted(entry))
        }
    }

    /** Re-inserts a deleted entry with its original id and position. */
    fun undoDelete(entry: DiaryEntryEntity) {
        launchSafely("Der Eintrag konnte nicht wiederhergestellt werden.") {
            if (diaryRepository.getEntry(entry.id) == null) diaryRepository.addEntry(entry)
        }
    }

    /** Copies the meal of the previous day ("Von gestern kopieren"). */
    fun copyFromPreviousDay() {
        if (copying.value) return
        copying.value = true
        viewModelScope.launch {
            try {
                val before = diaryRepository.observeDay(date).first().mapTo(HashSet()) { it.id }
                val count = diaryRepository.copyMeal(date.minusDays(1), meal, date)
                if (count == 0) {
                    _events.send(MealDetailEvent.Message("Am Vortag gibt es keine Einträge für ${meal.label}."))
                } else {
                    val newIds = diaryRepository.observeDay(date).first()
                        .filter { it.meal == meal && it.id !in before }
                        .map { it.id }
                    _events.send(MealDetailEvent.Copied(count, newIds))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Copying meal failed", e)
                _events.send(MealDetailEvent.Message("Das Kopieren hat nicht geklappt."))
            } finally {
                copying.value = false
            }
        }
    }

    /** Removes entries that were just copied. */
    fun undoCopy(entryIds: List<Long>) {
        if (entryIds.isEmpty()) return
        launchSafely("Das Rückgängigmachen hat nicht geklappt.") {
            for (id in entryIds) {
                diaryRepository.getEntry(id)?.let { diaryRepository.delete(it) }
            }
        }
    }

    private fun launchSafely(errorMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, errorMessage, e)
                _events.send(MealDetailEvent.Message(errorMessage))
            }
        }
    }

    private companion object {
        const val TAG = "MealDetailViewModel"
    }
}
