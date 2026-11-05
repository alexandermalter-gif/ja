package de.kalorienkompass.data.repo

import androidx.room.withTransaction
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.db.RecipeWithIngredients
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.domain.NutritionMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RecipeNutrition(val totalGrams: Double, val total: Nutrition, val perPortion: Nutrition)

class RecipeRepository(private val db: AppDatabase) {

    private val dao get() = db.recipeDao()

    /** Recipes by name; ingredients sorted by sortIndex. */
    fun observeAll(): Flow<List<RecipeWithIngredients>> =
        dao.observeAll().map { list -> list.map { it.sortedIngredients() } }

    fun observe(id: Long): Flow<RecipeWithIngredients?> =
        dao.observeById(id).map { it?.sortedIngredients() }

    suspend fun get(id: Long): RecipeWithIngredients? = dao.getById(id)?.sortedIngredients()

    /**
     * Inserts (id == 0) or updates the recipe and replaces ALL its ingredients (in list order)
     * in one transaction. Returns the recipe id.
     */
    suspend fun save(recipe: RecipeEntity, ingredients: List<RecipeIngredientEntity>): Long =
        db.withTransaction {
            val normalized = recipe.copy(
                name = recipe.name.trim(),
                portions = recipe.portions.coerceAtLeast(1),
                note = recipe.note?.trim()?.takeIf { it.isNotEmpty() },
            )
            val id = if (normalized.id == 0L) {
                dao.insertRecipe(normalized)
            } else {
                dao.updateRecipe(normalized)
                normalized.id
            }
            dao.deleteIngredientsForRecipe(id)
            if (ingredients.isNotEmpty()) {
                dao.insertIngredients(
                    ingredients.mapIndexed { index, ingredient ->
                        ingredient.copy(id = 0, recipeId = id, sortIndex = index)
                    },
                )
            }
            id
        }

    suspend fun delete(recipe: RecipeEntity) = dao.deleteRecipe(recipe)

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    private fun RecipeWithIngredients.sortedIngredients(): RecipeWithIngredients =
        copy(ingredients = ingredients.sortedWith(compareBy({ it.ingredient.sortIndex }, { it.ingredient.id })))

    companion object {
        /** Ingredients whose food no longer exists (food == null) are skipped. */
        fun nutritionOf(r: RecipeWithIngredients): RecipeNutrition {
            var grams = 0.0
            var total = Nutrition.ZERO
            for (item in r.ingredients) {
                val food = item.food ?: continue
                grams += item.ingredient.grams
                total += NutritionMath.forGrams(food, item.ingredient.grams)
            }
            val portions = r.recipe.portions.coerceAtLeast(1)
            return RecipeNutrition(
                totalGrams = grams,
                total = total,
                perPortion = total * (1.0 / portions),
            )
        }
    }
}
