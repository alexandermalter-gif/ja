package de.kalorienkompass.data.builtin

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
class BuiltinFoodSeederTest {

    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        db = DbTestSupport.inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(version: Int, vararg foods: String) =
        """{"version": $version, "foods": [${foods.joinToString(",")}]}"""

    private fun food(name: String, kcal: Number = 100, extra: String = "") =
        """{"name": "$name", "category": "Test", "kcal": $kcal, "protein": 1, "carbs": 2, "fat": 3$extra,
           "unit": "g", "portions": [{"name": "1 Stück", "grams": 50}]}"""

    @Test
    fun parse_ignoresUnknownFieldsAndSkipsBrokenEntries() {
        val text = file(
            3,
            food("Bier", extra = """, "alcohol": 3.9, "glutenfrei": false"""),
            """{"name": "Ohne Energie", "protein": 1}""", // kcal missing → skipped
            """{"name": "Falscher Typ", "kcal": "viel", "protein": 1, "carbs": 1, "fat": 1}""",
            "42",
            food("Wasser", kcal = 0),
        )
        val parsed = BuiltinFoodSeeder.parse(text)
        assertEquals(3, parsed.version)
        assertEquals(listOf("Bier", "Wasser"), parsed.foods.map { it.name })
        assertEquals(3.9, parsed.foods.first().alcohol!!, 1e-9)
    }

    @Test
    fun parse_realAssetFile() {
        val text = DbTestSupport.context.assets.open(BuiltinFoodSeeder.ASSET_FILE).bufferedReader().use { it.readText() }
        val parsed = BuiltinFoodSeeder.parse(text)
        assertTrue("expected ≥ 200 foods, got ${parsed.foods.size}", parsed.foods.size >= 200)
        val entities = parsed.foods.map { it.toEntity() }
        assertTrue(entities.all { it.source == FoodSource.BUILTIN && it.kcal >= 0.0 && it.unit in setOf("g", "ml") })
    }

    @Test
    fun toEntity_sanitizesValues() {
        val entity = BuiltinFoodJson(
            name = "  Test  ", category = " ", kcal = -5.0, protein = Double.NaN, carbs = 1.0, fat = 1.0,
            unit = " ML ", portions = listOf(
                de.kalorienkompass.data.model.FoodPortion(" Glas ", 200.0),
                de.kalorienkompass.data.model.FoodPortion("", 100.0),
                de.kalorienkompass.data.model.FoodPortion("Null", 0.0),
            ),
        ).toEntity()
        assertEquals("Test", entity.name)
        assertNull(entity.category)
        assertEquals(0.0, entity.kcal, 1e-9)
        assertEquals(0.0, entity.protein, 1e-9)
        assertEquals("ml", entity.unit)
        assertEquals(listOf("Glas"), entity.portions.map { it.name })
    }

    @Test
    fun seedsTheRealAssetOnceAndIsIdempotent() = runBlocking {
        val seeder = BuiltinFoodSeeder(DbTestSupport.context, db, json)
        seeder.seedIfNeeded()
        val count = db.foodDao().countBySource(FoodSource.BUILTIN)
        assertTrue("expected ≥ 200 built-in foods, got $count", count >= 200)
        seeder.seedIfNeeded()
        assertEquals(count, db.foodDao().countBySource(FoodSource.BUILTIN))
    }

    @Test
    fun concurrentSeedingFromSeveralInstancesInsertsEveryFoodOnce() = runBlocking {
        val text = file(1, food("Apfel"), food("Birne"), food("Banane"))
        (1..6).map {
            async(Dispatchers.Default) { BuiltinFoodSeeder(DbTestSupport.context, db, json).seedIfNeeded { text } }
        }.awaitAll()
        assertEquals(listOf("Apfel", "Banane", "Birne"), db.foodDao().getBySource(FoodSource.BUILTIN).map { it.name }.sorted())
    }

    @Test
    fun newVersionUpdatesExistingFoodsKeepingIdFavoriteAndUsage() = runBlocking {
        val seeder = BuiltinFoodSeeder(DbTestSupport.context, db, json)
        seeder.seedIfNeeded { file(1, food("Apfel", kcal = 50), food("Birne")) }
        val dao = db.foodDao()
        val apple = dao.getBySource(FoodSource.BUILTIN).first { it.name == "Apfel" }
        dao.setFavorite(apple.id, true)
        dao.markUsed(apple.id, 1_234)

        // same version again: nothing changes even if the content differs
        seeder.seedIfNeeded { file(1, food("Apfel", kcal = 99)) }
        assertEquals(50.0, dao.getById(apple.id)!!.kcal, 1e-9)

        seeder.seedIfNeeded { file(2, food("Apfel", kcal = 52), food("Birne"), food("Kiwi")) }
        val updated = dao.getById(apple.id)!!
        assertEquals(52.0, updated.kcal, 1e-9)
        assertTrue(updated.isFavorite)
        assertEquals(1, updated.useCount)
        assertEquals(1_234L, updated.lastUsedAt)
        assertEquals(apple.createdAt, updated.createdAt)
        assertEquals(listOf("Apfel", "Birne", "Kiwi"), dao.getBySource(FoodSource.BUILTIN).map { it.name })
    }

    @Test
    fun brokenFileDoesNotCrashAndReseedsWhenTheTableIsEmpty() = runBlocking {
        val seeder = BuiltinFoodSeeder(DbTestSupport.context, db, json)
        seeder.seedIfNeeded { "kein JSON" } // logged, not thrown
        seeder.seedIfNeeded { throw java.io.IOException("asset missing") }
        assertEquals(0, db.foodDao().countBySource(FoodSource.BUILTIN))

        seeder.seedIfNeeded { file(1, food("Apfel")) }
        assertEquals(1, db.foodDao().countBySource(FoodSource.BUILTIN))
        db.foodDao().deleteAll()
        // version 1 is already stored, but the built-in foods are gone (e.g. database reset) → seed again
        seeder.seedIfNeeded { file(1, food("Apfel")) }
        assertEquals(1, db.foodDao().countBySource(FoodSource.BUILTIN))
    }
}
