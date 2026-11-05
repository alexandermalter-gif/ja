package de.kalorienkompass.ui.recipes

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.remote.FoodRemoteDataSource
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.data.repo.RecipeRepository
import de.kalorienkompass.ui.navigation.NavResults
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.KalorienTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * UI flows of the recipe screens with a real NavHost: list → editor → (stand-in) food picker
 * via the pick-result protocol (spec §9) → save → detail → diary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RecipeScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AppDatabase
    private lateinit var deps: RecipeDependencies
    private lateinit var navController: NavHostController

    private object NoRemote : FoodRemoteDataSource {
        override suspend fun findByBarcode(barcode: String): FoodEntity? = null
        override suspend fun search(query: String, page: Int, pageSize: Int): List<FoodEntity> = emptyList()
    }

    private val oats = FoodEntity(
        name = "Haferflocken", kcal = 370.0, protein = 13.5, carbs = 58.7, fat = 7.0, source = FoodSource.BUILTIN,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        deps = RecipeDependencies(RecipeRepository(db), FoodRepository(db, NoRemote), DiaryRepository(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun setNavContent(pickedFoodId: () -> Long = { -1L }, host: @Composable (NavHostController) -> Unit) {
        compose.setContent {
            KalorienTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalRecipeDependencies provides deps) {
                    val nav = rememberNavController()
                    navController = nav
                    NavHost(navController = nav, startDestination = "host") {
                        composable("host") { host(nav) }
                        composable(
                            route = Routes.RECIPE_EDIT,
                            arguments = listOf(navArgument("recipeId") { type = NavType.LongType; defaultValue = -1L }),
                        ) { entry ->
                            RecipeEditScreen(nav, entry.arguments?.getLong("recipeId", -1L)?.takeIf { it > 0L })
                        }
                        composable(
                            route = Routes.RECIPE_DETAIL,
                            arguments = listOf(
                                navArgument("recipeId") { type = NavType.LongType },
                                navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) { entry ->
                            val args = entry.arguments
                            RecipeDetailScreen(
                                navController = nav,
                                recipeId = args?.getLong("recipeId", -1L) ?: -1L,
                                date = args?.getLong("date", -1L)?.takeIf { it != -1L }?.let(LocalDate::ofEpochDay),
                                meal = MealType.fromName(args?.getString("meal")),
                            )
                        }
                        composable(
                            route = Routes.FOOD_SEARCH,
                            arguments = listOf(
                                navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                                navArgument("pick") { type = NavType.BoolType; defaultValue = false },
                            ),
                        ) {
                            // Stand-in for the FOOD picker: its side of the pick-result protocol.
                            Button(
                                onClick = {
                                    val handle = nav.getBackStackEntry(Routes.RECIPE_EDIT).savedStateHandle
                                    handle[NavResults.PICKED_FOOD_ID] = pickedFoodId()
                                    handle[NavResults.PICKED_GRAMS] = 60.0
                                    nav.popBackStack(Routes.RECIPE_EDIT, inclusive = false)
                                },
                            ) { Text("Testauswahl") }
                        }
                    }
                }
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDescription(description: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun currentRoute(): String? {
        var route: String? = null
        compose.runOnIdle { route = navController.currentDestination?.route }
        return route
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun createRecipeWithPickedIngredientThenLogIt() {
        val oatsId = runBlocking { db.foodDao().insert(oats) }
        setNavContent(pickedFoodId = { oatsId }) { nav ->
            RecipeListContent(nav, date = null, meal = null, modifier = Modifier.fillMaxSize())
        }

        waitForText("Noch keine Rezepte")
        compose.onNodeWithText("Neues Rezept").performClick()
        waitForText("Name des Rezepts")
        compose.onNodeWithText("Name des Rezepts").performTextInput("Porridge")

        compose.onNodeWithText("Zutat hinzufügen").performScrollTo().performClick()
        waitForText("Testauswahl")
        compose.onNodeWithText("Testauswahl").performClick()

        // Back in the editor: the draft is still there and the picked food was added.
        waitForText("Haferflocken")
        compose.onNodeWithText("Porridge").assertExists()
        compose.onNodeWithText("60 g").assertExists()
        assertEquals(Routes.RECIPE_EDIT, currentRoute())

        compose.onNodeWithText("Rezept speichern").performScrollTo().performClick()

        // A new recipe opens its detail page (replacing the editor).
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        println("DEBUGDB-after-touch " + runBlocking { deps.recipes.observeAll().first() }.map { it.recipe.name })
        val bounds = compose.onNodeWithText("Rezept speichern").fetchSemanticsNode().boundsInRoot
        println("DEBUGBOUNDS " + bounds + " root=" + compose.onRoot().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Rezept speichern").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        println("DEBUGDB-after-action " + runBlocking { deps.recipes.observeAll().first() }.map { it.recipe.name } + " route=" + currentRoute())
        try { waitForText("pro Portion") } catch (e: Throwable) {
            println("DEBUGDB2 " + runBlocking { deps.recipes.observeAll().first() }.map { it.recipe.name + "/" + it.ingredients.size })
            println("DEBUGTREE route=" + currentRoute())
            println(compose.onRoot(useUnmergedTree = false).printToString(maxDepth = 100))
            throw e
        }
        assertEquals(Routes.RECIPE_DETAIL, currentRoute())
        val stored = runBlocking { deps.recipes.observeAll().first() }
        assertEquals(listOf("Porridge"), stored.map { it.recipe.name })
        assertEquals(listOf(60.0), stored.single().ingredients.map { it.ingredient.grams })

        compose.onNode(hasText("Zum Tagebuch hinzufügen") and hasClickAction()).performScrollTo().performClick()
        waitForText("hinzugefügt", substring = true)
        val entries = runBlocking { deps.diary.observeDay(LocalDate.now()).first() }
        assertEquals(listOf("Porridge"), entries.map { it.name })
        assertEquals(222.0, entries.single().kcal, 1e-6)

        // Back to the list, which now shows the recipe.
        pressBack()
        waitForText("Porridge")
        assertEquals("host", currentRoute())
    }

    @Test
    fun unsavedChangesAskBeforeLeaving() {
        setNavContent { nav -> RecipeListContent(nav, date = null, meal = null, modifier = Modifier.fillMaxSize()) }

        waitForText("Neues Rezept")
        compose.onNodeWithText("Neues Rezept").performClick()
        waitForText("Name des Rezepts")
        compose.onNodeWithText("Name des Rezepts").performTextInput("Linsen-Dal")

        pressBack()
        waitForText("Änderungen verwerfen?")
        compose.onNodeWithText("Abbrechen").performClick()
        assertEquals(Routes.RECIPE_EDIT, currentRoute())

        compose.onNodeWithContentDescription("Zurück").performClick()
        waitForText("Änderungen verwerfen?")
        compose.onNodeWithText("Verwerfen").performClick()
        waitForText("Noch keine Rezepte")
        assertEquals("host", currentRoute())
    }

    @Test
    fun deletingFromTheEditorAlsoClosesTheDetailPage() {
        runBlocking {
            val oatsId = db.foodDao().insert(oats)
            deps.recipes.save(
                RecipeEntity(name = "Haferbrei", portions = 2),
                listOf(RecipeIngredientEntity(recipeId = 0, foodId = oatsId, grams = 100.0)),
            )
        }
        setNavContent { nav -> RecipeListContent(nav, date = null, meal = null, modifier = Modifier.fillMaxSize()) }

        waitForText("Haferbrei")
        compose.onNodeWithText("185 kcal").assertExists() // 370 kcal / 2 portions
        compose.onNodeWithText("Haferbrei").performClick()
        waitForText("Zum Tagebuch hinzufügen")
        compose.onNodeWithContentDescription("Rezept bearbeiten").performClick()
        waitForDescription("Rezept löschen")
        compose.onNodeWithContentDescription("Rezept löschen").performClick()
        waitForText("Rezept löschen?")
        compose.onNodeWithText("Löschen").performClick()

        waitForText("Noch keine Rezepte")
        assertEquals("host", currentRoute())
    }

    @Test
    fun listWorksInsideAnOuterScrollContainer() {
        runBlocking {
            val oatsId = db.foodDao().insert(oats)
            deps.recipes.save(RecipeEntity(name = "Bircher Müsli"), listOf(RecipeIngredientEntity(recipeId = 0, foodId = oatsId, grams = 50.0)))
            deps.recipes.save(RecipeEntity(name = "Apfel-Porridge", isFavorite = true), listOf(RecipeIngredientEntity(recipeId = 0, foodId = oatsId, grams = 80.0)))
        }
        setNavContent { nav ->
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Kopfbereich")
                RecipeListContent(nav, date = null, meal = null)
            }
        }

        waitForText("Bircher Müsli")
        compose.onNodeWithText("Apfel-Porridge").assertExists()
        compose.onNodeWithContentDescription("Aus Favoriten entfernen").assertExists()
    }
}
