package de.kalorienkompass.data.repo

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.DbTestSupport.food
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DiaryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DiaryRepository
    private lateinit var recipes: RecipeRepository
    private val day = LocalDate.of(2026, 11, 5)

    @Before
    fun setUp() {
        db = DbTestSupport.inMemoryDatabase()
        repo = DiaryRepository(db)
        recipes = RecipeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun storedFood(
        name: String = "Brot",
        kcal: Double = 250.0,
        portions: List<FoodPortion> = emptyList(),
        source: FoodSource = FoodSource.BUILTIN,
    ) = db.foodDao().let { dao -> dao.getById(dao.insert(food(name, kcal = kcal, protein = 8.0, carbs = 48.0, fat = 1.5, source = source, portions = portions)))!! }

    @Test
    fun addFood_computesTotalsAndMarksFoodUsed() = runBlocking {
        val bread = storedFood(portions = listOf(FoodPortion("Scheibe (50 g)", 50.0)))
        val id = repo.addFood(day, MealType.BREAKFAST, bread, amount = 2.0, unitLabel = "Scheibe (50 g)", grams = 100.0)
        val e = repo.getEntry(id)!!
        assertEquals(250.0, e.kcal, 1e-9)
        assertEquals(8.0, e.protein, 1e-9)
        assertEquals(48.0, e.carbs, 1e-9)
        assertEquals(1.5, e.fat, 1e-9)
        assertEquals(100.0, e.grams, 1e-9)
        assertEquals(bread.id, e.foodId)
        assertEquals(EntrySource.FOOD, e.source)
        val used = db.foodDao().getById(bread.id)!!
        assertEquals(1, used.useCount)
        assertNotNull(used.lastUsedAt)
    }

    @Test
    fun addFood_aiFoodsAndInvalidAmounts() = runBlocking {
        val ai = storedFood("KI-Pasta", source = FoodSource.AI)
        val id = repo.addFood(day, MealType.DINNER, ai, amount = -3.0, unitLabel = "g", grams = Double.NaN)
        val e = repo.getEntry(id)!!
        assertEquals(EntrySource.AI, e.source)
        assertEquals(0.0, e.amount, 1e-9)
        assertEquals(0.0, e.grams, 1e-9)
        assertEquals(0.0, e.kcal, 1e-9)
    }

    @Test
    fun addRecipe_scalesByPortionsAndSkipsMissingFoods() = runBlocking {
        val flour = storedFood("Mehl", kcal = 350.0)
        val recipeId = recipes.save(
            RecipeEntity(name = "Brot", portions = 4),
            listOf(
                RecipeIngredientEntity(recipeId = 0, foodId = flour.id, grams = 400.0),
                RecipeIngredientEntity(recipeId = 0, foodId = 9_999, grams = 100.0), // food does not exist
            ),
        )
        val recipe = recipes.get(recipeId)!!
        val id = repo.addRecipe(day, MealType.LUNCH, recipe, portions = 1.5)
        val e = repo.getEntry(id)!!
        // 400 g flour = 1400 kcal for 4 portions → 1.5 portions = 525 kcal, 150 g
        assertEquals(525.0, e.kcal, 1e-9)
        assertEquals(150.0, e.grams, 1e-9)
        assertEquals(1.5, e.amount, 1e-9)
        assertEquals("Portion", e.unitLabel)
        assertEquals(recipeId, e.recipeId)
        assertEquals(EntrySource.RECIPE, e.source)
        assertNotNull(recipes.get(recipeId)!!.recipe.lastUsedAt)
    }

    @Test
    fun addQuick_defaultsAndNonNegativeValues() = runBlocking {
        val id = repo.addQuick(day, MealType.SNACK, "  ", Nutrition(kcal = 150.0, protein = -1.0, carbs = Double.NaN, fat = 5.0))
        val e = repo.getEntry(id)!!
        assertEquals("Schnelleintrag", e.name)
        assertEquals(1.0, e.amount, 1e-9)
        assertEquals("", e.unitLabel)
        assertEquals(0.0, e.grams, 1e-9)
        assertEquals(150.0, e.kcal, 1e-9)
        assertEquals(0.0, e.protein, 1e-9)
        assertEquals(0.0, e.carbs, 1e-9)
        assertEquals(EntrySource.QUICK, e.source)
    }

    @Test
    fun updateAmount_scalesProportionally() = runBlocking {
        val bread = storedFood()
        val id = repo.addFood(day, MealType.BREAKFAST, bread, amount = 100.0, unitLabel = "g", grams = 100.0)
        repo.updateAmount(repo.getEntry(id)!!, 150.0)
        val e = repo.getEntry(id)!!
        assertEquals(150.0, e.amount, 1e-9)
        assertEquals(150.0, e.grams, 1e-9)
        assertEquals(375.0, e.kcal, 1e-9)
        assertEquals(12.0, e.protein, 1e-9)

        // quick entries scale too (amount 1 → 2 × "Schnelleintrag")
        val quickId = repo.addQuick(day, MealType.SNACK, "Kuchen", Nutrition(300.0, 5.0, 40.0, 12.0))
        repo.updateAmount(repo.getEntry(quickId)!!, 2.0)
        assertEquals(600.0, repo.getEntry(quickId)!!.kcal, 1e-9)
    }

    @Test
    fun updateAmount_zeroCanBeUndoneByRecalculatingFromTheFood() = runBlocking {
        val bread = storedFood(portions = listOf(FoodPortion("Scheibe", 50.0)))
        val gramsId = repo.addFood(day, MealType.BREAKFAST, bread, amount = 100.0, unitLabel = "g", grams = 100.0)
        val sliceId = repo.addFood(day, MealType.BREAKFAST, bread, amount = 2.0, unitLabel = "Scheibe", grams = 100.0)
        val labelId = repo.addFood(day, MealType.BREAKFAST, bread, amount = 1.0, unitLabel = "Stück (80 g)", grams = 80.0)

        for (id in listOf(gramsId, sliceId, labelId)) {
            repo.updateAmount(repo.getEntry(id)!!, 0.0)
            val zero = repo.getEntry(id)!!
            assertEquals(0.0, zero.kcal, 1e-9)
            assertEquals(0.0, zero.grams, 1e-9)
        }

        repo.updateAmount(repo.getEntry(gramsId)!!, 200.0)
        assertEquals(200.0, repo.getEntry(gramsId)!!.grams, 1e-9)
        assertEquals(500.0, repo.getEntry(gramsId)!!.kcal, 1e-9)

        repo.updateAmount(repo.getEntry(sliceId)!!, 3.0)
        assertEquals(150.0, repo.getEntry(sliceId)!!.grams, 1e-9)
        assertEquals(375.0, repo.getEntry(sliceId)!!.kcal, 1e-9)

        repo.updateAmount(repo.getEntry(labelId)!!, 2.0)
        assertEquals(160.0, repo.getEntry(labelId)!!.grams, 1e-9)
        assertEquals(400.0, repo.getEntry(labelId)!!.kcal, 1e-9)
    }

    @Test
    fun updateAmount_zeroRecipeEntryIsRecalculatedFromTheRecipe() = runBlocking {
        val flour = storedFood("Mehl", kcal = 350.0)
        val recipeId = recipes.save(
            RecipeEntity(name = "Brot", portions = 2),
            listOf(RecipeIngredientEntity(recipeId = 0, foodId = flour.id, grams = 200.0)),
        )
        val id = repo.addRecipe(day, MealType.LUNCH, recipes.get(recipeId)!!, portions = 1.0)
        repo.updateAmount(repo.getEntry(id)!!, 0.0)
        repo.updateAmount(repo.getEntry(id)!!, 2.0)
        val e = repo.getEntry(id)!!
        assertEquals(700.0, e.kcal, 1e-9)
        assertEquals(200.0, e.grams, 1e-9)
    }

    @Test
    fun updateAmount_negativeIsZeroAndNaNIsIgnored() = runBlocking {
        val bread = storedFood()
        val id = repo.addFood(day, MealType.BREAKFAST, bread, amount = 100.0, unitLabel = "g", grams = 100.0)
        repo.updateAmount(repo.getEntry(id)!!, Double.NaN)
        assertEquals(250.0, repo.getEntry(id)!!.kcal, 1e-9)
        repo.updateAmount(repo.getEntry(id)!!, -50.0)
        val e = repo.getEntry(id)!!
        assertEquals(0.0, e.amount, 1e-9)
        assertEquals(0.0, e.kcal, 1e-9)
        assertTrue(e.grams >= 0.0)
    }

    @Test
    fun updateAmount_entryWithoutSourceOnlyChangesTheAmount() = runBlocking {
        val id = repo.addEntry(
            DiaryEntryEntity(
                date = day, meal = MealType.LUNCH, name = "Alt", amount = 0.0, unitLabel = "g", grams = 0.0,
                kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0, foodId = 12_345, source = EntrySource.FOOD,
            ),
        )
        repo.updateAmount(repo.getEntry(id)!!, 100.0)
        val e = repo.getEntry(id)!!
        assertEquals(100.0, e.amount, 1e-9)
        assertEquals(0.0, e.kcal, 1e-9)
    }

    @Test
    fun copyMeal_copiesEntriesInOrderToTheTargetMeal() = runBlocking {
        val bread = storedFood()
        repo.addFood(day, MealType.BREAKFAST, bread, 100.0, "g", 100.0)
        repo.addQuick(day, MealType.BREAKFAST, "Kaffee", Nutrition(5.0, 0.0, 0.0, 0.0))
        repo.addQuick(day, MealType.LUNCH, "Suppe", Nutrition(200.0, 0.0, 0.0, 0.0))

        val tomorrow = day.plusDays(1)
        assertEquals(2, repo.copyMeal(day, MealType.BREAKFAST, tomorrow, MealType.SNACK))
        val copied = repo.observeDay(tomorrow).first()
        assertEquals(listOf("Brot", "Kaffee"), copied.map { it.name })
        assertTrue(copied.all { it.meal == MealType.SNACK && it.date == tomorrow })
        assertEquals(bread.id, copied.first().foodId)
        assertEquals(3, repo.observeDay(day).first().size) // source day unchanged

        assertEquals(0, repo.copyMeal(day, MealType.DINNER, tomorrow))
        assertNull(repo.observeDay(day.minusDays(1)).first().firstOrNull())
    }

    @Test
    fun gramsPerUnit_parsesUnitLabels() {
        val bread = food("Brot", portions = listOf(FoodPortion("Scheibe", 50.0), FoodPortion("Kaputt", 0.0)))
        assertEquals(1.0, DiaryRepository.gramsPerUnit(bread, "g")!!, 1e-9)
        assertEquals(1.0, DiaryRepository.gramsPerUnit(bread, " ML ")!!, 1e-9)
        assertEquals(50.0, DiaryRepository.gramsPerUnit(bread, "Scheibe")!!, 1e-9)
        assertEquals(30.5, DiaryRepository.gramsPerUnit(bread, "Riegel (30,5 g)")!!, 1e-9)
        assertEquals(330.0, DiaryRepository.gramsPerUnit(bread, "Dose (330 ml)")!!, 1e-9)
        assertEquals(100.0, DiaryRepository.gramsPerUnit(bread, "100 g")!!, 1e-9)
        assertNull(DiaryRepository.gramsPerUnit(bread, "Kaputt"))
        assertNull(DiaryRepository.gramsPerUnit(bread, "Portion"))
        assertNull(DiaryRepository.gramsPerUnit(bread, ""))
    }
}
