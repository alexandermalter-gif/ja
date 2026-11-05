package de.kalorienkompass.health

import de.kalorienkompass.data.repo.HealthDataExporter
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * STUB (owned by HEALTH): sends manually entered weights to Health Connect if enabled.
 */
class HealthWeightExporter(
    private val hc: HealthConnectManager,
    private val settings: SettingsRepository,
) : HealthDataExporter {

    override suspend fun exportWeight(date: LocalDate, kg: Double) {
        val s = settings.settings.first()
        if (s.healthConnectEnabled && s.writeWeightToHealthConnect) {
            hc.writeWeight(date, kg)
        }
    }
}
