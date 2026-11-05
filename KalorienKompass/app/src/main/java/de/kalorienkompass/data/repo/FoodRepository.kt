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
     * Local search (built-in, custom, cached and AI foods). The query is a plain substring
     * (`%`/`_` are not wildcards). Besides the plain "contains" search, multi-word queries also
     * match names that contain all words in any order/with punctuation ("Reis gekocht" finds
     * "Reis, gekocht"). Ranking: prefix matches first, then most used, then shorter names.
     */
    suspend fun searchLocal(query: String, limit: Int = 50): List<FoodEntity> {
        val q = query.trim().replace(WHITESPACE, " ")
        if (q.isEmpty() || limit <= 0) return emptyList()

        // SQLite LIKE ignores case only for ASCII letters, so umlaut spellings ("äpfel" → "Äpfel",
        // "MÜSLI" → "Müsli") are queried as well; the merged hits are ranked again.
        val direct = LinkedHashMap<Long, FoodEntity>()
        for (variant in caseVariants(q)) {
            for (food in dao.search(variant, limit)) direct.putIfAbsent(food.id, food)
        }
        val ranking = rankingFor(q)
        val results = LinkedHashMap<Long, FoodEntity>()
        for (food in direct.values.sortedWith(ranking)) {
            if (results.size >= limit) break
            results[food.id] = food
        }

        val tokens = tokenize(q)
        if (results.size < limit && tokens.size > 1) {
            val longest = tokens.maxByOrNull { it.length } ?: return results.values.toList()
            val candidates = LinkedHashMap<Long, FoodEntity>()
            for (variant in caseVariants(longest)) {
                for (food in dao.search(variant, MULTI_WORD_CANDIDATES)) candidates.putIfAbsent(food.id, food)
            }
            val matching = candidates.values.filter { food ->
                val haystack = (food.name + " " + (food.brand ?: "")).lowercase(Locale.GERMAN)
                tokens.all { haystack.contains(it) }
            }
            for (food in matching.sortedWith(ranking)) {
                if (results.size >= limit) break
                results.putIfAbsent(food.id, food)
            }
        }
        return results.values.toList()
    }

    /** Remote search; results are NOT persisted (id = 0). Throws IOException on network problems. */
    suspend fun searchRemote(query: String, page: Int = 1): List<FoodEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return remote.search(q, page.coerceAtLeast(1))
    }

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
     * Inserts a remote product or updates the existing cached copy (matched by barcode – also the
     * UPC-A/EAN-13 variant – or by name + brand when there is no barcode). Keeps id, barcode,
     * favorite flag and usage statistics. A food that is not a cached Open Food Facts product
     * (e.g. the user's own food with the same barcode) is never overwritten. Returns the local id.
     */
    suspend fun cacheRemoteFood(food: FoodEntity): Long {
        val barcode = food.barcode?.trim()?.takeIf { it.isNotEmpty() }
        val normalized = food.copy(barcode = barcode)
        return db.withTransaction {
            val existing = if (barcode != null) {
                barcodeVariants(barcode).firstNotNullOfOrNull { dao.getByBarcode(it) }
            } else {
                dao.findBySourceNameAndBrand(normalized.source, normalized.name, normalized.brand ?: "")
            }
            when {
                existing == null -> dao.insert(normalized.copy(id = 0))
                existing.source == FoodSource.OPEN_FOOD_FACTS -> {
                    dao.update(
                        normalized.copy(
                            id = existing.id,
                            barcode = existing.barcode ?: barcode,
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
     * Insert if id == 0 else update (a food whose id no longer exists is inserted again with
     * that id); returns the id. Name/brand/barcode are trimmed, blank brand/barcode are stored as null.
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
            when {
                normalized.id == 0L -> dao.insert(normalized)
                dao.getById(normalized.id) == null -> dao.insert(normalized)
                else -> {
                    dao.update(normalized)
                    normalized.id
                }
            }
        }
    }

    /** Returns false (and deletes nothing) if the food is used by a recipe. */
    suspend fun deleteFood(food: FoodEntity): Boolean =
        db.withTransaction {
            if (db.recipeDao().countRecipesUsingFood(food.id) > 0) {
                false
            } else {
                dao.delete(food)
                true
            }
        }

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun markUsed(id: Long) = dao.markUsed(id, System.currentTimeMillis())

    private fun tokenize(q: String): List<String> =
        q.lowercase(Locale.GERMAN)
            .split(TOKEN_SPLIT)
            .filter { it.length >= 2 }
            .distinct()

    private companion object {
        const val MULTI_WORD_CANDIDATES = 300
        val TOKEN_SPLIT = Regex("[\\s,;/()\\-]+")
        val WHITESPACE = Regex("\\s+")

        /**
         * Spellings to query so that non-ASCII letters (umlauts) match case-insensitively:
         * as typed, "Capitalised" and lower case. Pure ASCII queries need no variants.
         */
        fun caseVariants(q: String): List<String> {
            if (q.all { it.code < 0x80 }) return listOf(q)
            val lower = q.lowercase(Locale.GERMAN)
            return listOf(
                q,
                q.replaceFirstChar { it.titlecase(Locale.GERMAN) },
                lower.replaceFirstChar { it.titlecase(Locale.GERMAN) },
                lower,
            ).distinct()
        }

        /** Same order as [de.kalorienkompass.data.db.FoodDao.search], but case-insensitive for umlauts too. */
        fun rankingFor(q: String): Comparator<FoodEntity> {
            val prefix = q.lowercase(Locale.GERMAN)
            return compareBy<FoodEntity> { if (it.name.lowercase(Locale.GERMAN).startsWith(prefix)) 0 else 1 }
                .thenByDescending { it.useCount }
                .thenBy { it.name.length }
                .thenBy { it.name.lowercase(Locale.GERMAN) }
                .thenBy { it.id }
        }

        /** UPC-A (12 digits) and EAN-13 with a leading zero describe the same product. */
        fun barcodeVariants(code: String): List<String> {
            val variants = mutableListOf(code)
            if (code.all { it in '0'..'9' }) {
                if (code.length == 12) variants += "0$code"
                if (code.length == 13 && code.startsWith("0")) variants += code.substring(1)
            }
            return variants
        }
    }
}
