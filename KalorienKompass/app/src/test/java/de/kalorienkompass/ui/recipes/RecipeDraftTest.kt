package de.kalorienkompass.ui.recipes

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.IngredientWithFood
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.db.RecipeWithIngredients
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeDraftTest {

    private val oats = FoodEntity(
        id = 1, name = "Haferflocken", kcal = 370.0, protein = 13.5, carbs = 58.7, fat = 7.0,
        source = FoodSource.BUILTIN, portions = listOf(FoodPortion("1 EL", 10.0)),
    )
    private val milk = FoodEntity(
        id = 2, name = "Milch 1,5 %", kcal = 47.0, protein = 3.4, carbs = 4.8, fat = 1.5,
        unit = "ml", source = FoodSource.BUILTIN,
    )

    private fun draft(vararg items: IngredientDraft, name: String = "Porridge", portions: Int = 2) =
        RecipeDraft(name = name, portions = portions, note = "", ingredients = items.toList())

    @Test
    fun `nutrition sums ingredients and divides by portions`() {
        val d = draft(IngredientDraft(1, 1, oats, 100.0), IngredientDraft(2, 2, milk, 200.0))
        val n = d.nutrition()
        assertEquals(300.0, n.totalGrams, 1e-9)
        assertEquals(370.0 + 94.0, n.total.kcal, 1e-9)
        assertEquals(13.5 + 6.8, n.total.protein, 1e-9)
        assertEquals((370.0 + 94.0) / 2, n.perPortion.kcal, 1e-9)
        assertEquals((7.0 + 3.0) / 2, n.perPortion.fat, 1e-9)
    }

    @Test
    fun `missing foods are ignored for nutrition and dropped on save`() {
        val d = draft(IngredientDraft(1, 99, null, 50.0), IngredientDraft(2, 1, oats, 50.0))
        assertEquals(185.0, d.nutrition().total.kcal, 1e-9)
        assertEquals(1, d.missingIngredientCount)
        val (_, ingredients) = d.toSaveModel(original = null)
        assertEquals(listOf(1L), ingredients.map { it.foodId })
        assertEquals(listOf(0), ingredients.map { it.sortIndex })
    }

    @Test
    fun `validation requires a name and an existing ingredient`() {
        assertEquals(setOf(DraftError.MISSING_NAME, DraftError.NO_INGREDIENTS), RecipeDraft(name = "  ").validate())
        assertEquals(setOf(DraftError.NO_INGREDIENTS), draft(IngredientDraft(1, 99, null, 50.0)).validate())
        assertEquals(setOf(DraftError.MISSING_NAME), draft(IngredientDraft(1, 1, oats, 50.0), name = "").validate())
        assertTrue(draft(IngredientDraft(1, 1, oats, 50.0)).validate().isEmpty())
    }

    @Test
    fun `save model keeps identity and usage data of the original recipe`() {
        val original = RecipeEntity(
            id = 7, name = "Alt", portions = 4, note = "alt", isFavorite = true, lastUsedAt = 123L, createdAt = 42L,
        )
        val d = RecipeDraft(
            name = "  Porridge  ",
            portions = 3,
            note = "  ",
            ingredients = listOf(IngredientDraft(1, 2, milk, 250.0), IngredientDraft(2, 1, oats, 60.0)),
        )
        val (recipe, ingredients) = d.toSaveModel(original)
        assertEquals(7L, recipe.id)
        assertEquals("Porridge", recipe.name)
        assertEquals(3, recipe.portions)
        assertNull(recipe.note)
        assertTrue(recipe.isFavorite)
        assertEquals(123L, recipe.lastUsedAt)
        assertEquals(42L, recipe.createdAt)
        assertEquals(listOf(2L, 1L), ingredients.map { it.foodId })
        assertEquals(listOf(250.0, 60.0), ingredients.map { it.grams })
        assertEquals(listOf(0, 1), ingredients.map { it.sortIndex })
        assertTrue(ingredients.all { it.recipeId == 7L })
    }

    @Test
    fun `stored recipe round-trips into a draft`() {
        val stored = RecipeWithIngredients(
            recipe = RecipeEntity(id = 3, name = "Müsli", portions = 2, note = "lecker"),
            ingredients = listOf(
                IngredientWithFood(RecipeIngredientEntity(id = 10, recipeId = 3, foodId = 1, grams = 80.0), oats),
                IngredientWithFood(RecipeIngredientEntity(id = 11, recipeId = 3, foodId = 5, grams = 20.0), null),
            ),
        )
        var key = 100L
        val d = stored.toDraft { key++ }
        assertEquals("Müsli", d.name)
        assertEquals(2, d.portions)
        assertEquals("lecker", d.note)
        assertEquals(listOf(100L, 101L), d.ingredients.map { it.key })
        assertEquals(listOf(1L, 5L), d.ingredients.map { it.foodId })
        assertTrue(d.ingredients[1].isMissing)
    }

    @Test
    fun `fingerprint detects real changes only`() {
        val base = draft(IngredientDraft(1, 1, oats, 50.0))
        assertEquals(base.fingerprint(), base.copy(name = "Porridge  ").fingerprint())
        assertEquals(base.fingerprint(), base.copy(ingredients = listOf(IngredientDraft(9, 1, oats, 50.0))).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(portions = 3).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(ingredients = listOf(IngredientDraft(1, 1, oats, 60.0))).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(note = "neu").fingerprint())
    }

    @Test
    fun `log portion stepper uses half portions`() {
        assertEquals(1.5, nextLogPortions(1.0), 1e-9)
        assertEquals(1.0, nextLogPortions(0.75), 1e-9)
        assertEquals(1.0, nextLogPortions(0.5), 1e-9)
        assertEquals(0.5, previousLogPortions(1.0), 1e-9)
        assertEquals(1.0, previousLogPortions(1.25), 1e-9)
        assertEquals(1.0, previousLogPortions(1.5), 1e-9)
        assertEquals(0.5, previousLogPortions(0.5), 1e-9)
        assertEquals(0.3, previousLogPortions(0.3), 1e-9)
        assertFalse(canDecreaseLogPortions(0.5))
        assertTrue(canDecreaseLogPortions(0.75))
        assertEquals(MAX_LOG_PORTIONS, nextLogPortions(MAX_LOG_PORTIONS), 1e-9)
        assertFalse(canIncreaseLogPortions(MAX_LOG_PORTIONS))
    }

    @Test
    fun `german labels`() {
        assertEquals("1 Portion", portionsLabel(1.0))
        assertEquals("0,5 Portionen", portionsLabel(0.5))
        assertEquals("2 Portionen", portionsLabel(2.0))
        assertEquals("1,25 Portionen", portionsLabel(1.25))
        assertEquals("1 Portion", portionsLabel(1))
        assertEquals("4 Portionen", portionsLabel(4))
        assertEquals("keine Zutaten", ingredientsLabel(0))
        assertEquals("1 Zutat", ingredientsLabel(1))
        assertEquals("6 Zutaten", ingredientsLabel(6))
    }

    @Test
    fun `pick results tolerate different value types`() {
        assertEquals(5L, parsePickedFoodId(5L))
        assertEquals(5L, parsePickedFoodId(5))
        assertEquals(7L, parsePickedFoodId("7"))
        assertNull(parsePickedFoodId(0L))
        assertNull(parsePickedFoodId(-1L))
        assertNull(parsePickedFoodId(null))
        assertNull(parsePickedFoodId("abc"))

        assertEquals(150.0, parsePickedGrams(150.0)!!, 1e-9)
        assertEquals(150.0, parsePickedGrams(150f)!!, 1e-9)
        assertEquals(150.0, parsePickedGrams(150)!!, 1e-9)
        assertEquals(12.5, parsePickedGrams("12,5")!!, 1e-9)
        assertNull(parsePickedGrams(0.0))
        assertNull(parsePickedGrams(Double.NaN))
        assertNull(parsePickedGrams(null))
    }

    @Test
    fun `default amount is the first portion else 100 g`() {
        assertEquals(10.0, defaultIngredientAmount(oats), 1e-9)
        assertEquals(100.0, defaultIngredientAmount(milk), 1e-9)
    }

    @Test
    fun `recipe list sorts favorites first then by german collation`() {
        fun item(id: Long, name: String, fav: Boolean = false) =
            RecipeListItem(id, name, 1, 1, 100.0, fav, 0)
        val sorted = sortRecipeItems(
            listOf(item(1, "Zucchini-Pfanne"), item(2, "Äpfelkuchen"), item(3, "Banane"), item(4, "apfelmus"), item(5, "Wraps", fav = true)),
        )
        // Umlauts sort like their base letter ("Äpfelkuchen" before "apfelmus"), case is ignored.
        assertEquals(listOf("Wraps", "Äpfelkuchen", "apfelmus", "Banane", "Zucchini-Pfanne"), sorted.map { it.name })
    }

    @Test
    fun `list item shows nutrition per portion and missing ingredients`() {
        val stored = RecipeWithIngredients(
            recipe = RecipeEntity(id = 3, name = "Müsli", portions = 2, isFavorite = true),
            ingredients = listOf(
                IngredientWithFood(RecipeIngredientEntity(id = 10, recipeId = 3, foodId = 1, grams = 100.0), oats),
                IngredientWithFood(RecipeIngredientEntity(id = 11, recipeId = 3, foodId = 5, grams = 20.0), null),
            ),
        )
        val item = stored.toListItem()
        assertEquals(185.0, item.kcalPerPortion, 1e-9)
        assertEquals(2, item.ingredientCount)
        assertEquals(1, item.missingCount)
        assertTrue(item.isFavorite)
    }
}
