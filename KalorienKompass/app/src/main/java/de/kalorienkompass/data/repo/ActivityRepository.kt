package de.kalorienkompass.data.repo

import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Daily steps. Priority of sources: MANUAL > HEALTH_CONNECT > SENSOR.
 */
class ActivityRepository(private val db: AppDatabase) {

    private val dao get() = db.activityDao()

    fun observeDay(date: LocalDate): Flow<DailyActivityEntity?> = dao.observeDay(date)

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DailyActivityEntity>> = dao.observeRange(from, to)

    /** Manual steps always win (burned kcal are then derived from the steps). */
    suspend fun setManualSteps(date: LocalDate, steps: Long) {
        dao.upsert(
            DailyActivityEntity(
                date = date,
                steps = steps.coerceAtLeast(0L),
                activeKcal = null,
                source = DataSource.MANUAL,
            ),
        )
    }

    /** Skipped if the day was entered manually. */
    suspend fun upsertFromHealthConnect(date: LocalDate, steps: Long, activeKcal: Double?) {
        val existing = dao.get(date)
        if (existing?.source == DataSource.MANUAL) return
        // Health Connect without any data must not wipe steps that were already counted by the sensor.
        if (existing != null && steps <= 0L && activeKcal == null && existing.steps > 0L) return
        if (existing != null &&
            existing.source == DataSource.HEALTH_CONNECT &&
            existing.steps == steps &&
            existing.activeKcal == activeKcal
        ) {
            return
        }
        dao.upsert(
            DailyActivityEntity(
                date = date,
                steps = steps.coerceAtLeast(0L),
                activeKcal = activeKcal,
                source = DataSource.HEALTH_CONNECT,
            ),
        )
    }

    /** Skipped if the day was entered manually or comes from Health Connect. */
    suspend fun upsertFromSensor(date: LocalDate, steps: Long) {
        val existing = dao.get(date)
        if (existing != null && existing.source != DataSource.SENSOR) return
        if (existing != null && existing.steps == steps) return
        dao.upsert(
            DailyActivityEntity(
                date = date,
                steps = steps.coerceAtLeast(0L),
                activeKcal = null,
                source = DataSource.SENSOR,
            ),
        )
    }
}
