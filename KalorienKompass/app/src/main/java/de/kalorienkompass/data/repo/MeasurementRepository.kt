package de.kalorienkompass.data.repo

import android.util.Log
import androidx.room.withTransaction
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

    /**
     * Adds a MANUAL value. Weights are also sent to the [HealthDataExporter] (off the main thread,
     * errors are swallowed – the local value is saved in any case).
     */
    suspend fun add(type: MeasurementType, date: LocalDate, value: Double): Long {
        val id = dao.insert(MeasurementEntity(type = type, date = date, value = value, source = DataSource.MANUAL))
        if (type == MeasurementType.WEIGHT) exportWeightSafely(date, value)
        return id
    }

    /**
     * Sets THE manual value of [date]: updates the day's MANUAL entry (the newest one if there are
     * several) or inserts one – atomically, so quick repeated calls never create duplicates.
     * Weights are also exported (off the main thread, errors swallowed).
     */
    suspend fun setDailyManualValue(type: MeasurementType, date: LocalDate, value: Double) {
        db.withTransaction {
            val existing = dao.getForDate(type, date, DataSource.MANUAL)
            if (existing != null) {
                // createdAt is refreshed so that the manual value wins over other values of the same day
                dao.update(existing.copy(value = value, createdAt = System.currentTimeMillis()))
            } else {
                dao.insert(MeasurementEntity(type = type, date = date, value = value, source = DataSource.MANUAL))
            }
        }
        if (type == MeasurementType.WEIGHT) exportWeightSafely(date, value)
    }

    suspend fun update(m: MeasurementEntity) = dao.update(m)

    suspend fun delete(m: MeasurementEntity) = dao.delete(m)

    /**
     * Inserts or updates a value imported from Health Connect, matched by record id (idempotent:
     * importing the same record again changes nothing). Non-finite values are ignored.
     */
    suspend fun upsertFromHealthConnect(type: MeasurementType, date: LocalDate, value: Double, externalId: String) {
        if (!value.isFinite() || externalId.isBlank()) return
        db.withTransaction {
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
    }

    private suspend fun exportWeightSafely(date: LocalDate, kg: Double) {
        try {
            withContext(Dispatchers.IO) { exporter.exportWeight(date, kg) }
        } catch (e: CancellationException) {
            // Rethrow only if the caller itself was cancelled (not e.g. a timeout inside the exporter).
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Weight export cancelled", e)
        } catch (e: Exception) {
            Log.w(TAG, "Weight export failed", e)
        } catch (e: LinkageError) {
            // e.g. Health Connect classes missing on this device
            Log.w(TAG, "Weight export not available", e)
        }
    }

    private companion object {
        const val TAG = "MeasurementRepository"
    }
}
