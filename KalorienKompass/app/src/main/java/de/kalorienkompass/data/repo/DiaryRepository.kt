package de.kalorienkompass.data.repo

import androidx.room.withTransaction
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.DailyNutritionTotals
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.RecipeWithIngredients
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.domain.NutritionMath
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class DiaryRepository(private val db: AppDatabase) {

    private val dao get() = db.diaryDao()

    fun observeDay(date: LocalDate): Flow<List<DiaryEntryEntity>> = dao.observeDay(date)

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DiaryEntryEntity>> = dao.observeRange(from, to)

    fun observeDailyTotals(from: LocalDate, to: LocalDate): Flow<List<DailyNutritionTotals>> =
        dao.observeDailyTotals(from, to)

    suspend fun getEntry(id: Long): DiaryEntryEntity? = dao.getById(id)

    /**
     * Logs [grams] of [food]. [amount]/[unitLabel] describe what the user entered
     * (e.g. 2 × "Scheibe (50 g)" → grams = 100). Totals are computed with [NutritionMath].
     * Negative or non-finite amounts/grams are stored as 0.
     */
    suspend fun addFood(
        date: LocalDate,
        meal: MealType,
        food: FoodEntity,
        amount: Double,
        unitLabel: String,
        grams: Double,
    ): Long {
        val safeGrams = grams.nonNegative()
        val n = NutritionMath.forGrams(food, safeGrams)
        val entry = DiaryEntryEntity(
            date = date,
            meal = meal,
            name = food.name,
            brand = food.brand,
            amount = amount.nonNegative(),
            unitLabel = unitLabel,
            grams = safeGrams,
            kcal = n.kcal,
            protein = n.protein,
            carbs = n.carbs,
            fat = n.fat,
            foodId = food.id.takeIf { it != 0L },
            source = if (food.source == FoodSource.AI) EntrySource.AI else EntrySource.FOOD,
        )
        return db.withTransaction {
            val id = dao.insert(entry)
            if (food.id != 0L) db.foodDao().markUsed(food.id, System.currentTimeMillis())
            id
        }
    }

    /**
     * Logs [portions] portions of a recipe (unitLabel "Portion", amount = portions,
     * grams = totalGrams / recipe.portions × portions). Negative or non-finite portions are stored as 0.
     */
    suspend fun addRecipe(
        date: LocalDate,
        meal: MealType,
        recipe: RecipeWithIngredients,
        portions: Double,
    ): Long {
        val safePortions = portions.nonNegative()
        val nutrition = RecipeRepository.nutritionOf(recipe)
        val factor = safePortions / recipe.recipe.portions.coerceAtLeast(1)
        val total = nutrition.total * factor
        val recipeId = recipe.recipe.id.takeIf { it != 0L }
        val entry = DiaryEntryEntity(
            date = date,
            meal = meal,
            name = recipe.recipe.name,
            amount = safePortions,
            unitLabel = RECIPE_UNIT,
            grams = nutrition.totalGrams * factor,
            kcal = total.kcal,
            protein = total.protein,
            carbs = total.carbs,
            fat = total.fat,
            recipeId = recipeId,
            source = EntrySource.RECIPE,
        )
        return db.withTransaction {
            val id = dao.insert(entry)
            if (recipeId != null) db.recipeDao().markUsed(recipeId, System.currentTimeMillis())
            id
        }
    }

    /** Quick entry with kcal (and optional macros) only; unitLabel "", amount 1, grams 0. */
    suspend fun addQuick(date: LocalDate, meal: MealType, name: String, nutrition: Nutrition): Long =
        dao.insert(
            DiaryEntryEntity(
                date = date,
                meal = meal,
                name = name.trim().ifEmpty { "Schnelleintrag" },
                amount = 1.0,
                unitLabel = "",
                grams = 0.0,
                kcal = nutrition.kcal.nonNegative(),
                protein = nutrition.protein.nonNegative(),
                carbs = nutrition.carbs.nonNegative(),
                fat = nutrition.fat.nonNegative(),
                source = EntrySource.QUICK,
            ),
        )

    suspend fun addEntry(entry: DiaryEntryEntity): Long = dao.insert(entry)

    /**
     * Changes the amount and scales grams and nutrition proportionally (if the old amount > 0).
     * Negative amounts are treated as 0 and non-finite amounts are ignored. An entry whose old
     * amount is 0 is recalculated from its food or recipe when possible, so that setting an
     * amount to 0 by mistake can be undone.
     */
    suspend fun updateAmount(entry: DiaryEntryEntity, newAmount: Double) {
        if (!newAmount.isFinite()) return
        val amount = newAmount.coerceAtLeast(0.0)
        val updated = if (entry.amount > 0.0 && entry.amount.isFinite()) {
            val f = amount / entry.amount
            entry.copy(
                amount = amount,
                grams = entry.grams * f,
                kcal = entry.kcal * f,
                protein = entry.protein * f,
                carbs = entry.carbs * f,
                fat = entry.fat * f,
            )
        } else {
            recalculated(entry, amount) ?: entry.copy(amount = amount)
        }
        dao.update(updated)
    }

    suspend fun update(entry: DiaryEntryEntity) = dao.update(entry)

    suspend fun delete(entry: DiaryEntryEntity) = dao.delete(entry)

    /** Copies all entries of [meal] on [fromDate] to [toMeal] on [toDate]. Returns the number of copied entries. */
    suspend fun copyMeal(fromDate: LocalDate, meal: MealType, toDate: LocalDate, toMeal: MealType = meal): Int =
        db.withTransaction {
            val entries = dao.getEntriesForMeal(fromDate, meal)
            if (entries.isNotEmpty()) {
                val now = System.currentTimeMillis()
                dao.insertAll(
                    entries.mapIndexed { index, e ->
                        e.copy(id = 0, date = toDate, meal = toMeal, createdAt = now + index)
                    },
                )
            }
            entries.size
        }

    /** Grams and nutrition of [entry] rebuilt for [amount] from its recipe or food; null if that is not possible. */
    private suspend fun recalculated(entry: DiaryEntryEntity, amount: Double): DiaryEntryEntity? {
        val recipeId = entry.recipeId
        if (recipeId != null) {
            val recipe = db.recipeDao().getById(recipeId) ?: return null
            val nutrition = RecipeRepository.nutritionOf(recipe)
            val factor = amount / recipe.recipe.portions.coerceAtLeast(1)
            return entry.withTotals(amount, nutrition.totalGrams * factor, nutrition.total * factor)
        }
        val foodId = entry.foodId ?: return null
        val food = db.foodDao().getById(foodId) ?: return null
        val gramsPerUnit = gramsPerUnit(food, entry.unitLabel) ?: return null
        val grams = gramsPerUnit * amount
        return entry.withTotals(amount, grams, NutritionMath.forGrams(food, grams))
    }

    private fun DiaryEntryEntity.withTotals(amount: Double, grams: Double, n: Nutrition): DiaryEntryEntity =
        copy(amount = amount, grams = grams, kcal = n.kcal, protein = n.protein, carbs = n.carbs, fat = n.fat)

    internal companion object {
        const val RECIPE_UNIT = "Portion"

        private val TRAILING_GRAMS = Regex("""\(\s*(\d+(?:[.,]\d+)?)\s*(?:g|ml)\s*\)\s*$""", RegexOption.IGNORE_CASE)
        private val PLAIN_GRAMS = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(?:g|ml)\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Grams (ml) per unit for a diary [unitLabel] of [food]: 1 for "g"/"ml", the portion's grams for
         * a portion name, "Scheibe (50 g)" → 50, "100 g" → 100; null if unknown.
         */
        fun gramsPerUnit(food: FoodEntity, unitLabel: String): Double? {
            val label = unitLabel.trim()
            if (label.equals("g", ignoreCase = true) || label.equals("ml", ignoreCase = true)) return 1.0
            food.portions.firstOrNull { it.name.trim() == label && it.grams > 0.0 }?.let { return it.grams }
            val match = TRAILING_GRAMS.find(label) ?: PLAIN_GRAMS.find(label) ?: return null
            return match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        }

        private fun Double.nonNegative(): Double = if (isFinite() && this > 0.0) this else 0.0
    }
}
