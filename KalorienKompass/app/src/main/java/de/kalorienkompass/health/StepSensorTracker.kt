package de.kalorienkompass.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.settings.SettingsRepository

/**
 * STUB (owned by HEALTH): reads the phone's step counter sensor (TYPE_STEP_COUNTER).
 * TODO(HEALTH): implement baseline handling and storage (see spec §11 HEALTH).
 */
class StepSensorTracker(
    context: Context,
    private val activity: ActivityRepository,
    private val settings: SettingsRepository,
) {
    private val appContext: Context = context.applicationContext

    fun isSensorAvailable(): Boolean {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    suspend fun readAndStore() {
        // TODO(HEALTH)
    }
}
