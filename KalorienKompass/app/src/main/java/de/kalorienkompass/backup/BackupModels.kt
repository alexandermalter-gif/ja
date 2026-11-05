package de.kalorienkompass.backup

import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import kotlinx.serialization.Serializable

/*
 * JSON backup format ("kalorienkompass-backup", version 1).
 *
 * Dates are ISO strings ("2026-11-05"), timestamps epoch millis. Original ids are kept so that
 * references between recipes, ingredients, foods and diary entries stay valid. Built-in foods are
 * not exported (they come with the app); they are referenced via [BackupFile.builtinFoods] (id + name)
 * so that the import can remap them by name on another device / app version.
 * The API key is never part of a backup.
 */

/** Thrown by [BackupManager] and [BackupCodec]; [message] is German and user-facing. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Root object of a KalorienKompass JSON backup. */
@Serializable
data class BackupFile(
    val format: String = FORMAT_ID,
    val version: Int = CURRENT_VERSION,
    /** ISO-8601 date-time with offset, e.g. "2026-11-05T14:05:00+01:00" */
    val exportedAt: String = "",
    val appVersion: String? = null,
    val profile: UserProfile = UserProfile(),
    val goals: NutritionGoalSettings = NutritionGoalSettings(),
    val settings: AppSettings = AppSettings(),
    /** all non-built-in foods (custom, Open Food Facts cache, AI estimates) */
    val foods: List<FoodDto> = emptyList(),
    val recipes: List<RecipeDto> = emptyList(),
    val recipeIngredients: List<RecipeIngredientDto> = emptyList(),
    val diary: List<DiaryEntryDto> = emptyList(),
    val measurements: List<MeasurementDto> = emptyList(),
    val activity: List<ActivityDto> = emptyList(),
    /**
     * Built-in foods that are referenced by recipes/diary entries or carry user state (favorite, usage).
     * `null` means "unknown" (file not written by this exporter).
     */
    val builtinFoods: List<BuiltinFoodRefDto>? = null,
) {
    companion object {
        const val FORMAT_ID: String = "kalorienkompass-backup"
        const val CURRENT_VERSION: Int = 1
    }
}

@Serializable
data class FoodDto(
    val id: Long = 0L,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
    val unit: String = "g",
    val portions: List<FoodPortion> = emptyList(),
    val source: FoodSource = FoodSource.CUSTOM,
    val category: String? = null,
    val imageUrl: String? = null,
    val isFavorite: Boolean = false,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val createdAt: Long = 0L,
)

@Serializable
data class RecipeDto(
    val id: Long = 0L,
    val name: String,
    val portions: Int = 1,
    val note: String? = null,
    val isFavorite: Boolean = false,
    val lastUsedAt: Long? = null,
    val createdAt: Long = 0L,
)

@Serializable
data class RecipeIngredientDto(
    val id: Long = 0L,
    val recipeId: Long,
    val foodId: Long,
    val grams: Double,
    val sortIndex: Int = 0,
    /** name of the referenced food at export time (informational, helps when remapping) */
    val foodName: String? = null,
)

@Serializable
data class DiaryEntryDto(
    val id: Long = 0L,
    /** ISO date "2026-11-05" */
    val date: String,
    val meal: MealType = MealType.SNACK,
    val name: String,
    val brand: String? = null,
    val amount: Double = 0.0,
    val unitLabel: String = "",
    val grams: Double = 0.0,
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val foodId: Long? = null,
    val recipeId: Long? = null,
    val source: EntrySource = EntrySource.FOOD,
    val createdAt: Long = 0L,
)

@Serializable
data class MeasurementDto(
    val id: Long = 0L,
    val type: MeasurementType,
    /** ISO date "2026-11-05" */
    val date: String,
    val value: Double,
    val source: DataSource = DataSource.MANUAL,
    val externalId: String? = null,
    val createdAt: Long = 0L,
)

@Serializable
data class ActivityDto(
    /** ISO date "2026-11-05" */
    val date: String,
    val steps: Long = 0L,
    val activeKcal: Double? = null,
    val source: DataSource = DataSource.MANUAL,
    val updatedAt: Long = 0L,
)

/** Reference to a built-in food of the exporting device (matched by name on import). */
@Serializable
data class BuiltinFoodRefDto(
    val id: Long,
    val name: String,
    val isFavorite: Boolean = false,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
)

/** What a backup contains (shown before importing and after import/export). */
data class BackupSummary(
    /** null if the export time is missing/unreadable */
    val exportedAtMillis: Long?,
    val appVersion: String?,
    val foods: Int,
    val recipes: Int,
    val diaryEntries: Int,
    val measurements: Int,
    val activityDays: Int,
) {
    val isEmpty: Boolean
        get() = foods == 0 && recipes == 0 && diaryEntries == 0 && measurements == 0 && activityDays == 0
}
