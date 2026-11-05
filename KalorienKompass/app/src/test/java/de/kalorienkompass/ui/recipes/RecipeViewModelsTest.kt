package de.kalorienkompass.ui.recipes

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.remote.FoodRemoteDataSource
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.data.repo.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** ViewModels of the recipe feature against a real (in-memory) Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecipeViewModelsTest {

    private lateinit var db: AppDatabase
    private lateinit var foods: FoodRepository
    private lateinit var recipes: RecipeRepository
    private lateinit var diary: DiaryRepository
    private val store = ViewModelStore()
    private var vmCounter = 0

    private object NoRemote : FoodRemoteDataSource {
        override suspend fun findByBarcode(barcode: String): FoodEntity? = null
        override suspend fun search(query: String, page: Int, pageSize: Int): List<FoodEntity> = emptyList()
    }

    private val oats = FoodEntity(
        name = "Haferflocken", kcal = 370.0, protein = 13.5, carbs = 58.7, fat = 7.0,
        source = FoodSource.BUILTIN, portions = listOf(FoodPortion("1 EL", 10.0)),
    )
    private val milk = FoodEntity(
        name = "Milch 1,5 %", kcal = 47.0, protein = 3.4, carbs = 4.8, fat = 1.5,
        unit = "ml", source = FoodSource.BUILTIN,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        foods = FoodRepository(db, NoRemote)
        recipes = RecipeRepository(db)
        diary = DiaryRepository(db)
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private inline fun <reified VM : ViewModel> create(crossinline factory: () -> VM): VM =
        ViewModelProvider(store, viewModelFactory { initializer { factory() } })
            .get("vm${vmCounter++}", VM::class.java)

    private fun editVm(recipeId: Long?, handle: SavedStateHandle = SavedStateHandle()) =
        create { RecipeEditViewModel(recipes, foods, handle, recipeId) }

    private fun <T> TestScope.record(flow: Flow<T>): StateFlow<List<T>> {
        val list = MutableStateFlow<List<T>>(emptyList())
        backgroundScope.launch { flow.collect { e -> list.update { it + e } } }
        return list
    }

    private suspend inline fun <reified E> StateFlow<List<*>>.await(): E =
        first { events -> events.any { it is E } }.filterIsInstance<E>().first()

    private suspend fun RecipeEditViewModel.loaded(): RecipeEditUiState = state.first { !it.isLoading }

    private suspend fun storeRecipe(name: String, portions: Int, vararg items: Pair<Long, Double>, favorite: Boolean = false): Long =
        recipes.save(
            RecipeEntity(name = name, portions = portions, isFavorite = favorite, createdAt = 42L),
            items.map { (foodId, grams) -> RecipeIngredientEntity(recipeId = 0, foodId = foodId, grams = grams) },
        )

    // ---------------------------------------------------------------- editor

    @Test
    fun `new recipe with picked ingredients is saved`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val milkId = db.foodDao().insert(milk)
        val vm = editVm(recipeId = null)
        val events = record(vm.events)

        val initial = vm.loaded()
        assertTrue(initial.isNew)
        assertFalse(initial.hasChanges)
        assertFalse(initial.canDelete)

        vm.setName("Porridge")
        vm.addPickedIngredient(oatsId, 60.0)
        vm.state.first { it.draft.ingredients.size == 1 }
        vm.addPickedIngredient(milkId, null) // no amount delivered → default (no portions → 100 ml)
        val withIngredients = vm.state.first { it.draft.ingredients.size == 2 }
        vm.increasePortions()

        assertEquals(370.0 * 0.6 + 47.0, withIngredients.nutrition.total.kcal, 1e-6)
        assertTrue(vm.state.value.hasChanges)
        assertEquals((370.0 * 0.6 + 47.0) / 2, vm.state.value.nutrition.perPortion.kcal, 1e-6)

        vm.save()
        val saved = events.await<RecipeEditEvent.Saved>()
        assertTrue(saved.wasNew)
        assertFalse(vm.state.value.hasChanges)

        val stored = recipes.get(saved.recipeId)
        assertNotNull(stored)
        assertEquals("Porridge", stored!!.recipe.name)
        assertEquals(2, stored.recipe.portions)
        assertEquals(listOf(oatsId, milkId), stored.ingredients.map { it.ingredient.foodId })
        assertEquals(listOf(60.0, 100.0), stored.ingredients.map { it.ingredient.grams })
    }

    @Test
    fun `saving twice after the first save updates instead of inserting again`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val vm = editVm(recipeId = null)
        val events = record(vm.events)
        vm.loaded()
        vm.setName("Brei")
        vm.addPickedIngredient(oatsId, 50.0)
        vm.state.first { it.draft.ingredients.size == 1 }

        vm.save()
        val first = events.await<RecipeEditEvent.Saved>()
        vm.setName("Haferbrei")
        vm.save()
        val second = events.first { list -> list.count { it is RecipeEditEvent.Saved } == 2 }
            .filterIsInstance<RecipeEditEvent.Saved>()[1]

        assertEquals(first.recipeId, second.recipeId)
        assertEquals(1, db.recipeDao().getAllRecipes().size)
        assertEquals("Haferbrei", recipes.get(first.recipeId)!!.recipe.name)
    }

    @Test
    fun `save without name or ingredients shows errors and stores nothing`() = runTest {
        val vm = editVm(recipeId = null)
        val events = record(vm.events)
        vm.loaded()

        vm.save()
        val message = events.await<RecipeEditEvent.Message>()
        assertEquals("Bitte gib dem Rezept einen Namen.", message.text)
        assertTrue(vm.state.value.showNameError)
        assertTrue(vm.state.value.showIngredientsError)
        assertTrue(db.recipeDao().getAllRecipes().isEmpty())

        vm.setName("Salat")
        assertFalse(vm.state.value.showNameError)
        assertTrue(vm.state.value.showIngredientsError)
    }

    @Test
    fun `editing an existing recipe keeps favorite and creation date`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val milkId = db.foodDao().insert(milk)
        val id = storeRecipe("Müsli", 2, oatsId to 80.0, milkId to 200.0, favorite = true)
        val vm = editVm(recipeId = id)
        val events = record(vm.events)

        val loaded = vm.loaded()
        assertFalse(loaded.isNew)
        assertTrue(loaded.canDelete)
        assertFalse(loaded.hasChanges)
        assertEquals("Müsli", loaded.draft.name)
        assertEquals(listOf(oatsId, milkId), loaded.draft.ingredients.map { it.foodId })

        vm.setIngredientAmount(loaded.draft.ingredients[0].key, 100.0)
        vm.removeIngredient(loaded.draft.ingredients[1].key)
        val removed = events.await<RecipeEditEvent.IngredientRemoved>()
        assertEquals(1, removed.index)
        assertTrue(vm.state.value.hasChanges)

        vm.save()
        val saved = events.await<RecipeEditEvent.Saved>()
        assertFalse(saved.wasNew)
        assertEquals(id, saved.recipeId)

        val stored = recipes.get(id)!!
        assertEquals(listOf(100.0), stored.ingredients.map { it.ingredient.grams })
        assertTrue(stored.recipe.isFavorite)
        assertEquals(42L, stored.recipe.createdAt)
    }

    @Test
    fun `removed ingredient can be restored at its position`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val milkId = db.foodDao().insert(milk)
        val id = storeRecipe("Müsli", 1, oatsId to 80.0, milkId to 200.0)
        val vm = editVm(recipeId = id)
        val events = record(vm.events)
        val first = vm.loaded().draft.ingredients[0]

        vm.removeIngredient(first.key)
        val removed = events.await<RecipeEditEvent.IngredientRemoved>()
        vm.restoreIngredient(removed.index, removed.ingredient)

        assertEquals(listOf(oatsId, milkId), vm.state.value.draft.ingredients.map { it.foodId })
        assertFalse(vm.state.value.hasChanges)
    }

    @Test
    fun `invalid amounts and portions are rejected`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val id = storeRecipe("Brei", 1, oatsId to 50.0)
        val vm = editVm(recipeId = id)
        val events = record(vm.events)
        val key = vm.loaded().draft.ingredients.single().key

        vm.setIngredientAmount(key, 0.0)
        assertEquals("Die Menge muss größer als 0 sein.", events.await<RecipeEditEvent.Message>().text)
        vm.setPortions(0)
        vm.decreasePortions()
        assertEquals(50.0, vm.state.value.draft.ingredients.single().grams, 1e-9)
        assertEquals(1, vm.state.value.draft.portions)

        vm.setPortions(500)
        assertEquals(MAX_RECIPE_PORTIONS, vm.state.value.draft.portions)
    }

    @Test
    fun `pick result that arrives before loading finished is not lost`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val milkId = db.foodDao().insert(milk)
        val id = storeRecipe("Müsli", 1, oatsId to 80.0)
        val vm = editVm(recipeId = id)

        vm.addPickedIngredient(milkId, 150.0) // the load coroutine has not run yet
        val state = vm.state.first { it.draft.ingredients.size == 2 }

        assertEquals(listOf(oatsId, milkId), state.draft.ingredients.map { it.foodId })
        assertEquals(150.0, state.draft.ingredients[1].grams, 1e-9)
        assertTrue(state.hasChanges)
    }

    @Test
    fun `unknown picked food shows a message`() = runTest {
        val vm = editVm(recipeId = null)
        val events = record(vm.events)
        vm.loaded()

        vm.addPickedIngredient(9_999L, 100.0)
        assertEquals("Das Lebensmittel wurde nicht gefunden.", events.await<RecipeEditEvent.Message>().text)
        assertTrue(vm.state.value.draft.ingredients.isEmpty())
    }

    @Test
    fun `draft survives process death via the saved state handle`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val handle = SavedStateHandle()
        val vm1 = editVm(recipeId = null, handle = handle)
        vm1.loaded()
        vm1.setName("Overnight Oats")
        vm1.setNote("Über Nacht quellen lassen")
        vm1.increasePortions()
        vm1.addPickedIngredient(oatsId, 45.0)
        vm1.state.first { it.draft.ingredients.size == 1 }

        // A new process only gets the saved values back.
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val vm2 = editVm(recipeId = null, handle = restoredHandle)
        val restored = vm2.loaded()

        assertEquals("Overnight Oats", restored.draft.name)
        assertEquals("Über Nacht quellen lassen", restored.draft.note)
        assertEquals(2, restored.draft.portions)
        assertEquals(listOf(oatsId), restored.draft.ingredients.map { it.foodId })
        assertEquals("Haferflocken", restored.draft.ingredients.single().food?.name)
        assertEquals(45.0, restored.draft.ingredients.single().grams, 1e-9)
        assertTrue(restored.hasChanges)
    }

    @Test
    fun `deleting a recipe removes it`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val id = storeRecipe("Brei", 1, oatsId to 50.0)
        val vm = editVm(recipeId = id)
        val events = record(vm.events)
        assertTrue(vm.loaded().canDelete)

        vm.delete()
        events.await<RecipeEditEvent.Deleted>()
        assertNull(recipes.get(id))
    }

    @Test
    fun `missing recipe shows not found`() = runTest {
        val state = editVm(recipeId = 12_345L).loaded()
        assertTrue(state.notFound)
        assertFalse(state.isEditable)
        assertFalse(state.canDelete)
    }

    // ---------------------------------------------------------------- detail

    @Test
    fun `detail logs the selected portions and undo removes the entry`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val milkId = db.foodDao().insert(milk)
        val id = storeRecipe("Porridge", 2, oatsId to 100.0, milkId to 200.0)
        val today = LocalDate.now()
        val vm = create { RecipeDetailViewModel(recipes, diary, id, today, MealType.BREAKFAST) }
        val events = record(vm.events)
        backgroundScope.launch { vm.state.collect { } }

        val content = vm.state.first { it is RecipeDetailUiState.Content } as RecipeDetailUiState.Content
        assertEquals((370.0 + 94.0) / 2, content.nutrition.perPortion.kcal, 1e-6)
        assertEquals(1.0, content.selection.portions, 1e-9)

        vm.increasePortions()
        vm.setMeal(MealType.DINNER)
        val updated = vm.state.first {
            it is RecipeDetailUiState.Content && it.selection.portions == 1.5 && it.selection.meal == MealType.DINNER
        } as RecipeDetailUiState.Content
        assertEquals((370.0 + 94.0) / 2 * 1.5, updated.logNutrition.kcal, 1e-6)

        vm.addToDiary()
        val added = events.await<RecipeDetailEvent.Added>()
        assertEquals("1,5 Portionen zu Abendessen (Heute) hinzugefügt", added.message)

        val entry = diary.getEntry(added.entryId)!!
        assertEquals(today, entry.date)
        assertEquals(MealType.DINNER, entry.meal)
        assertEquals("Porridge", entry.name)
        assertEquals("Portion", entry.unitLabel)
        assertEquals(1.5, entry.amount, 1e-9)
        assertEquals(225.0, entry.grams, 1e-6)
        assertEquals((370.0 + 94.0) * 0.75, entry.kcal, 1e-6)
        assertEquals(id, entry.recipeId)
        assertEquals(EntrySource.RECIPE, entry.source)

        vm.undoAdd(added.entryId)
        diary.observeDay(today).first { it.isEmpty() }
    }

    @Test
    fun `detail portions stepper and typed values`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val id = storeRecipe("Brei", 1, oatsId to 50.0)
        val vm = create { RecipeDetailViewModel(recipes, diary, id, null, null) }
        val events = record(vm.events)
        backgroundScope.launch { vm.state.collect { } }
        vm.state.first { it is RecipeDetailUiState.Content }

        vm.decreasePortions()
        vm.decreasePortions()
        assertEquals(0.5, (vm.state.first { (it as? RecipeDetailUiState.Content)?.selection?.portions == 0.5 } as RecipeDetailUiState.Content).selection.portions, 1e-9)
        vm.setPortions(0.75)
        vm.increasePortions()
        vm.state.first { (it as? RecipeDetailUiState.Content)?.selection?.portions == 1.0 }

        vm.setPortions(0.0)
        assertEquals("Die Anzahl der Portionen muss größer als 0 sein.", events.await<RecipeDetailEvent.Message>().text)
        assertEquals(LocalDate.now(), (vm.state.value as RecipeDetailUiState.Content).selection.date)
    }

    @Test
    fun `detail favorite toggle and missing recipe`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        val id = storeRecipe("Brei", 1, oatsId to 50.0)
        val vm = create { RecipeDetailViewModel(recipes, diary, id, null, null) }
        backgroundScope.launch { vm.state.collect { } }
        vm.state.first { it is RecipeDetailUiState.Content }

        vm.toggleFavorite()
        vm.state.first { (it as? RecipeDetailUiState.Content)?.recipe?.recipe?.isFavorite == true }

        val missing = create { RecipeDetailViewModel(recipes, diary, 777L, null, null) }
        assertEquals(RecipeDetailUiState.NotFound, missing.state.first { it != RecipeDetailUiState.Loading })
        val invalid = create { RecipeDetailViewModel(recipes, diary, -1L, null, null) }
        assertEquals(RecipeDetailUiState.NotFound, invalid.state.first { it != RecipeDetailUiState.Loading })
    }

    @Test
    fun `detail without existing ingredients cannot be logged`() = runTest {
        val id = storeRecipe("Leer", 1, 4_242L to 100.0) // food does not exist
        val vm = create { RecipeDetailViewModel(recipes, diary, id, null, null) }
        backgroundScope.launch { vm.state.collect { } }
        val content = vm.state.first { it is RecipeDetailUiState.Content } as RecipeDetailUiState.Content
        assertFalse(content.canLog)

        vm.addToDiary()
        assertTrue(diary.observeDay(content.selection.date).first().isEmpty())
    }

    // ---------------------------------------------------------------- list

    @Test
    fun `list shows recipes sorted with favorites first`() = runTest {
        val oatsId = db.foodDao().insert(oats)
        storeRecipe("Zucchini-Pfanne", 2, oatsId to 100.0)
        val bananaId = storeRecipe("Bananenbrot", 4, oatsId to 400.0)
        storeRecipe("Äpfelkompott", 1, oatsId to 10.0)
        val vm = create { RecipeListViewModel(recipes) }
        backgroundScope.launch { vm.state.collect { } }

        val loaded = vm.state.first { it is RecipeListUiState.Loaded } as RecipeListUiState.Loaded
        assertEquals(listOf("Äpfelkompott", "Bananenbrot", "Zucchini-Pfanne"), loaded.items.map { it.name })
        val banana = loaded.items.first { it.id == bananaId }
        assertEquals(370.0, banana.kcalPerPortion, 1e-6)
        assertEquals(4, banana.portions)

        vm.toggleFavorite(banana)
        val updated = vm.state.first {
            it is RecipeListUiState.Loaded && it.items.firstOrNull()?.id == bananaId
        } as RecipeListUiState.Loaded
        assertTrue(updated.items.first().isFavorite)
    }
}
