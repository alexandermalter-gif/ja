package de.kalorienkompass.ai.lookup

import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLookupResultTest {

    private val complete = NutritionLookupResult(
        name = "  Apfelschorle ",
        brand = " ",
        unit = "ml",
        kcal = 24.0,
        fat = 0.0,
        carbs = 5.5,
        sugar = 5.4,
        protein = 0.1,
        salt = 0.01,
        servingName = "1 Flasche",
        servingGrams = 500.0,
    )

    @Test
    fun completeResultBecomesCustomFood() {
        val food = complete.toCustomFood(" 4001234567890 ")!!

        assertEquals("Apfelschorle", food.name)
        assertNull(food.brand)
        assertEquals("4001234567890", food.barcode)
        assertEquals("ml", food.unit)
        assertEquals(24.0, food.kcal, 0.0)
        assertEquals(0.0, food.fat, 0.0)
        assertEquals(5.5, food.carbs, 0.0)
        assertEquals(0.1, food.protein, 0.0)
        assertEquals(5.4, food.sugar!!, 0.0)
        assertNull(food.fiber)
        assertEquals(FoodSource.CUSTOM, food.source)
        assertEquals(listOf(FoodPortion("1 Flasche", 500.0)), food.portions)
        assertEquals(0L, food.id)
    }

    @Test
    fun missingEnergyOrMacroGivesNull() {
        assertNull(complete.copy(kcal = null).toCustomFood(null))
        assertNull(complete.copy(fat = null).toCustomFood(null))
        assertNull(complete.copy(carbs = null).toCustomFood(null))
        assertNull(complete.copy(protein = null).toCustomFood(null))
        // optional values may be missing
        assertTrue(complete.copy(sugar = null, fiber = null, salt = null).toCustomFood(null) != null)
    }

    @Test
    fun servingWithoutNameIsCalledPortionAndInvalidServingIsDropped() {
        assertEquals(
            listOf(FoodPortion("Portion", 30.0)),
            complete.copy(servingName = null, servingGrams = 30.0).toCustomFood(null)!!.portions,
        )
        assertTrue(complete.copy(servingGrams = 0.0).toCustomFood(null)!!.portions.isEmpty())
        assertTrue(complete.copy(servingGrams = null).toCustomFood(null)!!.portions.isEmpty())
    }

    @Test
    fun missingNameFallsBackToBarcodeAndUnitDefaultsToGrams() {
        val food = complete.copy(name = null, unit = "kg").toCustomFood("4001234567890")!!
        assertEquals("Produkt 4001234567890", food.name)
        assertEquals("g", food.unit)
        assertEquals("Unbenanntes Produkt", complete.copy(name = "").toCustomFood(null)!!.name)
        assertNull(complete.toCustomFood("  ")!!.barcode)
    }
}
