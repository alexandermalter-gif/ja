package de.kalorienkompass.data.repo

import android.util.Log
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

class MeasurementRepository(
    private val db: AppDatabase,
    private val exporter: HealthDataExporter,
) {
    private val dao get() = db.measurementDao()

    fun observe(type: MeasurementType): Flow<List<MeasurementEntity>> = dao.observeByType(type)

    fun observeRange(type: MeasurementType, from: LocalDate, to: LocalDate): Flow<List<MeasurementEntity>> =
        dao.observeByTypeInRange(type, from, to)

    fun observeLatest(type: MeasurementType): Flow<MeasurementEntity?> = dao.observeLatest(type)

    /** Latest weight on or before [date]; re-emits whenever the measurements table changes. */
    fun observeLatestWeightOnOrBefore(date: LocalDate): Flow<Double?> =
        dao.observeLatestOnOrBefore(MeasurementType.WEIGHT, date)
            .map { it?.value }
            .distinctUntilChanged()

    suspend fun getLatestOnOrBefore(type: MeasurementType, date: LocalDate): MeasurementEntity? =
        dao.getLatestOnOrBefore(type, date)

    /** Adds a MANUAL value. Weights are also sent to the [HealthDataExporter] (errors are swallowed). */
    suspend fun add(type: MeasurementType, date: LocalDate, value: Double): Long {
        val id = dao.insert(MeasurementEntity(type = type, date = date, value = value, source = DataSource.MANUAL))
        if (type == MeasurementType.WEIGHT) exportWeightSafely(date, value)
        return id
    }

    /** Updates the MANUAL value of [date] (or inserts one). Weights are also exported. */
    suspend fun setDailyManualValue(type: MeasurementType, date: LocalDate, value: Double) {
        val existing = dao.getForDate(type, date, DataSource.MANUAL)
        if (existing != null) {
            // createdAt is refreshed so that the manual value wins over other values of the same day
            dao.update(existing.copy(value = value, createdAt = System.currentTimeMillis()))
        } else {
            dao.insert(MeasurementEntity(type = type, date = date, value = value, source = DataSource.MANUAL))
        }
        if (type == MeasurementType.WEIGHT) exportWeightSafely(date, value)
    }

    suspend fun update(m: MeasurementEntity) = dao.update(m)

    suspend fun delete(m: MeasurementEntity) = dao.delete(m)

    /** Inserts or updates a value imported from Health Connect (matched by record id). */
    suspend fun upsertFromHealthConnect(type: MeasurementType, date: LocalDate, value: Double, externalId: String) {
        val existing = dao.getByExternalId(externalId)
        if (existing == null) {
            dao.insert(
                MeasurementEntity(
                    type = type,
                    date = date,
                    value = value,
                    source = DataSource.HEALTH_CONNECT,
                    externalId = externalId,
                ),
            )
        } else if (existing.value != value || existing.date != date || existing.type != type) {
            dao.update(existing.copy(type = type, date = date, value = value))
        }
    }

    private suspend fun exportWeightSafely(date: LocalDate, kg: Double) {
        try {
            exporter.exportWeight(date, kg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Weight export failed", e)
        }
    }

    private companion object {
        const val TAG = "MeasurementRepository"
    }
}
