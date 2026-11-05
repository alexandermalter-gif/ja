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
     */
    suspend fun addFood(
        date: LocalDate,
        meal: MealType,
        food: FoodEntity,
        amount: Double,
        unitLabel: String,
        grams: Double,
    ): Long {
        val n = NutritionMath.forGrams(food, grams)
        val entry = DiaryEntryEntity(
            date = date,
            meal = meal,
            name = food.name,
            brand = food.brand,
            amount = amount,
            unitLabel = unitLabel,
            grams = grams,
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

    /** Logs [portions] portions of a recipe (unitLabel "Portion"). */
    suspend fun addRecipe(
        date: LocalDate,
        meal: MealType,
        recipe: RecipeWithIngredients,
        portions: Double,
    ): Long {
        val nutrition = RecipeRepository.nutritionOf(recipe)
        val recipePortions = recipe.recipe.portions.coerceAtLeast(1).toDouble()
        val total = nutrition.total * (portions / recipePortions)
        val entry = DiaryEntryEntity(
            date = date,
            meal = meal,
            name = recipe.recipe.name,
            amount = portions,
            unitLabel = "Portion",
            grams = nutrition.totalGrams / recipePortions * portions,
            kcal = total.kcal,
            protein = total.protein,
            carbs = total.carbs,
            fat = total.fat,
            recipeId = recipe.recipe.id,
            source = EntrySource.RECIPE,
        )
        return db.withTransaction {
            val id = dao.insert(entry)
            db.recipeDao().markUsed(recipe.recipe.id, System.currentTimeMillis())
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
                kcal = nutrition.kcal,
                protein = nutrition.protein,
                carbs = nutrition.carbs,
                fat = nutrition.fat,
                source = EntrySource.QUICK,
            ),
        )

    suspend fun addEntry(entry: DiaryEntryEntity): Long = dao.insert(entry)

    /** Changes the amount and scales grams and nutrition proportionally (if the old amount > 0). */
    suspend fun updateAmount(entry: DiaryEntryEntity, newAmount: Double) {
        val updated = if (entry.amount > 0.0) {
            val f = newAmount / entry.amount
            entry.copy(
                amount = newAmount,
                grams = entry.grams * f,
                kcal = entry.kcal * f,
                protein = entry.protein * f,
                carbs = entry.carbs * f,
                fat = entry.fat * f,
            )
        } else {
            entry.copy(amount = newAmount)
        }
        dao.update(updated)
    }

    suspend fun update(entry: DiaryEntryEntity) = dao.update(entry)

    suspend fun delete(entry: DiaryEntryEntity) = dao.delete(entry)

    /** Copies all entries of [meal] on [fromDate] to [toMeal] on [toDate]. Returns the number of copied entries. */
    suspend fun copyMeal(fromDate: LocalDate, meal: MealType, toDate: LocalDate, toMeal: MealType = meal): Int {
        val entries = dao.getEntriesForMeal(fromDate, meal)
        if (entries.isEmpty()) return 0
        val now = System.currentTimeMillis()
        dao.insertAll(
            entries.mapIndexed { index, e ->
                e.copy(id = 0, date = toDate, meal = toMeal, createdAt = now + index)
            },
        )
        return entries.size
    }
}
