package de.kalorienkompass.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface MeasurementDao {

    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY date ASC, createdAt ASC, id ASC")
    fun observeByType(type: MeasurementType): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT * FROM measurements WHERE type = :type AND date BETWEEN :from AND :to " +
            "ORDER BY date ASC, createdAt ASC, id ASC",
    )
    fun observeByTypeInRange(type: MeasurementType, from: LocalDate, to: LocalDate): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY date DESC, createdAt DESC, id DESC LIMIT 1")
    fun observeLatest(type: MeasurementType): Flow<MeasurementEntity?>

    @Query(
        "SELECT * FROM measurements WHERE type = :type AND date <= :date " +
            "ORDER BY date DESC, createdAt DESC, id DESC LIMIT 1",
    )
    fun observeLatestOnOrBefore(type: MeasurementType, date: LocalDate): Flow<MeasurementEntity?>

    @Query(
        "SELECT * FROM measurements WHERE type = :type AND date <= :date " +
            "ORDER BY date DESC, createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun getLatestOnOrBefore(type: MeasurementType, date: LocalDate): MeasurementEntity?

    @Query(
        "SELECT * FROM measurements WHERE type = :type AND date = :date AND source = :source " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun getForDate(type: MeasurementType, date: LocalDate, source: DataSource): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE externalId = :externalId ORDER BY id ASC LIMIT 1")
    suspend fun getByExternalId(externalId: String): MeasurementEntity?

    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Insert
    suspend fun insertAll(measurements: List<MeasurementEntity>)

    @Update
    suspend fun update(measurement: MeasurementEntity)

    @Delete
    suspend fun delete(measurement: MeasurementEntity)

    @Query("SELECT * FROM measurements ORDER BY date ASC, createdAt ASC, id ASC")
    suspend fun getAll(): List<MeasurementEntity>

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()
}
