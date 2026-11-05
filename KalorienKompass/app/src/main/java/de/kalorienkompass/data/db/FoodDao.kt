package de.kalorienkompass.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.kalorienkompass.data.model.FoodSource
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodDao {

    @Query("SELECT * FROM foods WHERE id = :id")
    fun observeById(id: Long): Flow<FoodEntity?>

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun getById(id: Long): FoodEntity?

    @Query("SELECT * FROM foods WHERE barcode = :code LIMIT 1")
    suspend fun getByBarcode(code: String): FoodEntity?

    /**
     * name/brand contains [query] (a plain substring: `%`, `_` and `\` in the user's input are matched
     * literally, not as LIKE wildcards; ASCII letters case-insensitive); prefix matches first,
     * then most used, then shorter names.
     */
    @Query(
        """
        SELECT * FROM foods
        WHERE name LIKE '%' || replace(replace(replace(:query, '\', '\\'), '%', '\%'), '_', '\_') || '%' ESCAPE '\'
           OR brand LIKE '%' || replace(replace(replace(:query, '\', '\\'), '%', '\%'), '_', '\_') || '%' ESCAPE '\'
        ORDER BY
            CASE WHEN name LIKE replace(replace(replace(:query, '\', '\\'), '%', '\%'), '_', '\_') || '%' ESCAPE '\'
                THEN 0 ELSE 1 END,
            useCount DESC,
            LENGTH(name) ASC,
            name COLLATE NOCASE ASC,
            id ASC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<FoodEntity>

    @Query("SELECT * FROM foods WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE isFavorite = 1 ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeFavorites(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE source = :source ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeBySource(source: FoodSource): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods ORDER BY id ASC")
    suspend fun getAll(): List<FoodEntity>

    @Query("SELECT * FROM foods WHERE source = :source ORDER BY id ASC")
    suspend fun getBySource(source: FoodSource): List<FoodEntity>

    @Query("SELECT COUNT(*) FROM foods WHERE source = :source")
    suspend fun countBySource(source: FoodSource): Int

    /** Used to de-duplicate cached remote products that have no barcode. */
    @Query(
        "SELECT * FROM foods WHERE source = :source AND name = :name AND IFNULL(brand, '') = :brand " +
            "ORDER BY id ASC LIMIT 1",
    )
    suspend fun findBySourceNameAndBrand(source: FoodSource, name: String, brand: String): FoodEntity?

    @Insert
    suspend fun insert(food: FoodEntity): Long

    @Insert
    suspend fun insertAll(foods: List<FoodEntity>)

    @Update
    suspend fun update(food: FoodEntity)

    @Delete
    suspend fun delete(food: FoodEntity)

    @Query("UPDATE foods SET lastUsedAt = :now, useCount = useCount + 1 WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)

    @Query("UPDATE foods SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM foods")
    suspend fun deleteAll()

    /** e.g. backup import: remove everything except the built-in foods. */
    @Query("DELETE FROM foods WHERE source != :keepSource")
    suspend fun deleteAllExceptSource(keepSource: FoodSource)
}
