package de.kalorienkompass.ui.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.data.repo.RecipeNutrition
import de.kalorienkompass.data.repo.RecipeRepository
import de.kalorienkompass.util.formatGrams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class RecipeEditUiState(
    val isNew: Boolean,
    val isLoading: Boolean = true,
    /** The recipe to edit does not exist (any more). */
    val notFound: Boolean = false,
    /** German, user-facing; loading failed. */
    val loadError: String? = null,
    val draft: RecipeDraft = RecipeDraft(),
    val nutrition: RecipeNutrition = RecipeNutrition(0.0, Nutrition.ZERO, Nutrition.ZERO),
    val showNameError: Boolean = false,
    val showIngredientsError: Boolean = false,
    val hasChanges: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    /** A picked food is being loaded. */
    val isAddingIngredient: Boolean = false,
) {
    val isEditable: Boolean get() = !isLoading && !notFound && loadError == null
    val isBusy: Boolean get() = isSaving || isDeleting

    /** Only stored recipes can be deleted. */
    val canDelete: Boolean get() = !isNew && isEditable
}

internal sealed interface RecipeEditEvent {
    data class Saved(val recipeId: Long, val wasNew: Boolean) : RecipeEditEvent
    data object Deleted : RecipeEditEvent
    data class Message(val text: String) : RecipeEditEvent
    data class IngredientRemoved(val index: Int, val ingredient: IngredientDraft) : RecipeEditEvent
}

/**
 * Holds the in-progress recipe ("draft") of the editor. It lives as long as the editor's back
 * stack entry, so the draft survives the trip to the food picker; it is additionally mirrored
 * into [savedState] so that it also survives process death.
 */
internal class RecipeEditViewModel(
    private val recipeRepository: RecipeRepository,
    private val foodRepository: FoodRepository,
    private val savedState: SavedStateHandle,
    private val recipeId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipeEditUiState(isNew = recipeId == null))
    val state: StateFlow<RecipeEditUiState> = _state.asStateFlow()

    private val _events = Channel<RecipeEditEvent>(Channel.BUFFERED)
    val events: Flow<RecipeEditEvent> = _events.receiveAsFlow()

    /** The stored recipe (null for a new one, until it has been saved once). */
    private var original: RecipeEntity? = null
    private var initialFingerprint: DraftFingerprint = RecipeDraft().fingerprint()
    private var nextKey = 1L

    // Declared last: the coroutine starts immediately (Main.immediate) and uses the fields above.
    private var loadJob: Job = viewModelScope.launch { load() }

    fun retryLoad() {
        if (_state.value.loadError == null) return
        _state.update { it.copy(isLoading = true, loadError = null) }
        loadJob = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val stored = recipeId?.let { recipeRepository.get(it) }
            if (recipeId != null && stored == null) {
                _state.update { it.copy(isLoading = false, notFound = true) }
                return
            }
            original = stored?.recipe
            val initialDraft = stored?.toDraft(::newKey) ?: RecipeDraft()
            initialFingerprint = initialDraft.fingerprint()
            val draft = restoreDraft() ?: initialDraft
            publish(draft, _state.value.copy(isLoading = false, notFound = false, loadError = null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(isLoading = false, loadError = "Das Rezept konnte nicht geladen werden.")
            }
        }
    }

    private fun newKey(): Long = nextKey++

    // ---- editing ----

    fun setName(name: String) = updateDraft { it.copy(name = name.take(MAX_RECIPE_NAME_LENGTH)) }

    fun setNote(note: String) = updateDraft { it.copy(note = note.take(MAX_RECIPE_NOTE_LENGTH)) }

    fun setPortions(portions: Int) {
        if (portions < MIN_RECIPE_PORTIONS) {
            message("Ein Rezept ergibt mindestens 1 Portion.")
            return
        }
        updateDraft { it.copy(portions = portions.coerceAtMost(MAX_RECIPE_PORTIONS)) }
    }

    fun increasePortions() = updateDraft {
        it.copy(portions = (it.portions + 1).coerceAtMost(MAX_RECIPE_PORTIONS))
    }

    fun decreasePortions() = updateDraft {
        it.copy(portions = (it.portions - 1).coerceAtLeast(MIN_RECIPE_PORTIONS))
    }

    fun setIngredientAmount(key: Long, amount: Double) {
        if (amount.isNaN() || amount <= 0.0) {
            message("Die Menge muss größer als 0 sein.")
            return
        }
        val value = amount.coerceAtMost(MAX_INGREDIENT_AMOUNT)
        updateDraft { draft ->
            draft.copy(ingredients = draft.ingredients.map { if (it.key == key) it.copy(grams = value) else it })
        }
    }

    fun removeIngredient(key: Long) {
        val draft = _state.value.draft
        val index = draft.ingredients.indexOfFirst { it.key == key }
        if (index < 0) return
        val removed = draft.ingredients[index]
        updateDraft { it.copy(ingredients = it.ingredients.filterNot { item -> item.key == key }) }
        _events.trySend(RecipeEditEvent.IngredientRemoved(index, removed))
    }

    /** Undo of [removeIngredient]. */
    fun restoreIngredient(index: Int, ingredient: IngredientDraft) = updateDraft { draft ->
        if (draft.ingredients.any { it.key == ingredient.key }) {
            draft
        } else {
            val list = draft.ingredients.toMutableList()
            list.add(index.coerceIn(0, list.size), ingredient)
            draft.copy(ingredients = list)
        }
    }

    /**
     * Result of the food picker (pick-result protocol, spec §9). Waits until the draft is loaded,
     * so a result that arrives right after a process restart is not lost.
     */
    fun addPickedIngredient(foodId: Long, grams: Double?) {
        viewModelScope.launch {
            loadJob.join()
            if (!_state.value.isEditable) return@launch
            _state.update { it.copy(isAddingIngredient = true) }
            val food: FoodEntity? = try {
                foodRepository.getFood(foodId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { it.copy(isAddingIngredient = false) }
            if (food == null) {
                message("Das Lebensmittel wurde nicht gefunden.")
                return@launch
            }
            val amount = (grams?.takeIf { !it.isNaN() && it > 0.0 } ?: defaultIngredientAmount(food))
                .coerceAtMost(MAX_INGREDIENT_AMOUNT)
            updateDraft { draft ->
                draft.copy(ingredients = draft.ingredients + IngredientDraft(newKey(), food.id, food, amount))
            }
            message("${formatGrams(amount)} ${food.unit.ifBlank { "g" }} „${food.name}“ hinzugefügt")
        }
    }

    // ---- save / delete ----

    fun save() {
        val current = _state.value
        if (!current.isEditable || current.isBusy) return
        val draft = current.draft
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            _state.update {
                it.copy(
                    showNameError = DraftError.MISSING_NAME in errors,
                    showIngredientsError = DraftError.NO_INGREDIENTS in errors,
                )
            }
            message(
                if (DraftError.MISSING_NAME in errors) {
                    "Bitte gib dem Rezept einen Namen."
                } else {
                    "Füge mindestens eine Zutat hinzu."
                },
            )
            return
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val wasNew = original == null
                val (recipe, ingredients) = draft.toSaveModel(original)
                val id = recipeRepository.save(recipe, ingredients)
                // A second save (e.g. if navigation did not happen) must update, not insert again.
                original = recipe.copy(id = id)
                initialFingerprint = draft.fingerprint()
                _state.update { it.copy(isSaving = false, hasChanges = false) }
                _events.send(RecipeEditEvent.Saved(id, wasNew))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
                message("Das Rezept konnte nicht gespeichert werden. Bitte versuche es erneut.")
            }
        }
    }

    fun delete() {
        val recipe = original ?: return
        if (_state.value.isBusy) return
        _state.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            try {
                recipeRepository.delete(recipe)
                clearSavedDraft()
                _state.update { it.copy(isDeleting = false, hasChanges = false) }
                _events.send(RecipeEditEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isDeleting = false) }
                message("Das Rezept konnte nicht gelöscht werden.")
            }
        }
    }

    // ---- internals ----

    private fun message(text: String) {
        _events.trySend(RecipeEditEvent.Message(text))
    }

    private inline fun updateDraft(transform: (RecipeDraft) -> RecipeDraft) {
        val current = _state.value
        if (!current.isEditable) return
        publish(transform(current.draft), current)
    }

    private fun publish(draft: RecipeDraft, base: RecipeEditUiState) {
        _state.value = base.copy(
            draft = draft,
            nutrition = draft.nutrition(),
            hasChanges = draft.fingerprint() != initialFingerprint,
            showNameError = base.showNameError && draft.name.isBlank(),
            showIngredientsError = base.showIngredientsError && draft.savableIngredients.isEmpty(),
        )
        persistDraft(draft)
    }

    private fun persistDraft(draft: RecipeDraft) {
        savedState[KEY_SAVED] = true
        savedState[KEY_NAME] = draft.name
        savedState[KEY_PORTIONS] = draft.portions
        savedState[KEY_NOTE] = draft.note
        savedState[KEY_FOOD_IDS] = draft.ingredients.map { it.foodId }.toLongArray()
        savedState[KEY_AMOUNTS] = draft.ingredients.map { it.grams }.toDoubleArray()
    }

    private fun clearSavedDraft() {
        savedState[KEY_SAVED] = false
    }

    /** Draft saved before a process restart, or null. */
    private suspend fun restoreDraft(): RecipeDraft? {
        if (savedState.get<Boolean>(KEY_SAVED) != true) return null
        val ids = savedState.get<LongArray>(KEY_FOOD_IDS) ?: LongArray(0)
        val amounts = savedState.get<DoubleArray>(KEY_AMOUNTS) ?: DoubleArray(0)
        val foods = HashMap<Long, FoodEntity?>()
        val ingredients = ids.indices.filter { it < amounts.size }.map { i ->
            val id = ids[i]
            val food = foods.getOrPut(id) { foodRepository.getFood(id) }
            IngredientDraft(newKey(), id, food, amounts[i])
        }
        return RecipeDraft(
            name = savedState.get<String>(KEY_NAME).orEmpty(),
            portions = (savedState.get<Int>(KEY_PORTIONS) ?: MIN_RECIPE_PORTIONS)
                .coerceIn(MIN_RECIPE_PORTIONS, MAX_RECIPE_PORTIONS),
            note = savedState.get<String>(KEY_NOTE).orEmpty(),
            ingredients = ingredients,
        )
    }

    private companion object {
        const val KEY_SAVED = "recipe_draft_saved"
        const val KEY_NAME = "recipe_draft_name"
        const val KEY_PORTIONS = "recipe_draft_portions"
        const val KEY_NOTE = "recipe_draft_note"
        const val KEY_FOOD_IDS = "recipe_draft_food_ids"
        const val KEY_AMOUNTS = "recipe_draft_amounts"
    }
}
