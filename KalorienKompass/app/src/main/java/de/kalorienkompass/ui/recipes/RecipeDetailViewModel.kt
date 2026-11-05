package de.kalorienkompass.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.RecipeWithIngredients
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.RecipeNutrition
import de.kalorienkompass.data.repo.RecipeRepository
import de.kalorienkompass.util.dayLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the user wants to log: when, which meal, how many portions. */
internal data class LogSelection(val date: LocalDate, val meal: MealType, val portions: Double)

internal sealed interface RecipeDetailUiState {
    data object Loading : RecipeDetailUiState
    data object NotFound : RecipeDetailUiState
    data object Error : RecipeDetailUiState
    data class Content(
        val recipe: RecipeWithIngredients,
        val nutrition: RecipeNutrition,
        val selection: LogSelection,
        /** Nutrition of the selected number of portions. */
        val logNutrition: Nutrition,
        val isAdding: Boolean,
    ) : RecipeDetailUiState {
        /** At least one ingredient whose food still exists. */
        val canLog: Boolean get() = recipe.ingredients.any { it.food != null }
    }
}

internal sealed interface RecipeDetailEvent {
    data class Added(val entryId: Long, val message: String) : RecipeDetailEvent
    data class Message(val text: String) : RecipeDetailEvent
}

internal class RecipeDetailViewModel(
    private val recipeRepository: RecipeRepository,
    private val diaryRepository: DiaryRepository,
    private val recipeId: Long,
    initialDate: LocalDate?,
    initialMeal: MealType?,
) : ViewModel() {

    private val selection = MutableStateFlow(
        LogSelection(
            date = initialDate ?: LocalDate.now(),
            meal = initialMeal ?: MealType.forTimeOfDay(),
            portions = 1.0,
        ),
    )
    private val isAdding = MutableStateFlow(false)

    private val _events = Channel<RecipeDetailEvent>(Channel.BUFFERED)
    val events: Flow<RecipeDetailEvent> = _events.receiveAsFlow()

    private sealed interface Loaded {
        data class Found(val recipe: RecipeWithIngredients, val nutrition: RecipeNutrition) : Loaded
        data object Missing : Loaded
        data object Failed : Loaded
    }

    private val recipe: Flow<Loaded> =
        if (recipeId <= 0L) {
            flowOf(Loaded.Missing)
        } else {
            recipeRepository.observe(recipeId)
                .map<RecipeWithIngredients?, Loaded> { r ->
                    if (r == null) Loaded.Missing else Loaded.Found(r, RecipeRepository.nutritionOf(r))
                }
                .catch { emit(Loaded.Failed) }
                .flowOn(Dispatchers.Default)
        }

    val state: StateFlow<RecipeDetailUiState> =
        combine(recipe, selection, isAdding) { loaded, sel, adding ->
            when (loaded) {
                Loaded.Missing -> RecipeDetailUiState.NotFound
                Loaded.Failed -> RecipeDetailUiState.Error
                is Loaded.Found -> RecipeDetailUiState.Content(
                    recipe = loaded.recipe,
                    nutrition = loaded.nutrition,
                    selection = sel,
                    logNutrition = loaded.nutrition.perPortion * sel.portions,
                    isAdding = adding,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeDetailUiState.Loading)

    fun increasePortions() = selection.update { it.copy(portions = nextLogPortions(it.portions)) }

    fun decreasePortions() = selection.update { it.copy(portions = previousLogPortions(it.portions)) }

    fun setPortions(portions: Double) {
        if (portions.isNaN() || portions <= 0.0) {
            _events.trySend(RecipeDetailEvent.Message("Die Anzahl der Portionen muss größer als 0 sein."))
            return
        }
        selection.update { it.copy(portions = portions.coerceAtMost(MAX_LOG_PORTIONS)) }
    }

    fun setMeal(meal: MealType) = selection.update { it.copy(meal = meal) }

    fun setDate(date: LocalDate) = selection.update { it.copy(date = date) }

    fun toggleFavorite() {
        val content = state.value as? RecipeDetailUiState.Content ?: return
        viewModelScope.launch {
            try {
                recipeRepository.setFavorite(content.recipe.recipe.id, !content.recipe.recipe.isFavorite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(RecipeDetailEvent.Message("Favorit konnte nicht gespeichert werden."))
            }
        }
    }

    fun addToDiary() {
        val content = state.value as? RecipeDetailUiState.Content ?: return
        if (!content.canLog || isAdding.value) return
        isAdding.value = true
        val sel = content.selection
        viewModelScope.launch {
            try {
                val entryId = diaryRepository.addRecipe(sel.date, sel.meal, content.recipe, sel.portions)
                _events.send(
                    RecipeDetailEvent.Added(
                        entryId = entryId,
                        message = "${portionsLabel(sel.portions)} zu ${sel.meal.label} " +
                            "(${dayLabel(sel.date)}) hinzugefügt",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(RecipeDetailEvent.Message("Eintragen fehlgeschlagen. Bitte versuche es erneut."))
            } finally {
                isAdding.value = false
            }
        }
    }

    /** Undo of [addToDiary]. */
    fun undoAdd(entryId: Long) {
        viewModelScope.launch {
            try {
                diaryRepository.getEntry(entryId)?.let { diaryRepository.delete(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(RecipeDetailEvent.Message("Rückgängig machen fehlgeschlagen."))
            }
        }
    }
}
