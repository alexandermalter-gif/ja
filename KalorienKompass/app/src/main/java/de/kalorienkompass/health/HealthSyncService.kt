package de.kalorienkompass.health

import android.util.Log
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

data class HealthSyncResult(val success: Boolean, val message: String)

/**
 * Imports steps, active calories, weight and body fat from Health Connect into the local database.
 *
 * - Only runs when "Health Connect verwenden" is enabled and Health Connect is available.
 * - Reads every data type the user granted (partial permissions are fine).
 * - Days entered manually are never overwritten ([ActivityRepository] enforces the priority
 *   MANUAL > HEALTH_CONNECT > SENSOR); weights written by KalorienKompass itself are skipped.
 * - Concurrent calls (dashboard, settings screen, background worker) are serialized.
 */
class HealthSyncService(
    private val hc: HealthConnectManager,
    private val settings: SettingsRepository,
    private val activity: ActivityRepository,
    private val measurements: MeasurementRepository,
) {
    private val mutex = Mutex()

    /** Synchronizes the last [days] days (including today). Never throws (except cancellation). */
    suspend fun syncRecent(days: Int = 30): HealthSyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                sync(days.coerceIn(1, MAX_DAYS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Health Connect sync failed", e)
                HealthSyncResult(false, HealthConnectManager.describeError(e))
            }
        }
    }

    private suspend fun sync(days: Int): HealthSyncResult {
        if (!settings.settings.first().healthConnectEnabled) {
            return HealthSyncResult(false, "Health Connect ist nicht aktiviert.")
        }
        when (hc.availability()) {
            HcAvailability.AVAILABLE -> Unit
            HcAvailability.NOT_INSTALLED -> return HealthSyncResult(false, "Health Connect ist nicht installiert.")
            HcAvailability.UPDATE_REQUIRED -> return HealthSyncResult(false, "Health Connect muss aktualisiert werden.")
            HcAvailability.NOT_SUPPORTED ->
                return HealthSyncResult(false, "Health Connect wird auf diesem Gerät nicht unterstützt.")
        }

        val granted = hc.grantedPermissions()
        val canSteps = hc.readStepsPermission in granted
        val canKcal = hc.readActiveCaloriesPermission in granted
        val canWeight = hc.readWeightPermission in granted
        val canBodyFat = hc.readBodyFatPermission in granted
        if (!canSteps && !canKcal && !canWeight && !canBodyFat) {
            return HealthSyncResult(false, "Keine Leseberechtigung für Health Connect. Bitte Berechtigungen erteilen.")
        }

        val today = LocalDate.now()
        val from = today.minusDays(days - 1L)
        val errors = mutableListOf<Exception>()
        var attempts = 0

        suspend fun <T> attempt(enabled: Boolean, fallback: T, block: suspend () -> T): T {
            if (!enabled) return fallback
            attempts++
            return try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Health Connect read failed", e)
                errors += e
                fallback
            }
        }

        val steps = attempt(canSteps, emptyMap()) { hc.fetchDailySteps(from, today) }
        val activeKcal = attempt(canKcal, emptyMap()) { hc.fetchDailyActiveCalories(from, today) }
        val weights = attempt(canWeight, emptyList()) { hc.fetchWeights(from, today) }
        val bodyFat = attempt(canBodyFat, emptyList()) { hc.fetchBodyFat(from, today) }

        if (attempts > 0 && errors.size == attempts) {
            return HealthSyncResult(false, HealthConnectManager.describeError(errors.first()))
        }

        var activityDays = 0
        var date = from
        while (!date.isAfter(today)) {
            if (storeActivity(date, steps[date], activeKcal[date])) activityDays++
            date = date.plusDays(1)
        }
        for (m in weights) {
            measurements.upsertFromHealthConnect(MeasurementType.WEIGHT, m.date, m.value.round(2), m.id)
        }
        for (m in bodyFat) {
            measurements.upsertFromHealthConnect(MeasurementType.BODY_FAT, m.date, m.value.round(1), m.id)
        }

        settings.updateSettings { it.copy(lastHealthSyncAt = System.currentTimeMillis()) }
        return HealthSyncResult(
            success = true,
            message = HealthSyncMessages.success(
                days = days,
                activityDays = activityDays,
                weights = weights.size,
                bodyFatValues = bodyFat.size,
                partial = errors.isNotEmpty(),
            ),
        )
    }

    /** @return true if Health Connect delivered activity data for [date]. */
    private suspend fun storeActivity(date: LocalDate, steps: Long?, activeKcal: Double?): Boolean {
        val kcal = activeKcal?.takeIf { it > 0.0 && !it.isNaN() }?.round(1)
        if (steps != null && steps > 0L) {
            activity.upsertFromHealthConnect(date, steps, kcal)
            return true
        }
        if (kcal == null) return false
        // Only active calories (no steps in Health Connect): never replace steps from the sensor or manual entries.
        val existing = activity.observeDay(date).first()
        if (existing != null && existing.source != DataSource.HEALTH_CONNECT) return false
        activity.upsertFromHealthConnect(date, existing?.steps ?: 0L, kcal)
        return true
    }

    private fun Double.round(decimals: Int): Double {
        val factor = when (decimals) {
            0 -> 1.0
            1 -> 10.0
            2 -> 100.0
            else -> 1000.0
        }
        return (this * factor).roundToLong() / factor
    }

    private companion object {
        const val TAG = "HealthSyncService"

        /** Health Connect only returns data of the last 30 days before the first permission grant anyway. */
        const val MAX_DAYS = 90
    }
}

/** German result texts of a sync (pure, unit-tested). */
internal object HealthSyncMessages {

    fun success(days: Int, activityDays: Int, weights: Int, bodyFatValues: Int, partial: Boolean): String {
        val parts = buildList {
            if (activityDays > 0) add("Aktivität für ${plural(activityDays, "Tag", "Tage")}")
            if (weights > 0) add(plural(weights, "Gewichtswert", "Gewichtswerte"))
            if (bodyFatValues > 0) add(plural(bodyFatValues, "Körperfettwert", "Körperfettwerte"))
        }
        val base = if (parts.isEmpty()) {
            val range = if (days == 1) "heute" else "die letzten ${plural(days, "Tag", "Tage")}"
            "Abgeglichen – Health Connect enthält keine Daten für $range."
        } else {
            "Abgeglichen: ${joinGerman(parts)}."
        }
        return if (partial) "$base Einige Daten konnten nicht gelesen werden." else base
    }

    fun plural(count: Int, one: String, many: String): String = if (count == 1) "1 $one" else "$count $many"

    /** "a", "a und b", "a, b und c" */
    fun joinGerman(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " und " + parts.last()
    }
}
