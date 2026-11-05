package de.kalorienkompass.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.kalorienkompass.data.model.MealType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DiaryDao {

    @Query("SELECT * FROM diary_entries WHERE date = :date ORDER BY createdAt ASC, id ASC")
    fun observeDay(date: LocalDate): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE date BETWEEN :from AND :to ORDER BY date ASC, createdAt ASC, id ASC")
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DiaryEntryEntity>>

    @Query(
        """
        SELECT date,
               SUM(kcal) AS kcal,
               SUM(protein) AS protein,
               SUM(carbs) AS carbs,
               SUM(fat) AS fat
        FROM diary_entries
        WHERE date BETWEEN :from AND :to
        GROUP BY date
        ORDER BY date ASC
        """,
    )
    fun observeDailyTotals(from: LocalDate, to: LocalDate): Flow<List<DailyNutritionTotals>>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun getById(id: Long): DiaryEntryEntity?

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<DiaryEntryEntity>)

    @Update
    suspend fun update(entry: DiaryEntryEntity)

    @Delete
    suspend fun delete(entry: DiaryEntryEntity)

    @Query("SELECT * FROM diary_entries ORDER BY date ASC, createdAt ASC, id ASC")
    suspend fun getAll(): List<DiaryEntryEntity>

    @Query("DELETE FROM diary_entries")
    suspend fun deleteAll()

    @Query("SELECT * FROM diary_entries WHERE date = :date AND meal = :meal ORDER BY createdAt ASC, id ASC")
    suspend fun getEntriesForMeal(date: LocalDate, meal: MealType): List<DiaryEntryEntity>
}
