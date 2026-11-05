package de.kalorienkompass.health

import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.SettingsRepository

data class HealthSyncResult(val success: Boolean, val message: String)

/**
 * STUB (owned by HEALTH): imports steps, active calories, weight and body fat from Health Connect.
 * TODO(HEALTH): implement (see spec §11 HEALTH).
 */
class HealthSyncService(
    private val hc: HealthConnectManager,
    private val settings: SettingsRepository,
    private val activity: ActivityRepository,
    private val measurements: MeasurementRepository,
) {
    suspend fun syncRecent(days: Int = 30): HealthSyncResult =
        HealthSyncResult(success = false, message = "Health Connect ist noch nicht eingerichtet.")
}
