package de.kalorienkompass.data.repo

import androidx.room.withTransaction
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Daily steps. Priority of sources: MANUAL > HEALTH_CONNECT > SENSOR.
 * Every read-check-write runs in one transaction, so a Health Connect sync and a sensor reading
 * running at the same time cannot overwrite a value of higher priority.
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
        val kcal = activeKcal?.takeIf { it.isFinite() && it >= 0.0 }
        val safeSteps = steps.coerceAtLeast(0L)
        db.withTransaction {
            val existing = dao.get(date)
            when {
                existing?.source == DataSource.MANUAL -> Unit
                // Health Connect without any data must not wipe steps that were already counted (e.g. by the sensor).
                existing != null && safeSteps == 0L && kcal == null && existing.steps > 0L -> Unit
                existing != null &&
                    existing.source == DataSource.HEALTH_CONNECT &&
                    existing.steps == safeSteps &&
                    existing.activeKcal == kcal -> Unit
                else -> dao.upsert(
                    DailyActivityEntity(
                        date = date,
                        steps = safeSteps,
                        activeKcal = kcal,
                        source = DataSource.HEALTH_CONNECT,
                    ),
                )
            }
        }
    }

    /** Skipped if the day was entered manually or comes from Health Connect. */
    suspend fun upsertFromSensor(date: LocalDate, steps: Long) {
        val safeSteps = steps.coerceAtLeast(0L)
        db.withTransaction {
            val existing = dao.get(date)
            when {
                existing != null && existing.source != DataSource.SENSOR -> Unit
                existing != null && existing.steps == safeSteps -> Unit
                else -> dao.upsert(
                    DailyActivityEntity(
                        date = date,
                        steps = safeSteps,
                        activeKcal = null,
                        source = DataSource.SENSOR,
                    ),
                )
            }
        }
    }
}
