package de.kalorienkompass.data.repo

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.DbTestSupport.food
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.IngredientWithFood
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.db.RecipeWithIngredients
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RecipeRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository

    @Before
    fun setUp() {
        db = DbTestSupport.inMemoryDatabase()
        repo = RecipeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun save_replacesAllIngredientsInListOrder() = runBlocking {
        val dao = db.foodDao()
        val a = dao.insert(food("A"))
        val b = dao.insert(food("B"))
        val c = dao.insert(food("C"))
        val id = repo.save(
            RecipeEntity(name = "  Salat  ", portions = 0, note = "   "),
            listOf(ingredient(b, 50.0), ingredient(a, 100.0)),
        )
        val first = repo.get(id)!!
        assertEquals("Salat", first.recipe.name)
        assertEquals(1, first.recipe.portions)
        assertNull(first.recipe.note)
        assertEquals(listOf("B", "A"), first.ingredients.map { it.food?.name })

        repo.save(first.recipe.copy(portions = 3), listOf(ingredient(c, 10.0), ingredient(a, 20.0), ingredient(b, Double.NaN)))
        val second = repo.observe(id).first()!!
        assertEquals(3, second.recipe.portions)
        assertEquals(listOf("C", "A", "B"), second.ingredients.map { it.food?.name })
        assertEquals(listOf(10.0, 20.0, 0.0), second.ingredients.map { it.ingredient.grams })
        assertEquals(listOf(0, 1, 2), second.ingredients.map { it.ingredient.sortIndex })
        assertEquals(3, db.recipeDao().getAllIngredients().size)
    }

    @Test
    fun save_reinsertsARecipeDeletedMeanwhile() = runBlocking {
        val foodId = db.foodDao().insert(food("Mehl"))
        val id = repo.save(RecipeEntity(name = "Brot"), listOf(ingredient(foodId, 500.0)))
        val stale = repo.get(id)!!.recipe
        repo.delete(stale)
        assertNull(repo.get(id))
        assertEquals(id, repo.save(stale, listOf(ingredient(foodId, 400.0))))
        assertEquals(400.0, repo.get(id)!!.ingredients.single().ingredient.grams, 1e-9)
    }

    @Test
    fun observeAll_sortedByNameWithSortedIngredients() = runBlocking {
        val x = db.foodDao().insert(food("X"))
        val y = db.foodDao().insert(food("Y"))
        repo.save(RecipeEntity(name = "zucchini-Pfanne"), listOf(ingredient(y, 1.0), ingredient(x, 1.0)))
        repo.save(RecipeEntity(name = "Apfelkuchen"), listOf(ingredient(x, 1.0)))
        val all = repo.observeAll().first()
        assertEquals(listOf("Apfelkuchen", "zucchini-Pfanne"), all.map { it.recipe.name })
        assertEquals(listOf("Y", "X"), all[1].ingredients.map { it.food?.name })
    }

    @Test
    fun nutritionOf_skipsMissingFoodsAndInvalidAmounts() {
        val oats = food("Haferflocken", kcal = 370.0, protein = 13.5, carbs = 58.7, fat = 7.0, id = 1)
        val milk = food("Milch", kcal = 64.0, protein = 3.4, carbs = 4.8, fat = 3.5, id = 2)
        val recipe = RecipeWithIngredients(
            recipe = RecipeEntity(id = 1, name = "Porridge", portions = 2),
            ingredients = listOf(
                IngredientWithFood(ingredient(1, 80.0), oats),
                IngredientWithFood(ingredient(2, 250.0), milk),
                IngredientWithFood(ingredient(3, 100.0), null), // deleted food
                IngredientWithFood(ingredient(1, -10.0), oats),
                IngredientWithFood(ingredient(1, Double.NaN), oats),
            ),
        )
        val n = RecipeRepository.nutritionOf(recipe)
        assertEquals(330.0, n.totalGrams, 1e-9)
        assertEquals(296.0 + 160.0, n.total.kcal, 1e-9)
        assertEquals((296.0 + 160.0) / 2, n.perPortion.kcal, 1e-9)
        assertEquals(10.8 + 8.5, n.total.protein, 1e-9)
        assertTrue(n.perPortion.fat > 0.0)

        val empty = RecipeRepository.nutritionOf(RecipeWithIngredients(RecipeEntity(name = "Leer", portions = 0), emptyList()))
        assertEquals(0.0, empty.totalGrams, 1e-9)
        assertEquals(0.0, empty.perPortion.kcal, 1e-9)
    }

    private fun ingredient(foodId: Long, grams: Double) = RecipeIngredientEntity(recipeId = 0, foodId = foodId, grams = grams)
}
