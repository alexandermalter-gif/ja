package de.kalorienkompass.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {

    /** Note: Room relations are unordered; [de.kalorienkompass.data.repo.RecipeRepository] sorts ingredients by sortIndex. */
    @Transaction
    @Query("SELECT * FROM recipes ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<RecipeWithIngredients>>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observeById(id: Long): Flow<RecipeWithIngredients?>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: Long): RecipeWithIngredients?

    /** The recipe row only (without ingredients). */
    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeEntity(id: Long): RecipeEntity?

    @Insert
    suspend fun insertRecipe(recipe: RecipeEntity): Long

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Delete
    suspend fun deleteRecipe(recipe: RecipeEntity)

    @Insert
    suspend fun insertIngredients(ingredients: List<RecipeIngredientEntity>)

    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :recipeId")
    suspend fun deleteIngredientsForRecipe(recipeId: Long)

    @Query("SELECT * FROM recipes ORDER BY id ASC")
    suspend fun getAllRecipes(): List<RecipeEntity>

    @Query("SELECT * FROM recipe_ingredients ORDER BY recipeId ASC, sortIndex ASC, id ASC")
    suspend fun getAllIngredients(): List<RecipeIngredientEntity>

    /** Ingredients are removed via ON DELETE CASCADE. */
    @Query("DELETE FROM recipes")
    suspend fun deleteAllRecipes()

    @Query("DELETE FROM recipe_ingredients")
    suspend fun deleteAllIngredients()

    @Query("UPDATE recipes SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE recipes SET lastUsedAt = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)

    @Query("SELECT COUNT(DISTINCT recipeId) FROM recipe_ingredients WHERE foodId = :foodId")
    suspend fun countRecipesUsingFood(foodId: Long): Int
}
