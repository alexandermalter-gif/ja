package de.kalorienkompass.health

import android.util.Log
import de.kalorienkompass.data.repo.HealthDataExporter
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends manually entered weights to Health Connect when the user enabled both
 * "Health Connect verwenden" and "Gewicht an Health Connect senden".
 *
 * Repeated exports of the same day replace one Health Connect record (client record id per day),
 * so the +/- buttons on the dashboard never create duplicates. Never throws (except cancellation).
 */
class HealthWeightExporter(
    private val hc: HealthConnectManager,
    private val settings: SettingsRepository,
) : HealthDataExporter {

    override suspend fun exportWeight(date: LocalDate, kg: Double) {
        try {
            val s = settings.settings.first()
            if (!s.healthConnectEnabled || !s.writeWeightToHealthConnect) return
            withContext(Dispatchers.IO) {
                if (hc.availability() == HcAvailability.AVAILABLE) {
                    hc.writeWeight(date, kg)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Weight export failed", e)
        }
    }

    private companion object {
        const val TAG = "HealthWeightExporter"
    }
}
