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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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
        kcal = kcal.sanitized(),
        protein = protein.sanitized(),
        carbs = carbs.sanitized(),
        fat = fat.sanitized(),
        sugar = sugar?.sanitized(),
        fiber = fiber?.sanitized(),
        salt = salt?.sanitized(),
        unit = if (unit.trim().equals("ml", ignoreCase = true)) "ml" else "g",
        portions = portions
            .filter { it.name.isNotBlank() && it.grams.isFinite() && it.grams > 0.0 }
            .map { it.copy(name = it.name.trim()) },
        source = FoodSource.BUILTIN,
        category = category?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun Double.sanitized(): Double = if (isFinite() && this > 0.0) this else 0.0
}

/**
 * Seeds the built-in German basic foods from `assets/foods_de.json`.
 *
 * Runs when the stored seed version is lower than the JSON version (or no built-in food exists).
 * New names are inserted; existing BUILTIN rows are updated by name, keeping id, favorite flag
 * and usage statistics (so diary/recipe references stay valid). Idempotent: concurrent calls
 * (also from different instances) are serialized and the existing rows are re-read inside the
 * database transaction, so no food is ever inserted twice. Always runs on [Dispatchers.IO].
 */
class BuiltinFoodSeeder(
    private val context: Context,
    private val db: AppDatabase,
    private val json: Json,
) {
    /** Never throws (except cancellation); failures are logged. */
    suspend fun seedIfNeeded() {
        seedIfNeeded { context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    }

    /** [readJson] supplies the content of `foods_de.json` (called on [Dispatchers.IO]). */
    internal suspend fun seedIfNeeded(readJson: () -> String) {
        withContext(Dispatchers.IO) {
            SEED_MUTEX.withLock {
                try {
                    seed(readJson())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Seeding built-in foods failed", e)
                }
            }
        }
    }

    private suspend fun seed(text: String) {
        val file = parse(text, json)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(KEY_VERSION, 0)
        val dao = db.foodDao()
        if (storedVersion >= file.version && dao.countBySource(FoodSource.BUILTIN) > 0) return

        val items = file.foods
            .filter { it.name.isNotBlank() }
            .map { it.toEntity() }
            .distinctBy { it.name }
        if (items.isEmpty()) {
            Log.w(TAG, "No valid built-in foods in $ASSET_FILE")
            return
        }

        var inserted = 0
        db.withTransaction {
            val existingByName = dao.getBySource(FoodSource.BUILTIN).associateBy { it.name }
            val toInsert = ArrayList<FoodEntity>()
            for (item in items) {
                val existing = existingByName[item.name]
                if (existing == null) {
                    toInsert += item
                } else {
                    val updated = item.copy(
                        id = existing.id,
                        barcode = existing.barcode,
                        imageUrl = existing.imageUrl,
                        isFavorite = existing.isFavorite,
                        lastUsedAt = existing.lastUsedAt,
                        useCount = existing.useCount,
                        createdAt = existing.createdAt,
                    )
                    if (updated != existing) dao.update(updated)
                }
            }
            if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
            inserted = toInsert.size
        }
        // commit() (not apply()): the version must be on disk before we report success.
        prefs.edit().putInt(KEY_VERSION, file.version).commit()
        Log.i(TAG, "Built-in foods seeded: version ${file.version}, ${items.size} foods, $inserted new")
    }

    companion object {
        const val ASSET_FILE = "foods_de.json"
        private const val TAG = "BuiltinFoodSeeder"
        private const val PREFS_NAME = "builtin_foods"
        private const val KEY_VERSION = "seed_version"

        /** Process-wide: serializes all seeder instances (app start, backup import, tests). */
        private val SEED_MUTEX = Mutex()

        private val defaultJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parses the content of `foods_de.json` (also usable from JVM unit tests). Unknown fields are
         * ignored and single malformed food entries are skipped instead of failing the whole file.
         * Throws [kotlinx.serialization.SerializationException] / [IllegalArgumentException] only if the
         * text is not a JSON object.
         */
        fun parse(text: String, json: Json = defaultJson): BuiltinFoodsFile {
            val lenient = Json(from = json) {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }
            val root = lenient.parseToJsonElement(text).jsonObject
            val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 1
            val foods = (root["foods"] as? JsonArray).orEmpty().mapNotNull { element ->
                try {
                    lenient.decodeFromJsonElement(BuiltinFoodJson.serializer(), element)
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            return BuiltinFoodsFile(version = version, foods = foods)
        }
    }
}
