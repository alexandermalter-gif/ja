package de.kalorienkompass.backup

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import java.time.LocalDate

/** A small but complete data set (as it would be read from the database). */
internal object BackupFixtures {

    val apple = FoodEntity(
        id = 1, name = "Apfel", kcal = 52.0, protein = 0.3, carbs = 11.4, fat = 0.2, fiber = 2.4,
        portions = listOf(FoodPortion("1 mittelgroßer Apfel", 150.0)),
        source = FoodSource.BUILTIN, category = "Obst",
        isFavorite = true, lastUsedAt = 1_762_300_000_000, useCount = 3, createdAt = 1L,
    )
    val rice = FoodEntity(
        id = 2, name = "Reis, gekocht", kcal = 130.0, protein = 2.7, carbs = 28.0, fat = 0.3,
        source = FoodSource.BUILTIN, category = "Getreide & Beilagen", createdAt = 1L,
    )
    val banana = FoodEntity(
        id = 3, name = "Banane", kcal = 93.0, protein = 1.2, carbs = 20.0, fat = 0.2,
        source = FoodSource.BUILTIN, category = "Obst", createdAt = 1L,
    )
    val cake = FoodEntity(
        id = 250, name = "Omas Kuchen", brand = "Selbstgemacht", kcal = 350.0, protein = 5.5, carbs = 45.0,
        fat = 16.0, sugar = 28.0, portions = listOf(FoodPortion("Stück", 120.0)), source = FoodSource.CUSTOM,
        isFavorite = true, createdAt = 1_000L,
    )
    val oatDrink = FoodEntity(
        id = 251, name = "Haferdrink", brand = "Oatly", barcode = "7394376616037", kcal = 46.0, protein = 1.0,
        carbs = 6.7, fat = 1.5, sugar = 4.0, fiber = 0.8, salt = 0.1, unit = "ml",
        portions = listOf(FoodPortion("Glas", 200.0), FoodPortion("Packung", 1000.0)),
        source = FoodSource.OPEN_FOOD_FACTS, imageUrl = "https://images.example.org/oat.jpg",
        lastUsedAt = 1_762_310_000_000, useCount = 5, createdAt = 2_000L,
    )
    val aiPasta = FoodEntity(
        id = 252, name = "Spaghetti mit Tomatensauce", kcal = 140.0, protein = 4.8, carbs = 24.0, fat = 2.5,
        source = FoodSource.AI, createdAt = 3_000L,
    )

    val builtins: List<FoodEntity> = listOf(apple, rice, banana)
    val foods: List<FoodEntity> = builtins + listOf(cake, oatDrink, aiPasta)

    val recipe = RecipeEntity(
        id = 7, name = "Milchreis mit Haferdrink", portions = 2, note = "Mit Zimt",
        isFavorite = true, lastUsedAt = 1_762_320_000_000, createdAt = 4_000L,
    )
    val ingredients: List<RecipeIngredientEntity> = listOf(
        RecipeIngredientEntity(id = 11, recipeId = 7, foodId = 2, grams = 200.0, sortIndex = 0),
        RecipeIngredientEntity(id = 12, recipeId = 7, foodId = 251, grams = 300.0, sortIndex = 1),
    )

    val day1: LocalDate = LocalDate.of(2026, 11, 3)
    val day2: LocalDate = LocalDate.of(2026, 11, 4)

    val diary: List<DiaryEntryEntity> = listOf(
        DiaryEntryEntity(
            id = 21, date = day1, meal = MealType.BREAKFAST, name = "Apfel", amount = 1.0,
            unitLabel = "1 mittelgroßer Apfel", grams = 150.0, kcal = 78.0, protein = 0.45, carbs = 17.1, fat = 0.3,
            foodId = 1, source = EntrySource.FOOD, createdAt = 10L,
        ),
        DiaryEntryEntity(
            id = 22, date = day1, meal = MealType.DINNER, name = "Milchreis mit Haferdrink", amount = 1.5,
            unitLabel = "Portion", grams = 375.0, kcal = 298.5, protein = 6.3, carbs = 57.1, fat = 3.6,
            recipeId = 7, source = EntrySource.RECIPE, createdAt = 11L,
        ),
        DiaryEntryEntity(
            id = 23, date = day2, meal = MealType.SNACK, name = "Schnelleintrag", amount = 1.0, unitLabel = "",
            grams = 0.0, kcal = 250.0, protein = 0.0, carbs = 0.0, fat = 0.0, source = EntrySource.QUICK,
            createdAt = 12L,
        ),
        DiaryEntryEntity(
            id = 24, date = day2, meal = MealType.LUNCH, name = "Haferdrink", brand = "Oatly", amount = 250.0,
            unitLabel = "ml", grams = 250.0, kcal = 115.0, protein = 2.5, carbs = 16.75, fat = 3.75,
            foodId = 251, source = EntrySource.FOOD, createdAt = 13L,
        ),
    )

    val measurements: List<MeasurementEntity> = listOf(
        MeasurementEntity(id = 31, type = MeasurementType.WEIGHT, date = day1, value = 82.45, createdAt = 20L),
        MeasurementEntity(
            id = 32, type = MeasurementType.BODY_FAT, date = day2, value = 21.5,
            source = DataSource.HEALTH_CONNECT, externalId = "hc-record-1", createdAt = 21L,
        ),
    )

    val activity: List<DailyActivityEntity> = listOf(
        DailyActivityEntity(date = day1, steps = 8500, activeKcal = 320.5, source = DataSource.HEALTH_CONNECT, updatedAt = 30L),
        DailyActivityEntity(date = day2, steps = 4000, activeKcal = null, source = DataSource.MANUAL, updatedAt = 31L),
    )

    val profile = UserProfile(
        onboardingDone = true, name = "Jörg", sex = Sex.MALE, birthYear = 1988, heightCm = 181.5,
        startWeightKg = 90.0, targetWeightKg = 80.0, activityLevel = ActivityLevel.MODERATE,
        goalType = GoalType.LOSE, weeklyRateKg = 0.75,
    )
    val goals = NutritionGoalSettings(customCalorieGoal = 2100, carbsPercent = 40, proteinPercent = 30, fatPercent = 30)
    val settings = AppSettings(
        themeMode = ThemeMode.SYSTEM, stepGoal = 12000, aiModel = AiModel.HAIKU,
        healthConnectEnabled = true, lastHealthSyncAt = 1_762_330_000_000,
    )

    fun backup(): BackupFile = BackupExport.build(
        foods = foods,
        recipes = listOf(recipe),
        ingredients = ingredients,
        diary = diary,
        measurements = measurements,
        activity = activity,
        profile = profile,
        goals = goals,
        settings = settings,
        exportedAt = "2026-11-05T14:05:00+01:00",
        appVersion = "1.0.0",
    )
}
