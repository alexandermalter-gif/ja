package de.kalorienkompass.smoke

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import de.kalorienkompass.KalorienApp
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Test data for the Robolectric smoke tests, written through the REAL [AppContainer] repositories of
 * the Robolectric application (`ApplicationProvider.getApplicationContext<KalorienApp>().container`).
 *
 * Robolectric creates a new [KalorienApp] (and therefore a new, empty Room database in a fresh temp
 * directory) for every test, but the DataStore singletons behind `SettingsRepository` survive from test
 * to test inside one Robolectric sandbox. Both modes therefore ALWAYS overwrite profile, goals, settings
 * and the API key explicitly instead of relying on an empty store.
 *
 * Built-in foods are looked up by name with fallbacks so that changes to `foods_de.json` do not break
 * the seeding. All random values use a fixed seed, so screenshots are reproducible (apart from "today").
 */
object TestData {

    /** Barcode of the seeded custom food (valid EAN-13). */
    val CUSTOM_BARCODE: String = ean13("401234567890")

    /** Barcode of the cached Open Food Facts product (valid EAN-13). */
    val OFF_BARCODE: String = ean13("406123456789")

    /** A valid EAN-13 that is NOT in the database (unknown product flow). */
    val UNKNOWN_BARCODE: String = ean13("409999999999")

    val PROFILE = UserProfile(
        onboardingDone = true,
        name = "Max",
        sex = Sex.MALE,
        birthYear = 1988,
        heightCm = 182.0,
        startWeightKg = 104.0,
        targetWeightKg = 83.0,
        activityLevel = ActivityLevel.LIGHT,
        goalType = GoalType.LOSE,
        weeklyRateKg = 0.5,
    )

    val GOALS = NutritionGoalSettings(
        customCalorieGoal = null,
        carbsPercent = 45,
        proteinPercent = 25,
        fatPercent = 30,
        breakfastPercent = 30,
        lunchPercent = 35,
        dinnerPercent = 25,
        snackPercent = 10,
    )

    /** Ids and values of the seeded data that tests use to build routes. */
    data class Seeded(
        val today: LocalDate,
        /** built-in food with portions (Banane) */
        val bananaId: Long,
        /** built-in food used as recipe ingredient (Skyr / Joghurt) */
        val skyrId: Long,
        /** custom food WITH barcode [CUSTOM_BARCODE] */
        val customFoodId: Long,
        /** custom food without barcode */
        val cakeId: Long,
        /** cached Open Food Facts product (ml) */
        val offFoodId: Long,
        /** recipe with 3 ingredients (4 portions) */
        val recipeId: Long,
        /** second recipe (favorite) */
        val oatsRecipeId: Long,
        val favoriteFoodIds: List<Long>,
        val diaryEntryCount: Int,
        val weightCount: Int,
    )

    fun app(): KalorienApp = ApplicationProvider.getApplicationContext()

    fun container(): AppContainer = app().container

    /** First launch: no user data, onboarding not done (built-in foods are seeded by the app itself). */
    fun seedFreshInstall() {
        runBlocking {
            val c = container()
            awaitBuiltinFoods(c)
            wipeUserDataIfAny(c)
            c.settingsRepository.replaceAll(UserProfile(), NutritionGoalSettings(), AppSettings())
            c.settingsRepository.setAiApiKey(null)
        }
    }

    /**
     * A realistic user: male, born 1988, 182 cm, 104 kg → 83 kg (LOSE, 0.5 kg/week), 90 days of weights,
     * body fat + waist measurements, 22 days of diary (all four meals), custom foods, favorites,
     * recipes and 30 days of steps (Health Connect, one manual day).
     */
    fun seedRealistic(today: LocalDate = LocalDate.now()): Seeded = runBlocking {
        val c = container()
        awaitBuiltinFoods(c)
        wipeUserDataIfAny(c)

        val now = System.currentTimeMillis()
        c.settingsRepository.replaceAll(
            PROFILE,
            GOALS,
            AppSettings(
                themeMode = ThemeMode.DARK,
                stepGoal = 10_000,
                addBurnedCalories = true,
                // Steps "come" from Health Connect; under Robolectric Health Connect is unavailable, so the
                // screens' sync attempts exercise the "not available" paths.
                healthConnectEnabled = true,
                writeWeightToHealthConnect = false,
                stepSensorEnabled = false,
                aiModel = AiModel.OPUS,
                lastHealthSyncAt = now - 2 * 60 * 60 * 1000L,
            ),
        )
        c.settingsRepository.setAiApiKey(null)

        val rnd = Random(20261105)
        val builtin = c.database.foodDao().getBySource(FoodSource.BUILTIN)
        check(builtin.isNotEmpty()) { "No built-in foods after BuiltinFoodSeeder.seedIfNeeded()" }
        val foods = BuiltinFoods(builtin)

        lateinit var seeded: Seeded
        // One Room transaction for everything: the repositories' own transactions nest into it, which keeps
        // seeding fast (one commit) under Robolectric's SQLite.
        c.database.withTransaction {
            // ---- foods -------------------------------------------------------------------------
            val customFoodId = c.foodRepository.saveCustomFood(
                FoodEntity(
                    name = "Proteinpudding Schoko",
                    brand = "Eigenmarke",
                    barcode = CUSTOM_BARCODE,
                    kcal = 72.0, protein = 10.0, carbs = 5.6, fat = 1.5,
                    sugar = 4.9, fiber = 0.6, salt = 0.18,
                    unit = "g",
                    portions = listOf(FoodPortion("1 Becher", 200.0)),
                    source = FoodSource.CUSTOM,
                ),
            )
            val cakeId = c.foodRepository.saveCustomFood(
                FoodEntity(
                    name = "Omas Apfelkuchen",
                    kcal = 245.0, protein = 3.4, carbs = 33.0, fat = 10.5,
                    sugar = 19.0, fiber = 1.6, salt = 0.25,
                    portions = listOf(FoodPortion("1 Stück", 110.0)),
                    source = FoodSource.CUSTOM,
                ),
            )
            val offFoodId = c.foodRepository.cacheRemoteFood(
                FoodEntity(
                    name = "Haferdrink Barista",
                    brand = "Nordmühle",
                    barcode = OFF_BARCODE,
                    kcal = 59.0, protein = 1.0, carbs = 6.6, fat = 3.0,
                    sugar = 4.0, fiber = 0.8, salt = 0.1,
                    unit = "ml",
                    portions = listOf(FoodPortion("Portion (200 ml)", 200.0), FoodPortion("Packung", 1000.0)),
                    source = FoodSource.OPEN_FOOD_FACTS,
                    // Like real OFF products; offline in the tests, so Coil's error path is exercised.
                    imageUrl = "https://images.openfoodfacts.org/images/products/406/123/456/789/front_de.4.200.jpg",
                ),
            )
            val aiCurryId = c.foodRepository.saveCustomFood(
                FoodEntity(
                    name = "Gemüsecurry mit Reis",
                    kcal = 128.0, protein = 3.1, carbs = 19.5, fat = 3.9,
                    source = FoodSource.AI,
                ),
            )
            val custom = c.foodRepository.getFood(customFoodId)!!
            val cake = c.foodRepository.getFood(cakeId)!!
            val off = c.foodRepository.getFood(offFoodId)!!
            val aiCurry = c.foodRepository.getFood(aiCurryId)!!

            val banana = foods.pick("Banane")
            val skyr = foods.pick("Skyr natur", "Magerquark", "Joghurt natur 1,5 %")
            val oats = foods.pick("Haferflocken", "Haferflocken (Vollkorn)", "Müsli (ohne Zucker)")
            val blueberries = foods.pick("Heidelbeeren", "Himbeeren", "Erdbeeren")

            // ---- favorites ---------------------------------------------------------------------
            val favorites = listOf(banana.id, skyr.id, oats.id, customFoodId).distinct()
            favorites.forEach { c.foodRepository.setFavorite(it, true) }

            // ---- recipes -----------------------------------------------------------------------
            val pasta = foods.pick("Nudeln (Spaghetti, Penne), roh", "Vollkornnudeln, roh", "Reis, roh")
            val mince = foods.pick("Rinderhackfleisch, roh", "Hackfleisch gemischt, roh", "Putenbrustfilet, roh")
            val sauce = foods.pick("Tomatensauce (Fertigsauce)", "Tomate")
            val recipeId = c.recipeRepository.save(
                RecipeEntity(
                    name = "Spaghetti Bolognese",
                    portions = 4,
                    note = "Sauce mindestens 20 Minuten köcheln lassen.",
                ),
                listOf(
                    RecipeIngredientEntity(recipeId = 0, foodId = pasta.id, grams = 400.0),
                    RecipeIngredientEntity(recipeId = 0, foodId = mince.id, grams = 400.0),
                    RecipeIngredientEntity(recipeId = 0, foodId = sauce.id, grams = 500.0),
                ),
            )
            val oatsRecipeId = c.recipeRepository.save(
                RecipeEntity(name = "Overnight Oats mit Beeren", portions = 2, note = "Am Vorabend ansetzen."),
                listOf(
                    RecipeIngredientEntity(recipeId = 0, foodId = oats.id, grams = 100.0),
                    RecipeIngredientEntity(recipeId = 0, foodId = skyr.id, grams = 300.0),
                    RecipeIngredientEntity(recipeId = 0, foodId = blueberries.id, grams = 150.0),
                ),
            )
            c.recipeRepository.setFavorite(oatsRecipeId, true)
            val bolognese = c.recipeRepository.get(recipeId)!!
            val overnightOats = c.recipeRepository.get(oatsRecipeId)!!

            // ---- diary: today + 21 days back, all four meals -------------------------------------
            val breakfasts: List<List<Pair<FoodEntity, Double>>> = listOf(
                listOf(oats to 1.0, foods.pick("Vollmilch 3,5 %", "Fettarme Milch 1,5 %") to 1.0, banana to 1.0),
                listOf(
                    foods.pick("Vollkornbrot", "Mischbrot (Roggenmischbrot)") to 2.0,
                    foods.pick("Butter", "Margarine (80 % Fett)") to 1.0,
                    foods.pick("Gouda", "Emmentaler") to 2.0,
                    foods.pick("Milchkaffee (Vollmilch)", "Kaffee, schwarz") to 1.0,
                ),
                listOf(skyr to 1.0, blueberries to 1.0, foods.pick("Cappuccino (Vollmilch)", "Kaffee, schwarz") to 1.0),
            )
            val lunches: List<List<Pair<FoodEntity, Double>>> = listOf(
                listOf(
                    foods.pick("Hähnchenbrust, gebraten", "Hähnchenbrustfilet, roh") to 1.0,
                    foods.pick("Reis, gekocht", "Vollkornreis, gekocht") to 1.0,
                    foods.pick("Brokkoli, gekocht", "Brokkoli, roh") to 1.0,
                ),
                listOf(foods.pick("Döner Kebab (im Brot)", "Pizza Margherita") to 1.0, off to 1.0),
                listOf(
                    foods.pick("Linsen, gekocht", "Kichererbsen (Dose, abgetropft)") to 1.0,
                    foods.pick("Vollkornbrot", "Weizenmischbrot") to 1.0,
                ),
            )
            val dinners: List<List<Pair<FoodEntity, Double>>> = listOf(
                listOf(
                    foods.pick("Lachs, gegart", "Lachs, roh") to 1.0,
                    foods.pick("Kartoffeln, gekocht (Salzkartoffeln)", "Kartoffeln, roh") to 2.0,
                    foods.pick("Brokkoli, gekocht", "Zucchini") to 1.0,
                ),
                listOf(
                    foods.pick("Vollkornbrot", "Mischbrot (Roggenmischbrot)") to 2.0,
                    foods.pick("Frischkäse (Halbfettstufe)", "Frischkäse (Doppelrahmstufe)") to 2.0,
                    foods.pick("Tomate") to 1.0,
                    foods.pick("Salatgurke") to 1.0,
                ),
                listOf(foods.pick("Pizza Margherita", "Flammkuchen") to 0.5, foods.pick("Cola", "Apfelschorle") to 1.0),
            )
            val snacks: List<List<Pair<FoodEntity, Double>>> = listOf(
                listOf(foods.pick("Apfel") to 1.0),
                listOf(foods.pick("Mandeln", "Walnüsse") to 1.0, custom to 1.0),
                listOf(foods.pick("Vollmilchschokolade", "Zartbitterschokolade (70 %)") to 3.0),
                listOf(cake to 1.0),
            )

            var entries = 0
            for (daysAgo in 21 downTo 0) {
                val date = today.minusDays(daysAgo.toLong())
                val i = 21 - daysAgo
                for ((food, amount) in breakfasts[i % breakfasts.size]) entries += logFood(c, date, MealType.BREAKFAST, food, amount)
                when {
                    i % 5 == 2 -> { c.diaryRepository.addRecipe(date, MealType.LUNCH, bolognese, 1.0); entries++ }
                    else -> for ((food, amount) in lunches[i % lunches.size]) entries += logFood(c, date, MealType.LUNCH, food, amount)
                }
                when {
                    daysAgo == 1 -> { entries += logFood(c, date, MealType.DINNER, aiCurry, 0.0, gramsOverride = 450.0) }
                    else -> for ((food, amount) in dinners[i % dinners.size]) entries += logFood(c, date, MealType.DINNER, food, amount)
                }
                for ((food, amount) in snacks[i % snacks.size]) entries += logFood(c, date, MealType.SNACK, food, amount)
                if (i % 6 == 3) {
                    c.diaryRepository.addQuick(date, MealType.SNACK, "Kuchen im Büro", Nutrition(320.0, 4.0, 38.0, 16.0))
                    entries++
                }
                if (i % 7 == 4) {
                    c.diaryRepository.addRecipe(date, MealType.BREAKFAST, overnightOats, 1.0)
                    entries++
                }
            }

            // ---- weight: 90 days, trending down (≈ 0.5 kg/week) with noise --------------------------
            var weights = 0
            for (daysAgo in 89 downTo 0) {
                if (daysAgo > 0 && rnd.nextInt(100) < 18) continue // realistic gaps, but always one for today
                val date = today.minusDays(daysAgo.toLong())
                val trend = 103.6 - (89 - daysAgo) * (6.4 / 89.0)
                val value = round1(trend + (rnd.nextDouble() - 0.5) * 0.9)
                if (daysAgo % 7 == 3) {
                    c.measurementRepository.upsertFromHealthConnect(MeasurementType.WEIGHT, date, value, "hc-weight-$daysAgo")
                } else {
                    c.measurementRepository.add(MeasurementType.WEIGHT, date, value)
                }
                weights++
            }

            // ---- body fat (weekly) and waist (every two weeks) ------------------------------------
            for (week in 12 downTo 0) {
                val date = today.minusDays(week * 7L + 1)
                val value = round1(31.8 - (12 - week) * 0.22 + (rnd.nextDouble() - 0.5) * 0.4)
                if (week % 3 == 0) {
                    c.measurementRepository.upsertFromHealthConnect(MeasurementType.BODY_FAT, date, value, "hc-bodyfat-$week")
                } else {
                    c.measurementRepository.add(MeasurementType.BODY_FAT, date, value)
                }
            }
            for (step in 6 downTo 0) {
                val date = today.minusDays(step * 14L + 2)
                c.measurementRepository.add(MeasurementType.WAIST, date, round1(113.0 - (6 - step) * 1.1 + (rnd.nextDouble() - 0.5) * 0.6))
            }
            c.measurementRepository.add(MeasurementType.HIP, today.minusDays(30), 108.5)
            c.measurementRepository.add(MeasurementType.HIP, today.minusDays(2), 106.0)

            // ---- steps: 30 days from Health Connect, one manual day --------------------------------
            for (daysAgo in 29 downTo 0) {
                val date = today.minusDays(daysAgo.toLong())
                if (daysAgo == 9) {
                    c.activityRepository.setManualSteps(date, 12_850)
                } else {
                    val steps = if (daysAgo == 0) 6_234L else 4_000L + rnd.nextInt(10_500)
                    c.activityRepository.upsertFromHealthConnect(date, steps, activeKcal = round1(steps * 0.042))
                }
            }

            seeded = Seeded(
                today = today,
                bananaId = banana.id,
                skyrId = skyr.id,
                customFoodId = customFoodId,
                cakeId = cakeId,
                offFoodId = offFoodId,
                recipeId = recipeId,
                oatsRecipeId = oatsRecipeId,
                favoriteFoodIds = favorites,
                diaryEntryCount = entries,
                weightCount = weights,
            )
        }

        // Sanity checks: the data really is in the database the screens read from.
        check(c.database.diaryDao().getAll().size == seeded.diaryEntryCount) { "Diary seeding incomplete" }
        check(c.recipeRepository.get(seeded.recipeId)?.ingredients?.size == 3) { "Recipe seeding incomplete" }
        seeded
    }

    // ---------------------------------------------------------------------------------------------

    /** Waits for the seeding launched by `KalorienApp.onCreate` (same seeder + mutex) or runs it. */
    private suspend fun awaitBuiltinFoods(c: AppContainer) {
        c.builtinFoodSeeder.seedIfNeeded()
        val count = c.database.foodDao().countBySource(FoodSource.BUILTIN)
        check(count > 0) { "BuiltinFoodSeeder did not insert any built-in food (assets/foods_de.json missing?)" }
    }

    /**
     * Robolectric gives every test a new database file, so this is normally a no-op. It protects against
     * leftovers should the database ever become a process-wide singleton.
     */
    private suspend fun wipeUserDataIfAny(c: AppContainer) {
        val db = c.database
        val dirty = db.diaryDao().getAll().isNotEmpty() ||
            db.measurementDao().getAll().isNotEmpty() ||
            db.activityDao().getAll().isNotEmpty() ||
            db.recipeDao().getAllRecipes().isNotEmpty() ||
            db.foodDao().getAll().any { it.source != FoodSource.BUILTIN }
        if (!dirty) return
        withContext(Dispatchers.IO) { db.clearAllTables() }
        c.builtinFoodSeeder.seedIfNeeded()
    }

    /** Logs [count] × first portion (or [gramsOverride] g / 100 g) of [food]. Returns 1. */
    private suspend fun logFood(
        c: AppContainer,
        date: LocalDate,
        meal: MealType,
        food: FoodEntity,
        count: Double,
        gramsOverride: Double? = null,
    ): Int {
        val portion = food.portions.firstOrNull()
        when {
            gramsOverride != null ->
                c.diaryRepository.addFood(date, meal, food, gramsOverride, food.unit, gramsOverride)
            portion != null ->
                c.diaryRepository.addFood(date, meal, food, count, portion.name, portion.grams * count)
            else -> {
                val grams = 100.0 * count.coerceAtLeast(0.5)
                c.diaryRepository.addFood(date, meal, food, grams, food.unit, grams)
            }
        }
        return 1
    }

    private class BuiltinFoods(private val all: List<FoodEntity>) {
        private val byName = all.associateBy { it.name }

        /** Exact name, else first food whose name starts with the first word, else a stable fallback. */
        fun pick(vararg names: String): FoodEntity {
            names.firstNotNullOfOrNull { byName[it] }?.let { return it }
            for (n in names) {
                val word = n.substringBefore(',').substringBefore(" (").substringBefore(' ').trim()
                all.firstOrNull { it.name.startsWith(word, ignoreCase = true) }?.let { return it }
            }
            return all[Math.floorMod(names.first().hashCode(), all.size)]
        }
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

    /** Appends the EAN-13 check digit to 12 digits. */
    fun ean13(first12: String): String {
        require(first12.length == 12 && first12.all { it.isDigit() })
        val sum = first12.mapIndexed { i, ch -> (ch - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return first12 + ((10 - sum % 10) % 10)
    }
}
