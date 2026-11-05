package de.kalorienkompass.domain

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.Nutrition
import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionMathTest {

    private val apple = FoodEntity(
        name = "Apfel",
        kcal = 52.0,
        protein = 0.3,
        carbs = 11.4,
        fat = 0.2,
        source = FoodSource.BUILTIN,
    )

    @Test
    fun forGrams_scalesPer100Values() {
        val n = NutritionMath.forGrams(apple, 150.0)
        assertEquals(78.0, n.kcal, 1e-9)
        assertEquals(0.45, n.protein, 1e-9)
        assertEquals(17.1, n.carbs, 1e-9)
        assertEquals(0.3, n.fat, 1e-9)
    }

    @Test
    fun forGrams_zeroGramsIsZero() {
        assertEquals(Nutrition.ZERO, NutritionMath.forGrams(apple, 0.0))
        assertEquals(0.0, NutritionMath.forGrams(apple, 0.0).kcal, 1e-9)
    }

    @Test
    fun per100_returnsStoredValues() {
        assertEquals(Nutrition(52.0, 0.3, 11.4, 0.2), NutritionMath.per100(apple))
    }

    @Test
    fun nutrition_plusAndTimes() {
        val a = Nutrition(100.0, 10.0, 20.0, 5.0)
        val b = Nutrition(50.0, 1.0, 2.0, 3.0)
        assertEquals(Nutrition(150.0, 11.0, 22.0, 8.0), a + b)
        assertEquals(Nutrition(200.0, 20.0, 40.0, 10.0), a * 2.0)
        assertEquals(a, a + Nutrition.ZERO)
    }

    @Test
    fun sum_addsAll() {
        val total = NutritionMath.sum(listOf(Nutrition(1.0, 2.0, 3.0, 4.0), Nutrition(1.0, 2.0, 3.0, 4.0)))
        assertEquals(Nutrition(2.0, 4.0, 6.0, 8.0), total)
        assertEquals(Nutrition.ZERO, NutritionMath.sum(emptyList()))
    }
}
