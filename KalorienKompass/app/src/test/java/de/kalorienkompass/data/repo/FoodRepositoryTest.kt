package de.kalorienkompass.data.repo

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.DbTestSupport.food
import de.kalorienkompass.data.FakeRemoteDataSource
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FoodRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var remote: FakeRemoteDataSource
    private lateinit var repo: FoodRepository

    @Before
    fun setUp() {
        db = DbTestSupport.inMemoryDatabase()
        remote = FakeRemoteDataSource()
        repo = FoodRepository(db, remote)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- local search ----

    @Test
    fun searchLocal_findsUmlautsCaseInsensitively() = runBlocking {
        db.foodDao().insertAll(listOf(food("Äpfel, getrocknet"), food("Bratäpfel"), food("Müsli"), food("Öl, Raps")))
        assertEquals(listOf("Äpfel, getrocknet", "Bratäpfel"), repo.searchLocal("äpfel").map { it.name })
        assertEquals(listOf("Müsli"), repo.searchLocal("MÜSLI").map { it.name })
        assertEquals(listOf("Öl, Raps"), repo.searchLocal("öl").map { it.name })
    }

    @Test
    fun searchLocal_multiWordQueriesMatchWordsInAnyOrder() = runBlocking {
        db.foodDao().insertAll(listOf(food("Reis, gekocht"), food("Reis, roh"), food("Nudeln, gekocht")))
        assertEquals(listOf("Reis, gekocht"), repo.searchLocal("Reis gekocht").map { it.name })
        assertEquals(listOf("Reis, gekocht"), repo.searchLocal("gekocht   reis").map { it.name })
        assertTrue(repo.searchLocal("   ").isEmpty())
        assertTrue(repo.searchLocal("Reis", limit = 0).isEmpty())
    }

    @Test
    fun searchLocal_wildcardsAreLiteralAndLimitIsRespected() = runBlocking {
        db.foodDao().insertAll((1..20).map { food("Joghurt $it") } + food("Milch 1,5%"))
        assertEquals(listOf("Milch 1,5%"), repo.searchLocal("%").map { it.name })
        assertTrue(repo.searchLocal("_").isEmpty())
        assertEquals(5, repo.searchLocal("joghurt", limit = 5).size)
    }

    // ---- barcode lookup & remote cache ----

    @Test
    fun lookupBarcode_localFirstIncludingUpcEanVariant() = runBlocking {
        val id = db.foodDao().insert(food("Cola", barcode = "0123456789012"))
        val found = repo.lookupBarcode(" 123456789012 ")
        assertEquals(id, (found as BarcodeLookupResult.Found).food.id)
        assertTrue("remote must not be called", remote.barcodeRequests.isEmpty())
    }

    @Test
    fun lookupBarcode_remoteProductIsCachedAndReused() = runBlocking {
        remote.products = mapOf("4000000000001" to food("Keks", source = FoodSource.OPEN_FOOD_FACTS))
        val first = repo.lookupBarcode("4000000000001") as BarcodeLookupResult.Found
        assertTrue(first.food.id != 0L)
        assertEquals("4000000000001", first.food.barcode) // barcode filled in when the remote food has none
        assertEquals(FoodSource.OPEN_FOOD_FACTS, first.food.source)

        val second = repo.lookupBarcode("4000000000001") as BarcodeLookupResult.Found
        assertEquals(first.food.id, second.food.id)
        assertEquals(1, remote.barcodeRequests.size)
    }

    @Test
    fun lookupBarcode_notFoundAndErrors() = runBlocking {
        assertEquals(BarcodeLookupResult.NotFound, repo.lookupBarcode("   "))
        assertEquals(BarcodeLookupResult.NotFound, repo.lookupBarcode("4000000000002"))

        val offline = FoodRepository(db, FakeRemoteDataSource.offline())
        val error = offline.lookupBarcode("4000000000003")
        assertTrue(error is BarcodeLookupResult.Error)
        assertTrue((error as BarcodeLookupResult.Error).message.contains("Internetverbindung"))

        val broken = FoodRepository(db, FakeRemoteDataSource().apply { failWith = IllegalStateException("kaputt") })
        assertTrue(broken.lookupBarcode("4000000000004") is BarcodeLookupResult.Error)
    }

    @Test
    fun cacheRemoteFood_updatesByBarcodeKeepingIdFavoriteAndUsage() = runBlocking {
        val id = repo.cacheRemoteFood(food("Keks alt", kcal = 400.0, barcode = "4001", source = FoodSource.OPEN_FOOD_FACTS))
        repo.setFavorite(id, true)
        repo.markUsed(id)
        repo.markUsed(id)
        val before = repo.getFood(id)!!

        val again = repo.cacheRemoteFood(food("Keks neu", kcal = 450.0, barcode = " 4001 ", source = FoodSource.OPEN_FOOD_FACTS))
        assertEquals(id, again)
        val after = repo.getFood(id)!!
        assertEquals("Keks neu", after.name)
        assertEquals(450.0, after.kcal, 1e-9)
        assertTrue(after.isFavorite)
        assertEquals(2, after.useCount)
        assertEquals(before.lastUsedAt, after.lastUsedAt)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(1, db.foodDao().getAll().size)
    }

    @Test
    fun cacheRemoteFood_neverOverwritesTheUsersOwnFood() = runBlocking {
        val customId = repo.saveCustomFood(food("Mein Müsli", kcal = 350.0, barcode = "4002"))
        val cachedId = repo.cacheRemoteFood(food("OFF Müsli", kcal = 999.0, barcode = "4002", source = FoodSource.OPEN_FOOD_FACTS))
        assertEquals(customId, cachedId)
        assertEquals("Mein Müsli", repo.getFood(customId)!!.name)
        assertEquals(350.0, repo.getFood(customId)!!.kcal, 1e-9)
    }

    @Test
    fun cacheRemoteFood_withoutBarcodeDeduplicatesByNameAndBrand() = runBlocking {
        val a = repo.cacheRemoteFood(food("Brezel", brand = "Bäcker", source = FoodSource.OPEN_FOOD_FACTS))
        val b = repo.cacheRemoteFood(food("Brezel", brand = "Bäcker", kcal = 300.0, source = FoodSource.OPEN_FOOD_FACTS))
        val c = repo.cacheRemoteFood(food("Brezel", brand = null, source = FoodSource.OPEN_FOOD_FACTS))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(300.0, repo.getFood(a)!!.kcal, 1e-9)
    }

    @Test
    fun cacheRemoteFood_concurrentCallsCreateOneRow() = runBlocking {
        val ids = (1..8).map {
            async(Dispatchers.Default) {
                repo.cacheRemoteFood(food("Saft", barcode = "4003", source = FoodSource.OPEN_FOOD_FACTS))
            }
        }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(1, db.foodDao().getAll().size)
    }

    // ---- custom foods ----

    @Test
    fun saveCustomFood_normalizesAndRejectsBarcodeOfAnotherFood() = runBlocking {
        val id = repo.saveCustomFood(food("  Quark  ", brand = "  ", barcode = " 4004 "))
        val saved = repo.getFood(id)!!
        assertEquals("Quark", saved.name)
        assertEquals(null, saved.brand)
        assertEquals("4004", saved.barcode)

        // updating the same food with its own barcode is fine
        assertEquals(id, repo.saveCustomFood(saved.copy(kcal = 70.0)))
        assertEquals(70.0, repo.getFood(id)!!.kcal, 1e-9)

        try {
            repo.saveCustomFood(food("Skyr", barcode = "4004"))
            fail("barcode conflict expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Quark"))
        }
        assertEquals(1, db.foodDao().getAll().size)
    }

    @Test
    fun saveCustomFood_reinsertsAFoodThatWasDeletedMeanwhile() = runBlocking {
        val id = repo.saveCustomFood(food("Hummus"))
        val stale = repo.getFood(id)!!
        assertTrue(repo.deleteFood(stale))
        assertEquals(id, repo.saveCustomFood(stale.copy(kcal = 166.0)))
        assertEquals(166.0, repo.getFood(id)!!.kcal, 1e-9)
    }

    @Test
    fun deleteFood_refusesFoodsUsedByARecipe() = runBlocking {
        val id = repo.saveCustomFood(food("Mehl"))
        val recipeId = db.recipeDao().insertRecipe(RecipeEntity(name = "Brot"))
        db.recipeDao().insertIngredients(listOf(RecipeIngredientEntity(recipeId = recipeId, foodId = id, grams = 500.0)))
        assertFalse(repo.deleteFood(repo.getFood(id)!!))
        assertTrue(repo.getFood(id) != null)
    }
}
