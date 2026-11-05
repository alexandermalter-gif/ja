package de.kalorienkompass.data.repo

import androidx.room.withTransaction
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.remote.FoodRemoteDataSource
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class FoodRepository(
    private val db: AppDatabase,
    private val remote: FoodRemoteDataSource,
) {
    private val dao get() = db.foodDao()

    fun observeFood(id: Long): Flow<FoodEntity?> = dao.observeById(id)

    suspend fun getFood(id: Long): FoodEntity? = dao.getById(id)

    /**
     * Local search (built-in, custom, cached and AI foods). Besides the plain "contains" search,
     * multi-word queries also match names that contain all words in any order/with punctuation
     * ("Reis gekocht" finds "Reis, gekocht").
     */
    suspend fun searchLocal(query: String, limit: Int = 50): List<FoodEntity> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()

        val results = LinkedHashMap<Long, FoodEntity>()
        fun addAll(list: List<FoodEntity>) {
            for (f in list) if (results.size < limit) results.putIfAbsent(f.id, f)
        }

        addAll(dao.search(q, limit))

        // SQLite LIKE is case-insensitive only for ASCII: retry with capitalised first letter (ä → Ä).
        if (results.size < limit) {
            val capitalised = q.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.GERMAN) else it.toString() }
            if (capitalised != q) addAll(dao.search(capitalised, limit))
        }

        val tokens = tokenize(q)
        if (results.size < limit && tokens.size > 1) {
            val longest = tokens.maxByOrNull { it.length } ?: return results.values.toList()
            val candidates = dao.search(longest, MULTI_WORD_CANDIDATES) +
                dao.search(longest.replaceFirstChar { it.titlecase(Locale.GERMAN) }, MULTI_WORD_CANDIDATES)
            val matching = candidates.filter { food ->
                val haystack = (food.name + " " + (food.brand ?: "")).lowercase(Locale.GERMAN)
                tokens.all { haystack.contains(it) }
            }
            addAll(matching)
        }
        return results.values.toList()
    }

    /** Remote search; results are NOT persisted (id = 0). Throws IOException on network problems. */
    suspend fun searchRemote(query: String, page: Int = 1): List<FoodEntity> =
        remote.search(query.trim(), page)

    /** Local database first, then Open Food Facts (the remote product is cached locally). */
    suspend fun lookupBarcode(barcode: String): BarcodeLookupResult {
        val code = barcode.trim()
        if (code.isEmpty()) return BarcodeLookupResult.NotFound

        for (variant in barcodeVariants(code)) {
            dao.getByBarcode(variant)?.let { return BarcodeLookupResult.Found(it) }
        }

        return try {
            val remoteFood = remote.findByBarcode(code)
            if (remoteFood == null) {
                BarcodeLookupResult.NotFound
            } else {
                val id = cacheRemoteFood(remoteFood.copy(barcode = remoteFood.barcode?.takeIf { it.isNotBlank() } ?: code))
                val stored = dao.getById(id)
                if (stored != null) {
                    BarcodeLookupResult.Found(stored)
                } else {
                    BarcodeLookupResult.Error("Das Produkt konnte nicht gespeichert werden.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            BarcodeLookupResult.Error(
                "Open Food Facts ist gerade nicht erreichbar. Bitte prüfe deine Internetverbindung und versuche es erneut.",
            )
        } catch (e: Exception) {
            BarcodeLookupResult.Error("Fehler bei der Produktsuche: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Inserts a remote product or updates the existing cached copy (matched by barcode, or by
     * name + brand when there is no barcode). Keeps id, favorite flag and usage statistics.
     * A user's own (CUSTOM) food with the same barcode is never overwritten.
     * Returns the local id.
     */
    suspend fun cacheRemoteFood(food: FoodEntity): Long {
        val barcode = food.barcode?.trim()?.takeIf { it.isNotEmpty() }
        val normalized = food.copy(barcode = barcode)
        return db.withTransaction {
            val existing = if (barcode != null) {
                dao.getByBarcode(barcode)
            } else {
                dao.findBySourceNameAndBrand(normalized.source, normalized.name, normalized.brand ?: "")
            }
            when {
                existing == null -> dao.insert(normalized.copy(id = 0))
                existing.source == FoodSource.OPEN_FOOD_FACTS -> {
                    dao.update(
                        normalized.copy(
                            id = existing.id,
                            isFavorite = existing.isFavorite,
                            lastUsedAt = existing.lastUsedAt,
                            useCount = existing.useCount,
                            createdAt = existing.createdAt,
                        ),
                    )
                    existing.id
                }
                else -> existing.id
            }
        }
    }

    fun observeRecent(limit: Int = 30): Flow<List<FoodEntity>> = dao.observeRecent(limit)

    fun observeFavorites(): Flow<List<FoodEntity>> = dao.observeFavorites()

    fun observeCustomFoods(): Flow<List<FoodEntity>> = dao.observeBySource(FoodSource.CUSTOM)

    /**
     * Insert if id == 0 else update; returns the id. Blank brand/barcode are stored as null.
     * Throws [IllegalArgumentException] (German message) if the barcode already belongs to another food.
     */
    suspend fun saveCustomFood(food: FoodEntity): Long {
        val normalized = food.copy(
            name = food.name.trim(),
            brand = food.brand?.trim()?.takeIf { it.isNotEmpty() },
            barcode = food.barcode?.trim()?.takeIf { it.isNotEmpty() },
        )
        return db.withTransaction {
            normalized.barcode?.let { code ->
                val other = dao.getByBarcode(code)
                if (other != null && other.id != normalized.id) {
                    throw IllegalArgumentException(
                        "Der Barcode $code ist bereits dem Lebensmittel „${other.name}“ zugeordnet.",
                    )
                }
            }
            if (normalized.id == 0L) {
                dao.insert(normalized)
            } else {
                dao.update(normalized)
                normalized.id
            }
        }
    }

    /** Returns false (and deletes nothing) if the food is used by a recipe. */
    suspend fun deleteFood(food: FoodEntity): Boolean {
        if (db.recipeDao().countRecipesUsingFood(food.id) > 0) return false
        dao.delete(food)
        return true
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun markUsed(id: Long) = dao.markUsed(id, System.currentTimeMillis())

    private fun tokenize(q: String): List<String> =
        q.lowercase(Locale.GERMAN)
            .split(TOKEN_SPLIT)
            .filter { it.length >= 2 }
            .distinct()

    /** UPC-A (12 digits) and EAN-13 with a leading zero describe the same product. */
    private fun barcodeVariants(code: String): List<String> {
        val variants = mutableListOf(code)
        if (code.all { it in '0'..'9' }) {
            if (code.length == 12) variants += "0$code"
            if (code.length == 13 && code.startsWith("0")) variants += code.substring(1)
        }
        return variants
    }

    private companion object {
        const val MULTI_WORD_CANDIDATES = 300
        val TOKEN_SPLIT = Regex("[\\s,;/()\\-]+")
    }
}
