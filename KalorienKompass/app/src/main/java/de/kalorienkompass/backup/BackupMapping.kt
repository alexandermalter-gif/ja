package de.kalorienkompass.backup

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Entity <-> DTO
// ---------------------------------------------------------------------------------------------

fun FoodEntity.toDto(): FoodDto = FoodDto(
    id = id,
    name = name,
    brand = brand,
    barcode = barcode,
    kcal = kcal,
    protein = protein,
    carbs = carbs,
    fat = fat,
    sugar = sugar,
    fiber = fiber,
    salt = salt,
    unit = unit,
    portions = portions,
    source = source,
    category = category,
    imageUrl = imageUrl,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    createdAt = createdAt,
)

/** Values are sanitized (finite, not negative); blank brand/barcode become null. */
fun FoodDto.toEntity(): FoodEntity = FoodEntity(
    id = id,
    name = name.trim(),
    brand = brand?.trim()?.takeIf { it.isNotEmpty() },
    barcode = barcode?.trim()?.takeIf { it.isNotEmpty() },
    kcal = kcal.sanitized(),
    protein = protein.sanitized(),
    carbs = carbs.sanitized(),
    fat = fat.sanitized(),
    sugar = sugar?.sanitized(),
    fiber = fiber?.sanitized(),
    salt = salt?.sanitized(),
    unit = if (unit.equals("ml", ignoreCase = true)) "ml" else "g",
    portions = portions.filter { it.name.isNotBlank() && it.grams.isFinite() && it.grams > 0.0 }
        .map { FoodPortion(it.name.trim(), it.grams) },
    source = source,
    category = category,
    imageUrl = imageUrl,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAt,
    useCount = useCount.coerceAtLeast(0),
    createdAt = createdAt,
)

fun RecipeEntity.toDto(): RecipeDto = RecipeDto(
    id = id,
    name = name,
    portions = portions,
    note = note,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAt,
    createdAt = createdAt,
)

fun RecipeDto.toEntity(): RecipeEntity = RecipeEntity(
    id = id,
    name = name.trim(),
    portions = portions.coerceAtLeast(1),
    note = note,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAt,
    createdAt = createdAt,
)

fun RecipeIngredientEntity.toDto(foodName: String? = null): RecipeIngredientDto = RecipeIngredientDto(
    id = id,
    recipeId = recipeId,
    foodId = foodId,
    grams = grams,
    sortIndex = sortIndex,
    foodName = foodName,
)

fun RecipeIngredientDto.toEntity(): RecipeIngredientEntity = RecipeIngredientEntity(
    id = id,
    recipeId = recipeId,
    foodId = foodId,
    grams = grams.sanitized(),
    sortIndex = sortIndex,
)

fun DiaryEntryEntity.toDto(): DiaryEntryDto = DiaryEntryDto(
    id = id,
    date = date.toString(),
    meal = meal,
    name = name,
    brand = brand,
    amount = amount,
    unitLabel = unitLabel,
    grams = grams,
    kcal = kcal,
    protein = protein,
    carbs = carbs,
    fat = fat,
    foodId = foodId,
    recipeId = recipeId,
    source = source,
    createdAt = createdAt,
)

/** Throws [BackupException] if the date is invalid. */
fun DiaryEntryDto.toEntity(): DiaryEntryEntity = DiaryEntryEntity(
    id = id,
    date = parseBackupDate(date),
    meal = meal,
    name = name,
    brand = brand,
    amount = amount.sanitized(),
    unitLabel = unitLabel,
    grams = grams.sanitized(),
    kcal = kcal.sanitized(),
    protein = protein.sanitized(),
    carbs = carbs.sanitized(),
    fat = fat.sanitized(),
    foodId = foodId,
    recipeId = recipeId,
    source = source,
    createdAt = createdAt,
)

fun MeasurementEntity.toDto(): MeasurementDto = MeasurementDto(
    id = id,
    type = type,
    date = date.toString(),
    value = value,
    source = source,
    externalId = externalId,
    createdAt = createdAt,
)

/** Throws [BackupException] if the date is invalid. */
fun MeasurementDto.toEntity(): MeasurementEntity = MeasurementEntity(
    id = id,
    type = type,
    date = parseBackupDate(date),
    value = value.sanitized(),
    source = source,
    externalId = externalId,
    createdAt = createdAt,
)

fun DailyActivityEntity.toDto(): ActivityDto = ActivityDto(
    date = date.toString(),
    steps = steps,
    activeKcal = activeKcal,
    source = source,
    updatedAt = updatedAt,
)

/** Throws [BackupException] if the date is invalid. */
fun ActivityDto.toEntity(): DailyActivityEntity = DailyActivityEntity(
    date = parseBackupDate(date),
    steps = steps.coerceAtLeast(0L),
    activeKcal = activeKcal?.sanitized(),
    source = source,
    updatedAt = updatedAt,
)

/** ISO date ("2026-11-05") → [LocalDate]; throws [BackupException] (German) otherwise. */
fun parseBackupDate(value: String): LocalDate = try {
    LocalDate.parse(value.trim())
} catch (e: DateTimeParseException) {
    throw BackupException("Das Backup enthält ein ungültiges Datum („$value“) und kann nicht importiert werden.", e)
}

private fun Double.sanitized(): Double = if (isNaN() || isInfinite() || this < 0.0) 0.0 else this

// ---------------------------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------------------------

/** Builds the backup object from a database snapshot (pure function). */
object BackupExport {

    fun build(
        foods: List<FoodEntity>,
        recipes: List<RecipeEntity>,
        ingredients: List<RecipeIngredientEntity>,
        diary: List<DiaryEntryEntity>,
        measurements: List<MeasurementEntity>,
        activity: List<DailyActivityEntity>,
        profile: UserProfile,
        goals: NutritionGoalSettings,
        settings: AppSettings,
        exportedAt: String,
        appVersion: String?,
    ): BackupFile {
        val referencedFoodIds = HashSet<Long>()
        ingredients.forEach { referencedFoodIds += it.foodId }
        diary.forEach { entry -> entry.foodId?.let { referencedFoodIds += it } }

        val builtinRefs = foods
            .filter { it.source == FoodSource.BUILTIN }
            .filter { it.id in referencedFoodIds || it.isFavorite || it.lastUsedAt != null || it.useCount > 0 }
            .map {
                BuiltinFoodRefDto(
                    id = it.id,
                    name = it.name,
                    isFavorite = it.isFavorite,
                    lastUsedAt = it.lastUsedAt,
                    useCount = it.useCount,
                )
            }
        val namesById: Map<Long, String> = foods.associate { it.id to it.name }

        return BackupFile(
            exportedAt = exportedAt,
            appVersion = appVersion,
            profile = profile,
            goals = goals,
            settings = settings,
            foods = foods.filter { it.source != FoodSource.BUILTIN }.map { it.toDto() },
            recipes = recipes.map { it.toDto() },
            recipeIngredients = ingredients.map { it.toDto(foodName = namesById[it.foodId]) },
            diary = diary.map { it.toDto() },
            measurements = measurements.map { it.toDto() },
            activity = activity.map { it.toDto() },
            builtinFoods = builtinRefs,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Import
// ---------------------------------------------------------------------------------------------

/** Everything the import writes (after all non-built-in data was deleted). */
data class ImportPlan(
    /** non-built-in foods with their final ids */
    val foods: List<FoodEntity>,
    val recipes: List<RecipeEntity>,
    /** ingredients with remapped food ids (unresolvable ones are dropped) */
    val ingredients: List<RecipeIngredientEntity>,
    val diary: List<DiaryEntryEntity>,
    val measurements: List<MeasurementEntity>,
    val activity: List<DailyActivityEntity>,
    /** built-in foods whose favorite flag / usage statistics change */
    val builtinUpdates: List<FoodEntity>,
    /** recipe ingredients whose food could not be found */
    val droppedIngredients: Int,
)

/**
 * Plans an import (pure function, unit-tested).
 *
 * - Non-built-in foods keep their original id unless it is taken by a built-in food of this
 *   device; then they get a fresh id and all references follow.
 * - References to built-in foods are remapped by name (built-in ids may differ between devices
 *   and app versions); if there is no name information, an existing built-in id is kept.
 * - Duplicate ids/barcodes of a damaged file are resolved instead of failing the import.
 */
object BackupImport {

    fun plan(backup: BackupFile, builtins: List<FoodEntity>): ImportPlan {
        val builtinIds: Set<Long> = builtins.map { it.id }.toHashSet()
        val builtinByName: Map<String, FoodEntity> = builtins
            .groupBy { normalizeName(it.name) }
            .mapValues { (_, list) -> list.minBy { it.id } }

        // Built-in references: explicit list + any BUILTIN foods a foreign file might contain.
        val hasBuiltinInfo = backup.builtinFoods != null
        val builtinRefs: List<BuiltinFoodRefDto> = (backup.builtinFoods ?: emptyList()) +
            backup.foods.filter { it.source == FoodSource.BUILTIN }.map {
                BuiltinFoodRefDto(it.id, it.name, it.isFavorite, it.lastUsedAt, it.useCount)
            }

        val builtinIdMap = HashMap<Long, Long>()
        for (ref in builtinRefs) {
            val local = builtinByName[normalizeName(ref.name)]?.id ?: ref.id.takeIf { it in builtinIds }
            if (local != null) builtinIdMap.putIfAbsent(ref.id, local)
        }

        // ---- foods ----
        val customDtos = backup.foods.filter { it.source != FoodSource.BUILTIN && it.name.isNotBlank() }
        val takenIds = HashSet<Long>(builtinIds)
        var nextFoodId = maxOf(builtins.maxOfOrNull { it.id } ?: 0L, customDtos.maxOfOrNull { it.id } ?: 0L) + 1L
        val usedBarcodes = HashSet<String>()
        builtins.mapNotNullTo(usedBarcodes) { it.barcode }
        val foodIdMap = HashMap<Long, Long>()
        val foods = customDtos.map { dto ->
            val entity = dto.toEntity()
            val wanted = dto.id
            val id = if (wanted > 0L && wanted !in takenIds) wanted else nextFoodId++
            takenIds += id
            if (wanted > 0L) foodIdMap.putIfAbsent(wanted, id)
            val barcode = entity.barcode?.takeIf { usedBarcodes.add(it) }
            entity.copy(id = id, barcode = barcode)
        }

        fun resolveFood(oldId: Long): Long? =
            foodIdMap[oldId]
                ?: builtinIdMap[oldId]
                ?: oldId.takeIf { !hasBuiltinInfo && it in builtinIds }

        // ---- recipes ----
        val recipeIds = HashSet<Long>()
        var nextRecipeId = (backup.recipes.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        val recipes = backup.recipes.filter { it.name.isNotBlank() }.map { dto ->
            val id = if (dto.id > 0L && dto.id !in recipeIds) dto.id else nextRecipeId++
            recipeIds += id
            dto.toEntity().copy(id = id)
        }

        // ---- ingredients ----
        val ingredientIds = HashSet<Long>()
        var dropped = 0
        val ingredients = backup.recipeIngredients
            .filter { it.recipeId in recipeIds }
            .mapNotNull { dto ->
                val foodId = resolveFood(dto.foodId)
                if (foodId == null) {
                    dropped++
                    null
                } else {
                    val id = if (dto.id > 0L && ingredientIds.add(dto.id)) dto.id else 0L
                    dto.toEntity().copy(id = id, foodId = foodId)
                }
            }

        // ---- diary ----
        val diaryIds = HashSet<Long>()
        val diary = backup.diary.map { dto ->
            val id = if (dto.id > 0L && diaryIds.add(dto.id)) dto.id else 0L
            dto.toEntity().copy(
                id = id,
                foodId = dto.foodId?.let { resolveFood(it) },
                recipeId = dto.recipeId?.takeIf { it in recipeIds },
            )
        }

        // ---- measurements & activity ----
        val measurementIds = HashSet<Long>()
        val measurements = backup.measurements.map { dto ->
            val id = if (dto.id > 0L && measurementIds.add(dto.id)) dto.id else 0L
            dto.toEntity().copy(id = id)
        }
        val activity = backup.activity.map { it.toEntity() }.distinctBy { it.date }

        // ---- user state of built-in foods (favorites, usage) ----
        // With complete information the backup's state replaces the current one; otherwise only
        // the foods the file mentions are updated.
        val stateByName: Map<String, BuiltinFoodRefDto> = builtinRefs.associateBy { normalizeName(it.name) }
        val builtinUpdates = builtins.mapNotNull { food ->
            val ref = stateByName[normalizeName(food.name)]
            if (ref == null && !hasBuiltinInfo) return@mapNotNull null
            val favorite = ref?.isFavorite ?: false
            val lastUsed = ref?.lastUsedAt
            val count = (ref?.useCount ?: 0).coerceAtLeast(0)
            if (food.isFavorite != favorite || food.lastUsedAt != lastUsed || food.useCount != count) {
                food.copy(isFavorite = favorite, lastUsedAt = lastUsed, useCount = count)
            } else {
                null
            }
        }

        return ImportPlan(
            foods = foods,
            recipes = recipes,
            ingredients = ingredients,
            diary = diary,
            measurements = measurements,
            activity = activity,
            builtinUpdates = builtinUpdates,
            droppedIngredients = dropped,
        )
    }

    private fun normalizeName(name: String): String = name.trim().lowercase(Locale.ROOT)
}
