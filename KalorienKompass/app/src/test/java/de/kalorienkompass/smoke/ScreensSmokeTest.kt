package de.kalorienkompass.smoke

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.ui.navigation.AppNavHost
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.KalorienTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * # UI smoke tests + screenshots (Robolectric)
 *
 * One test per screen entry composable of the architecture spec (§9). Every screen is hosted in
 * `KalorienTheme` + the app's real `AppNavHost` (all routes registered, start destination = the screen under
 * test; pick-mode screens get RecipeEdit underneath, as in the app) on top of realistic data that [TestData]
 * writes through the real repositories. Each test
 * - renders the screen, waits until the UI is stable (async Room / DataStore content, debounces) and asserts
 *   that something is displayed,
 * - writes a dark-theme screenshot (plus a light one for Diary, Progress and FoodDetail),
 * - performs a few defensive interactions ("Hinzufügen", "Speichern", "Erneut versuchen", "Zurück", …):
 *   missing buttons are skipped and navigation away is undone, but every exception thrown by the app fails
 *   the test – including uncaught ones on background threads or in ViewModel coroutines ([SmokeGuardRule]).
 *
 * The network is "offline" (Open Food Facts / Anthropic calls fail fast with an IOException), Health Connect
 * and the camera are unavailable (no CAMERA permission) and no Anthropic API key is stored, so the error and
 * fallback paths of these features are exercised. [AppFlowSmokeTest] starts the real `MainActivity`
 * (fresh install → onboarding; seeded data → diary + every bottom tab).
 *
 * ## Run
 * ```
 * cd KalorienKompass
 * ./gradlew :app:testDebugUnitTest --tests "de.kalorienkompass.smoke.*"
 * # a single screen:
 * ./gradlew :app:testDebugUnitTest --tests "de.kalorienkompass.smoke.ScreensSmokeTest.progress"
 * # if Maven Central rate-limits Robolectric's android-all download:
 * ./gradlew :app:testDebugUnitTest --tests "de.kalorienkompass.smoke.*" \
 *     -ProbolectricRepoUrl=https://maven-central.storage-download.googleapis.com/maven2
 * ```
 * The suite takes about 1.5–3 s per screen (first test ≈ 20 s for the Robolectric start-up).
 *
 * ## Output
 * - `app/build/smoke-screenshots/<test>-<theme>.png` – 1233 × 2673 px (411 × 891 dp, xxhdpi, German locale);
 *   extra shots after selected actions, e.g. `CustomFoodEdit-barcode-im-web-suchen-dark.png`;
 * - `app/build/smoke-screenshots/smoke-report.txt` – per test: what was visible, screenshots with pixel
 *   statistics, the clicked actions and where they led;
 * - the JUnit report `app/build/reports/tests/testDebugUnitTest/index.html` (stdout contains the same log).
 *
 * ## How it works / known environment limits
 * - Screenshots use Robolectric native graphics (`@GraphicsMode(NATIVE)`). `onRoot().captureToImage()` is
 *   tried first, but cannot work under Robolectric's PAUSED looper with compose-ui-test 1.7 (it posts a redraw
 *   to the main looper and then blocks that same thread → ComposeTimeoutException), so [Screenshots] draws
 *   every app window with `View.draw()` – which also captures dialogs, bottom sheets and menus. A screenshot
 *   without colour variance fails the test.
 * - compose-ui-test runs `rememberCoroutineScope`/`LaunchedEffect` coroutines on an unconfined test
 *   dispatcher, i.e. after `withContext(IO)`/DataStore/Room they would continue on a background thread
 *   (unlike on a device). [withEffectsResumingOnMainThread] restores device behaviour.
 * - Robolectric does not run androidx.startup, so WorkManager is not initialized while `KalorienApp.onCreate`
 *   schedules the health sync (the app catches this; noted in the report). Each test then installs a test
 *   WorkManager; [AppFlowSmokeTest] checks `HealthWorkScheduler.schedule()` against it.
 * - Robolectric creates a new application + database per test, so every test seeds its own data
 *   (≈ 0.1 s after warm-up). The DataStore singletons survive between tests – [TestData] overwrites them.
 * - A test that hangs for 3 minutes is interrupted with a thread dump (JUnit timeouts do not work with
 *   Robolectric's main thread).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SmokeDevice.SDK], qualifiers = SmokeDevice.QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensSmokeTest {

    @get:Rule(order = 0)
    val guard = SmokeGuardRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>().withEffectsResumingOnMainThread()

    private lateinit var data: TestData.Seeded
    private lateinit var navController: NavHostController
    private val themeMode = mutableStateOf(ThemeMode.DARK)

    // ---- tabs ----------------------------------------------------------------------------------

    @Test
    fun diary() {
        smoke("Diary", lightTheme = true) { Routes.DIARY }
    }

    @Test
    fun foodsHub() {
        smoke(
            "FoodsHub",
            actions = FOODS_HUB_TABS + DEFAULT_ACTIONS,
            screenshotAfter = FOODS_HUB_TABS.toSet(),
        ) { Routes.FOODS }
    }

    @Test
    fun progress() {
        smoke(
            "Progress",
            lightTheme = true,
            actions = listOf("Eintrag") + DEFAULT_ACTIONS,
            screenshotAfter = setOf("Eintrag", "Hinzufügen"),
        ) { Routes.PROGRESS }
    }

    /** Also follows the profile's links (each target is left again via the back stack). */
    @Test
    fun profile() {
        smoke("Profile", actions = listOf("Ziele", "Einstellungen", "Datenschutz", "Health Connect")) { Routes.PROFILE }
    }

    // ---- diary ---------------------------------------------------------------------------------

    @Test
    fun mealDetail() {
        smoke("MealDetail") { Routes.meal(data.today, MealType.LUNCH) }
    }

    // ---- food ----------------------------------------------------------------------------------

    @Test
    fun foodSearch_logMode() {
        smoke("FoodSearch-log", typeText = "Apfel") {
            Routes.foodSearch(data.today, MealType.BREAKFAST)
        }
    }

    /** Pick mode is only reachable from RecipeEdit (result protocol §9), so RecipeEdit is put below it. */
    @Test
    fun foodSearch_pickMode() {
        smoke(
            "FoodSearch-pick",
            via = { listOf(Routes.recipeEdit()) },
            typeText = "Skyr",
        ) { Routes.foodPick() }
    }

    @Test
    fun foodDetail_withDateAndMeal() {
        smoke("FoodDetail-meal", lightTheme = true) {
            Routes.foodDetail(data.bananaId, data.today, MealType.BREAKFAST)
        }
    }

    @Test
    fun foodDetail_withoutDateAndMeal() {
        smoke("FoodDetail-standalone") {
            Routes.foodDetail(data.customFoodId, null, null)
        }
    }

    /** Cached Open Food Facts product with an image URL (offline → image error placeholder). */
    @Test
    fun foodDetail_openFoodFactsProduct() {
        smoke("FoodDetail-off") { Routes.foodDetail(data.offFoodId, null, null) }
    }

    @Test
    fun foodDetail_pickMode() {
        val result = smoke(
            "FoodDetail-pick",
            via = { listOf(Routes.recipeEdit(), Routes.foodPick()) },
            actions = listOf("Zum Rezept", "Hinzufügen", "Zurück"),
        ) { Routes.foodDetail(data.skyrId, null, null, pick = true) }
        if (result.clicked.any { it.contains("Rezept", ignoreCase = true) } && !result.screenStillShown) {
            // Spec §9: the picker sets the result on the RECIPE_EDIT entry and pops back to it.
            assertEquals(
                "After 'Zum Rezept hinzufügen' the app must pop back to RecipeEdit (spec §9 pick protocol)",
                Routes.RECIPE_EDIT,
                navController.currentDestination?.route,
            )
            composeRule.settle()
            screenshot("FoodDetail-pick-result-dark")
        }
    }

    @Test
    fun customFoodEdit_new() {
        smoke("CustomFoodEdit-new", actions = listOf("Speichern", "Zurück")) {
            Routes.customFood()
        }
    }

    @Test
    fun customFoodEdit_existing() {
        smoke("CustomFoodEdit-edit") { Routes.customFood(foodId = data.customFoodId) }
    }

    /** Target of the "unknown product" flow (spec §13): prefilled barcode + AI lookup buttons (no API key). */
    @Test
    fun customFoodEdit_withUnknownBarcode() {
        smoke(
            "CustomFoodEdit-barcode",
            actions = listOf("Im Web suchen", "Nährwerttabelle", "Speichern", "Zurück"),
            screenshotAfter = setOf("Im Web suchen", "Nährwerttabelle", "Speichern"),
        ) { Routes.customFood(barcode = TestData.UNKNOWN_BARCODE, date = data.today, meal = MealType.SNACK) }
    }

    // ---- scan / AI -----------------------------------------------------------------------------

    @Test
    fun barcodeScan() {
        smoke(
            "BarcodeScan",
            actions = listOf("Erneut", "Zurück"),
            beforeActions = { name ->
                // Manual entry of an unknown barcode → local miss → Open Food Facts (offline) → error / not-found UI.
                val opened = composeRule.clickFirst("manuell") ?: composeRule.clickFirst("eingeben")
                if (opened != null) {
                    log("clicked '$opened'")
                    composeRule.settle()
                    if (composeRule.typeIntoFirstTextField(TestData.UNKNOWN_BARCODE)) {
                        log("typed ${TestData.UNKNOWN_BARCODE}")
                        val confirm = CONFIRM_LABELS.firstNotNullOfOrNull { composeRule.clickFirst(it) }
                        log(if (confirm != null) "clicked '$confirm'" else "no confirm button found")
                        composeRule.settle(maxMillis = 8_000)
                        screenshot("$name-unknown-dark")
                        composeRule.dismissDialogs(::log)
                    }
                }
            },
        ) { Routes.barcode(data.today, MealType.SNACK) }
    }

    @Test
    fun aiPhoto() {
        smoke("AiPhoto", actions = listOf("Einstellungen") + DEFAULT_ACTIONS) {
            Routes.aiPhoto(data.today, MealType.DINNER)
        }
    }

    // ---- recipes -------------------------------------------------------------------------------

    @Test
    fun recipeEdit_new() {
        smoke("RecipeEdit-new") { Routes.recipeEdit() }
    }

    @Test
    fun recipeEdit_existing() {
        smoke("RecipeEdit-existing") { Routes.recipeEdit(data.recipeId) }
    }

    @Test
    fun recipeDetail() {
        smoke("RecipeDetail") { Routes.recipeDetail(data.recipeId, data.today, MealType.DINNER) }
    }

    // ---- progress ------------------------------------------------------------------------------

    @Test
    fun measurementHistory_weight() {
        smoke("MeasurementHistory-WEIGHT") {
            Routes.measurementHistory(MeasurementType.WEIGHT)
        }
    }

    @Test
    fun measurementHistory_waist() {
        smoke("MeasurementHistory-WAIST") {
            Routes.measurementHistory(MeasurementType.WAIST)
        }
    }

    // ---- profile -------------------------------------------------------------------------------

    @Test
    fun goals() {
        smoke("Goals") { Routes.GOALS }
    }

    @Test
    fun settings() {
        smoke("Settings") { Routes.SETTINGS }
    }

    @Test
    fun onboarding() {
        val result = smoke(
            "Onboarding",
            fresh = true,
            actions = List(8) { "Weiter" } + listOf("Los geht", "Fertig"),
        ) { Routes.ONBOARDING }
        if (!result.screenStillShown && navController.currentDestination?.route == Routes.DIARY) {
            // Spec §9: on finish onboardingDone = true and navigate to the diary.
            val done = runBlocking { TestData.container().settingsRepository.profile.first().onboardingDone }
            assertTrue("Onboarding navigated to the diary but did not set profile.onboardingDone", done)
            screenshot("Onboarding-finished-dark")
        }
    }

    // ---- health --------------------------------------------------------------------------------

    @Test
    fun healthConnect() {
        smoke("HealthConnect", actions = listOf("synchronisieren") + DEFAULT_ACTIONS) {
            Routes.HEALTH_CONNECT
        }
    }

    @Test
    fun privacy() {
        smoke("Privacy") { Routes.PRIVACY }
    }

    // =============================================================================================

    class Result(val clicked: List<String>, val screenStillShown: Boolean)

    private fun log(line: String) {
        println("SMOKE: $line")
        SmokeOutput.report("    $line")
    }

    private fun screenshot(name: String) {
        Screenshots.capture(composeRule, name) { composeRule.activity.window.decorView }
    }

    /**
     * Seeds data, hosts [route] (after the optional [via] routes) in the real AppNavHost, takes screenshots
     * and performs [actions] (each at most once, skipped if absent). After an action listed in
     * [screenshotAfter] an extra screenshot `<name>-<action>-dark.png` is written (dialogs included).
     */
    private fun smoke(
        name: String,
        fresh: Boolean = false,
        lightTheme: Boolean = false,
        via: () -> List<String> = { emptyList() },
        typeText: String? = null,
        actions: List<String> = DEFAULT_ACTIONS,
        screenshotAfter: Set<String> = emptySet(),
        beforeActions: (String) -> Unit = {},
        route: () -> String,
    ): Result {
        SmokeOutput.report("\n== $name")
        val started = System.currentTimeMillis()
        if (fresh) TestData.seedFreshInstall() else data = TestData.seedRealistic()
        val seededAt = System.currentTimeMillis()

        val stack = via() + route()
        composeRule.setContent {
            val nav = rememberNavController()
            navController = nav
            KalorienTheme(themeMode = themeMode.value) {
                AppNavHost(startDestination = stack.first(), navController = nav)
            }
        }
        composeRule.settle()
        for (next in stack.drop(1)) {
            composeRule.runOnUiThread { navController.navigate(next) }
            composeRule.settle()
        }
        val screenEntry = checkNotNull(navController.currentBackStackEntry) { "$name: empty back stack" }
        val screenDestinationId = screenEntry.destination.id
        log("showing ${screenEntry.destination.route} (route ${stack.joinToString(" → ")})")
        fun onScreen() = navController.currentBackStackEntry?.id == screenEntry.id

        composeRule.assertHasDisplayedContent(name)
        log("visible: ${composeRule.visibleTexts(25)}")
        screenshot("$name-dark")
        if (lightTheme) {
            themeMode.value = ThemeMode.LIGHT
            composeRule.settle()
            screenshot("$name-light")
            themeMode.value = ThemeMode.DARK
            composeRule.settle()
        }

        if (typeText != null && onScreen()) {
            if (composeRule.typeIntoFirstTextField(typeText)) {
                log("typed '$typeText'")
                composeRule.settle(maxMillis = 8_000) // debounce (local 400 ms, remote 800 ms) + offline error
                composeRule.assertHasDisplayedContent("$name after typing")
                log("visible: ${composeRule.visibleTexts(25)}")
                screenshot("$name-query-dark")
            } else {
                log("no text field for '$typeText'")
            }
        }

        if (onScreen()) beforeActions(name)

        val clicked = mutableListOf<String>()
        for (label in actions) {
            if (!onScreen()) break
            val description = composeRule.clickFirst(label) ?: continue
            clicked += description
            log("clicked '$description' (action '$label')")
            composeRule.settle()
            if (label in screenshotAfter) screenshot("$name-${slug(label)}-dark")
            composeRule.dismissDialogs(::log)
            if (!onScreen()) {
                val inBackStack = navController.currentBackStack.value.any { it.id == screenEntry.id }
                val now = navController.currentDestination?.route
                if (inBackStack) {
                    log("navigated to $now – going back")
                    composeRule.runOnUiThread { navController.popBackStack(screenDestinationId, false) }
                    composeRule.settle()
                } else {
                    log("screen closed itself (now at ${now ?: "empty back stack"})")
                }
            }
        }
        // Whatever the interactions did, the app must still be alive and drawable.
        composeRule.settle()
        composeRule.waitForIdle()

        SmokeOutput.report(
            "  OK in ${System.currentTimeMillis() - started} ms (seeding ${seededAt - started} ms); actions: " +
                if (clicked.isEmpty()) "none of $actions present" else clicked.joinToString(" → "),
        )
        return Result(clicked, onScreen())
    }

    private fun slug(label: String): String =
        label.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        val DEFAULT_ACTIONS = listOf("Hinzufügen", "Speichern", "Erneut versuchen", "Zurück")
        val FOODS_HUB_TABS = listOf("Eigene", "Rezepte", "Zuletzt")
        val CONFIRM_LABELS = listOf("Suchen", "Nachschlagen", "Übernehmen", "OK", "Weiter")
    }
}
