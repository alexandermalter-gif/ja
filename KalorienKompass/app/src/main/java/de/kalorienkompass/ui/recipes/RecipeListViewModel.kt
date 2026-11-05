package de.kalorienkompass.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.RecipeWithIngredients
import de.kalorienkompass.data.repo.RecipeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

internal data class RecipeListItem(
    val id: Long,
    val name: String,
    val portions: Int,
    val ingredientCount: Int,
    val kcalPerPortion: Double,
    val isFavorite: Boolean,
    /** Ingredients whose food no longer exists. */
    val missingCount: Int,
)

internal sealed interface RecipeListUiState {
    data object Loading : RecipeListUiState
    data class Loaded(val items: List<RecipeListItem>) : RecipeListUiState
    data object Error : RecipeListUiState
}

internal class RecipeListViewModel(private val recipeRepository: RecipeRepository) : ViewModel() {

    val state: StateFlow<RecipeListUiState> = recipeRepository.observeAll()
        .map<List<RecipeWithIngredients>, RecipeListUiState> { recipes ->
            RecipeListUiState.Loaded(sortRecipeItems(recipes.map { it.toListItem() }))
        }
        .catch { emit(RecipeListUiState.Error) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeListUiState.Loading)

    fun toggleFavorite(item: RecipeListItem) {
        viewModelScope.launch {
            try {
                recipeRepository.setFavorite(item.id, !item.isFavorite)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The star simply does not change; nothing else to do.
            }
        }
    }
}

internal fun RecipeWithIngredients.toListItem(): RecipeListItem {
    val nutrition = RecipeRepository.nutritionOf(this)
    return RecipeListItem(
        id = recipe.id,
        name = recipe.name,
        portions = recipe.portions.coerceAtLeast(MIN_RECIPE_PORTIONS),
        ingredientCount = ingredients.size,
        kcalPerPortion = nutrition.perPortion.kcal,
        isFavorite = recipe.isFavorite,
        missingCount = ingredients.count { it.food == null },
    )
}

/** Favorites first, then alphabetically (German collation: "Äpfel" next to "Apfel"). */
internal fun sortRecipeItems(items: List<RecipeListItem>): List<RecipeListItem> {
    val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.SECONDARY }
    return items.sortedWith(
        compareByDescending<RecipeListItem> { it.isFavorite }
            .thenComparator { a, b -> collator.compare(a.name.trim(), b.name.trim()) }
            .thenBy { it.id },
    )
}
