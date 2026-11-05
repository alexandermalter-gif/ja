package de.kalorienkompass.ui.diary

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.domain.DailyTargets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import java.time.LocalDate

/**
 * In-memory database whose queries, transactions and invalidation callbacks all run on the
 * test dispatcher, so that `advanceUntilIdle()` deterministically drains Room work too.
 */
internal fun inMemoryDatabase(dispatcher: CoroutineDispatcher): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val executor = dispatcher.asExecutor()
    return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(executor)
        .setTransactionExecutor(executor)
        .build()
}

internal val TEST_TARGETS = DailyTargets(
    calories = 2000,
    carbsG = 250,
    proteinG = 100,
    fatG = 67,
    mealCalories = mapOf(
        MealType.BREAKFAST to 600,
        MealType.LUNCH to 800,
        MealType.DINNER to 500,
        MealType.SNACK to 100,
    ),
)

internal fun testEntry(
    date: LocalDate,
    meal: MealType,
    name: String,
    kcal: Double,
    amount: Double = 100.0,
    unitLabel: String = "g",
    grams: Double = amount,
    protein: Double = 0.0,
    carbs: Double = 0.0,
    fat: Double = 0.0,
    source: EntrySource = EntrySource.FOOD,
    createdAt: Long = 1_000L,
    id: Long = 0L,
) = DiaryEntryEntity(
    id = id,
    date = date,
    meal = meal,
    name = name,
    amount = amount,
    unitLabel = unitLabel,
    grams = grams,
    kcal = kcal,
    protein = protein,
    carbs = carbs,
    fat = fat,
    source = source,
    createdAt = createdAt,
)
