package de.kalorienkompass.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ActivityDao {

    @Query("SELECT * FROM daily_activity WHERE date = :date")
    fun observeDay(date: LocalDate): Flow<DailyActivityEntity?>

    @Query("SELECT * FROM daily_activity WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DailyActivityEntity>>

    @Query("SELECT * FROM daily_activity WHERE date = :date")
    suspend fun get(date: LocalDate): DailyActivityEntity?

    @Upsert
    suspend fun upsert(activity: DailyActivityEntity)

    @Upsert
    suspend fun upsertAll(activities: List<DailyActivityEntity>)

    @Query("SELECT * FROM daily_activity ORDER BY date ASC")
    suspend fun getAll(): List<DailyActivityEntity>

    @Query("DELETE FROM daily_activity")
    suspend fun deleteAll()
}
