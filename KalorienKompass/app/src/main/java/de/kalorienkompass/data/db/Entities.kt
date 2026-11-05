package de.kalorienkompass.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import java.time.LocalDate

/** A food. All nutrition values are per 100 g (or 100 ml). EU convention: [carbs] excludes fiber. */
@Entity(
    tableName = "foods",
    indices = [Index(value = ["barcode"], unique = true), Index("name")],
)
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
    /** "g" or "ml" */
    val unit: String = "g",
    /** stored as JSON via [Converters] */
    val portions: List<FoodPortion> = emptyList(),
    val source: FoodSource,
    /** e.g. "Obst" (built-in foods) */
    val category: String? = null,
    val imageUrl: String? = null,
    val isFavorite: Boolean = false,
    /** epoch millis */
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "diary_entries", indices = [Index("date")])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** stored as epochDay */
    val date: LocalDate,
    val meal: MealType,
    val name: String,
    val brand: String? = null,
    /** quantity in [unitLabel] units (e.g. 150 for "g", 2 for "Portion") */
    val amount: Double,
    /** "g", "ml", or a portion name like "Scheibe (50 g)", "Portion"; "" for quick entries */
    val unitLabel: String,
    /** total grams/ml eaten (0.0 for QUICK entries) */
    val grams: Double,
    /** TOTALS for this entry */
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val foodId: Long? = null,
    val recipeId: Long? = null,
    val source: EntrySource,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "measurements",
    indices = [Index(value = ["type", "date"]), Index("externalId")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: MeasurementType,
    val date: LocalDate,
    val value: Double,
    val source: DataSource = DataSource.MANUAL,
    /** Health Connect record id */
    val externalId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey val date: LocalDate,
    val steps: Long,
    /** from Health Connect if available */
    val activeKcal: Double? = null,
    val source: DataSource,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val portions: Int = 1,
    val note: String? = null,
    val isFavorite: Boolean = false,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "recipe_ingredients",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipeId"), Index("foodId")],
)
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val foodId: Long,
    val grams: Double,
    val sortIndex: Int = 0,
)

data class IngredientWithFood(
    @Embedded val ingredient: RecipeIngredientEntity,
    @Relation(parentColumn = "foodId", entityColumn = "id")
    val food: FoodEntity?,
)

data class RecipeWithIngredients(
    @Embedded val recipe: RecipeEntity,
    @Relation(
        entity = RecipeIngredientEntity::class,
        parentColumn = "id",
        entityColumn = "recipeId",
    )
    val ingredients: List<IngredientWithFood>,
)

/** Result row of [DiaryDao.observeDailyTotals]. */
data class DailyNutritionTotals(
    val date: LocalDate,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
)
