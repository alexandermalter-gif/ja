package de.kalorienkompass.domain

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.Nutrition

object NutritionMath {
    /** Nutrition of [grams] g (or ml) of [food]: per-100 value × grams / 100. */
    fun forGrams(food: FoodEntity, grams: Double): Nutrition {
        val f = grams / 100.0
        return Nutrition(
            kcal = food.kcal * f,
            protein = food.protein * f,
            carbs = food.carbs * f,
            fat = food.fat * f,
        )
    }

    /** Per-100 g (ml) values of [food]. */
    fun per100(food: FoodEntity): Nutrition = Nutrition(food.kcal, food.protein, food.carbs, food.fat)

    /** Sum of a list of nutrition values. */
    fun sum(items: Iterable<Nutrition>): Nutrition = items.fold(Nutrition.ZERO) { acc, n -> acc + n }
}
