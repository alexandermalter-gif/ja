package de.kalorienkompass.data.builtin

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Format of `assets/foods_de.json`. All values per 100 g (or 100 ml). */
@Serializable
data class BuiltinFoodsFile(
    val version: Int,
    val foods: List<BuiltinFoodJson>,
)

@Serializable
data class BuiltinFoodJson(
    val name: String,
    val category: String? = null,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
    /** g/100 g, only for alcoholic drinks */
    val alcohol: Double? = null,
    val unit: String = "g",
    val portions: List<FoodPortion> = emptyList(),
) {
    fun toEntity(): FoodEntity = FoodEntity(
        name = name.trim(),
        kcal = kcal.coerceAtLeast(0.0),
        protein = protein.coerceAtLeast(0.0),
        carbs = carbs.coerceAtLeast(0.0),
        fat = fat.coerceAtLeast(0.0),
        sugar = sugar?.coerceAtLeast(0.0),
        fiber = fiber?.coerceAtLeast(0.0),
        salt = salt?.coerceAtLeast(0.0),
        unit = if (unit.equals("ml", ignoreCase = true)) "ml" else "g",
        portions = portions.filter { it.name.isNotBlank() && it.grams > 0.0 },
        source = FoodSource.BUILTIN,
        category = category?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Seeds the built-in German basic foods from `assets/foods_de.json`.
 *
 * Runs when the stored seed version is lower than the JSON version (or no built-in food exists).
 * New names are inserted; existing BUILTIN rows are updated by name, keeping id, favorite flag
 * and usage statistics (so diary/recipe references stay valid).
 */
class BuiltinFoodSeeder(
    private val context: Context,
    private val db: AppDatabase,
    private val json: Json,
) {
    private val mutex = Mutex()

    suspend fun seedIfNeeded() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    seed()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Seeding built-in foods failed", e)
                }
            }
        }
    }

    private suspend fun seed() {
        val text = context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val file = parse(text, json)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(KEY_VERSION, 0)
        val dao = db.foodDao()
        if (storedVersion >= file.version && dao.countBySource(FoodSource.BUILTIN) > 0) return

        val items = file.foods
            .filter { it.name.isNotBlank() }
            .map { it.toEntity() }
            .distinctBy { it.name }

        db.withTransaction {
            val existingByName = dao.getBySource(FoodSource.BUILTIN).associateBy { it.name }
            val toInsert = ArrayList<FoodEntity>()
            for (item in items) {
                val existing = existingByName[item.name]
                if (existing == null) {
                    toInsert += item
                } else {
                    dao.update(
                        item.copy(
                            id = existing.id,
                            barcode = existing.barcode,
                            imageUrl = existing.imageUrl,
                            isFavorite = existing.isFavorite,
                            lastUsedAt = existing.lastUsedAt,
                            useCount = existing.useCount,
                            createdAt = existing.createdAt,
                        ),
                    )
                }
            }
            if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        }
        prefs.edit().putInt(KEY_VERSION, file.version).apply()
        Log.i(TAG, "Built-in foods seeded: version ${file.version}, ${items.size} foods")
    }

    companion object {
        const val ASSET_FILE = "foods_de.json"
        private const val TAG = "BuiltinFoodSeeder"
        private const val PREFS_NAME = "builtin_foods"
        private const val KEY_VERSION = "seed_version"

        private val defaultJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses the content of `foods_de.json` (also usable from JVM unit tests). */
        fun parse(text: String, json: Json = defaultJson): BuiltinFoodsFile {
            val lenient = Json(from = json) { ignoreUnknownKeys = true }
            return lenient.decodeFromString(BuiltinFoodsFile.serializer(), text)
        }
    }
}
