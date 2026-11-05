package de.kalorienkompass.data.db

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.DbTestSupport.food
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Room queries on an in-memory database (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DaoQueriesTest {

    private lateinit var db: AppDatabase
    private val day = LocalDate.of(2026, 11, 5)

    @Before
    fun setUp() {
        db = DbTestSupport.inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- FoodDao ----

    @Test
    fun search_treatsPercentAndUnderscoreLiterally() = runBlocking {
        val dao = db.foodDao()
        dao.insertAll(
            listOf(
                food("Milch 1,5%"),
                food("Milch 3,5 Prozent"),
                food("Saft_Mix"),
                food("Saftmix"),
                food("Back\\slash"),
            ),
        )
        assertEquals(listOf("Milch 1,5%"), dao.search("%", 50).map { it.name })
        assertEquals(listOf("Milch 1,5%"), dao.search("5%", 50).map { it.name })
        assertEquals(listOf("Saft_Mix"), dao.search("_", 50).map { it.name })
        assertEquals(listOf("Saft_Mix"), dao.search("t_M", 50).map { it.name })
        assertEquals(listOf("Back\\slash"), dao.search("\\", 50).map { it.name })
        assertEquals(2, dao.search("milch", 50).size) // ASCII letters stay case-insensitive
    }

    @Test
    fun search_ordersPrefixThenUseCountThenShorterNames() = runBlocking {
        val dao = db.foodDao()
        dao.insertAll(
            listOf(
                food("Apfelmus", useCount = 1),
                food("Bratapfel", useCount = 10),
                food("Apfel", useCount = 1),
                food("Apfelsaft", useCount = 5),
                food("Müsli", brand = "Apfelland"),
            ),
        )
        assertEquals(
            listOf("Apfelsaft", "Apfel", "Apfelmus", "Bratapfel", "Müsli"),
            dao.search("apfel", 50).map { it.name },
        )
        assertEquals(listOf("Apfelsaft", "Apfel"), dao.search("apfel", 2).map { it.name })
    }

    @Test
    fun barcodeIsUniqueButNullBarcodesAreAllowedTwice() = runBlocking {
        val dao = db.foodDao()
        dao.insert(food("A"))
        dao.insert(food("B"))
        dao.insert(food("C", barcode = "4001"))
        try {
            dao.insert(food("D", barcode = "4001"))
            fail("duplicate barcode must be rejected")
        } catch (e: SQLiteConstraintException) {
            // expected
        }
        assertEquals("C", dao.getByBarcode("4001")?.name)
    }

    @Test
    fun recent_onlyUsedFoodsNewestFirst() = runBlocking {
        val dao = db.foodDao()
        val a = dao.insert(food("A"))
        val b = dao.insert(food("B"))
        dao.insert(food("C"))
        dao.markUsed(a, 1_000)
        dao.markUsed(b, 2_000)
        dao.markUsed(a, 3_000)
        assertEquals(listOf("A", "B"), dao.observeRecent(10).first().map { it.name })
        assertEquals(2, dao.getById(a)?.useCount)
    }

    @Test
    fun portionsRoundTripThroughConverter() = runBlocking {
        val dao = db.foodDao()
        val portions = listOf(FoodPortion("Scheibe", 50.0), FoodPortion("Packung „groß“", 500.0))
        val id = dao.insert(food("Brot", portions = portions))
        assertEquals(portions, dao.getById(id)?.portions)
    }

    // ---- DiaryDao ----

    @Test
    fun dailyTotals_groupByDateWithinRange() = runBlocking {
        val dao = db.diaryDao()
        dao.insertAll(
            listOf(
                entry(day.minusDays(1), kcal = 100.0),
                entry(day, kcal = 200.0, protein = 10.0),
                entry(day, kcal = 300.5, protein = 2.5),
                entry(day.plusDays(1), kcal = 400.0),
                entry(day.plusDays(5), kcal = 999.0),
            ),
        )
        val totals = dao.observeDailyTotals(day.minusDays(1), day.plusDays(1)).first()
        assertEquals(listOf(day.minusDays(1), day, day.plusDays(1)), totals.map { it.date })
        assertEquals(500.5, totals[1].kcal, 1e-9)
        assertEquals(12.5, totals[1].protein, 1e-9)
        assertEquals(400.0, totals[2].kcal, 1e-9)
        assertTrue(dao.observeDailyTotals(day.plusDays(2), day.plusDays(3)).first().isEmpty())
    }

    @Test
    fun observeDay_ordersByCreationTime() = runBlocking {
        val dao = db.diaryDao()
        dao.insert(entry(day, name = "B", createdAt = 2_000))
        dao.insert(entry(day, name = "A", createdAt = 1_000))
        dao.insert(entry(day, name = "C", createdAt = 2_000))
        dao.insert(entry(day.plusDays(1), name = "X", createdAt = 0))
        assertEquals(listOf("A", "B", "C"), dao.observeDay(day).first().map { it.name })
        assertEquals(listOf("A", "B", "C"), dao.getEntriesForMeal(day, MealType.LUNCH).map { it.name })
    }

    // ---- MeasurementDao ----

    @Test
    fun latestOnOrBefore_usesDateThenCreatedAtThenId() = runBlocking {
        val dao = db.measurementDao()
        dao.insert(measurement(day.minusDays(3), 80.0, createdAt = 9_000))
        dao.insert(measurement(day, 81.0, createdAt = 5_000))
        dao.insert(measurement(day, 82.0, createdAt = 5_000)) // same createdAt → higher id wins
        dao.insert(measurement(day, 79.0, createdAt = 4_000))
        dao.insert(measurement(day.plusDays(1), 70.0, createdAt = 1_000))
        dao.insert(measurement(day, 50.0, type = MeasurementType.WAIST, createdAt = 99_000))

        assertEquals(82.0, dao.getLatestOnOrBefore(MeasurementType.WEIGHT, day)!!.value, 1e-9)
        assertEquals(82.0, dao.observeLatestOnOrBefore(MeasurementType.WEIGHT, day).first()!!.value, 1e-9)
        assertEquals(80.0, dao.getLatestOnOrBefore(MeasurementType.WEIGHT, day.minusDays(1))!!.value, 1e-9)
        assertNull(dao.getLatestOnOrBefore(MeasurementType.WEIGHT, day.minusDays(4)))
        assertEquals(70.0, dao.observeLatest(MeasurementType.WEIGHT).first()!!.value, 1e-9)
        assertEquals(
            listOf(80.0, 79.0, 81.0, 82.0, 70.0),
            dao.observeByType(MeasurementType.WEIGHT).first().map { it.value },
        )
        assertEquals(
            listOf(79.0, 81.0, 82.0),
            dao.observeByTypeInRange(MeasurementType.WEIGHT, day, day).first().map { it.value },
        )
    }

    @Test
    fun getForDate_filtersBySource() = runBlocking {
        val dao = db.measurementDao()
        dao.insert(measurement(day, 80.0, source = DataSource.HEALTH_CONNECT, externalId = "hc-1"))
        assertNull(dao.getForDate(MeasurementType.WEIGHT, day, DataSource.MANUAL))
        dao.insert(measurement(day, 81.0))
        assertEquals(81.0, dao.getForDate(MeasurementType.WEIGHT, day, DataSource.MANUAL)!!.value, 1e-9)
        assertEquals(80.0, dao.getByExternalId("hc-1")!!.value, 1e-9)
    }

    // ---- ActivityDao / RecipeDao ----

    @Test
    fun activityUpsertReplacesTheDay() = runBlocking {
        val dao = db.activityDao()
        dao.upsert(DailyActivityEntity(date = day, steps = 100, source = DataSource.SENSOR))
        dao.upsert(DailyActivityEntity(date = day, steps = 5_000, activeKcal = 210.0, source = DataSource.HEALTH_CONNECT))
        val stored = dao.get(day)!!
        assertEquals(5_000L, stored.steps)
        assertEquals(DataSource.HEALTH_CONNECT, stored.source)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun deletingARecipeCascadesToItsIngredients() = runBlocking {
        val foodId = db.foodDao().insert(food("Mehl"))
        val recipeDao = db.recipeDao()
        val recipeId = recipeDao.insertRecipe(RecipeEntity(name = "Brot", portions = 2))
        recipeDao.insertIngredients(listOf(RecipeIngredientEntity(recipeId = recipeId, foodId = foodId, grams = 500.0)))
        assertEquals(1, recipeDao.countRecipesUsingFood(foodId))
        val loaded = recipeDao.getById(recipeId)
        assertNotNull(loaded)
        assertEquals("Mehl", loaded!!.ingredients.single().food?.name)

        recipeDao.deleteRecipe(loaded.recipe)
        assertTrue(recipeDao.getAllIngredients().isEmpty())
        assertEquals(0, recipeDao.countRecipesUsingFood(foodId))
    }

    private fun entry(
        date: LocalDate,
        kcal: Double = 100.0,
        protein: Double = 1.0,
        name: String = "Essen",
        createdAt: Long = System.currentTimeMillis(),
    ) = DiaryEntryEntity(
        date = date,
        meal = MealType.LUNCH,
        name = name,
        amount = 100.0,
        unitLabel = "g",
        grams = 100.0,
        kcal = kcal,
        protein = protein,
        carbs = 1.0,
        fat = 1.0,
        source = EntrySource.FOOD,
        createdAt = createdAt,
    )

    private fun measurement(
        date: LocalDate,
        value: Double,
        type: MeasurementType = MeasurementType.WEIGHT,
        source: DataSource = DataSource.MANUAL,
        externalId: String? = null,
        createdAt: Long = System.currentTimeMillis(),
    ) = MeasurementEntity(type = type, date = date, value = value, source = source, externalId = externalId, createdAt = createdAt)
}
