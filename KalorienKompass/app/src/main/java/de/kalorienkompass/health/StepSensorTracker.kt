package de.kalorienkompass.health

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Fallback step source: the phone's step counter sensor (TYPE_STEP_COUNTER, cumulative since boot).
 *
 * Each reading registers a listener, waits for the first event (max. 5 s) and unregisters again.
 * The baseline is persisted in the SharedPreferences file "step_sensor" (see [StepCounterMath]).
 * Requires ACTIVITY_RECOGNITION on Android 10+ (without it nothing happens).
 */
class StepSensorTracker(
    context: Context,
    private val activity: ActivityRepository,
    private val settings: SettingsRepository,
) {
    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSensorAvailable(): Boolean {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    /** ACTIVITY_RECOGNITION ("Körperliche Aktivitäten") is a runtime permission on Android 10+. */
    fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /** Reads the sensor and stores today's steps if the step sensor is enabled in the settings. Never throws. */
    suspend fun readAndStore() {
        val enabled = try {
            settings.settings.first().stepSensorEnabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (enabled) readNow()
    }

    /**
     * Reads the sensor now and stores the result (regardless of the setting).
     * @return today's steps, or null if the sensor/permission is missing or delivered no value.
     */
    suspend fun readNow(): Long? = mutex.withLock {
        try {
            if (!isSensorAvailable() || !hasActivityRecognitionPermission()) return@withLock null
            val counter = readCounter() ?: return@withLock null
            withContext(Dispatchers.IO) { store(counter) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading the step counter failed", e)
            null
        }
    }

    /** Forgets the baseline (when the user disables the sensor), so a later activation starts fresh. */
    suspend fun resetState() {
        withContext(Dispatchers.IO) {
            try {
                prefs.edit().clear().apply()
            } catch (e: Exception) {
                Log.w(TAG, "Could not reset step sensor state", e)
            }
        }
    }

    private suspend fun store(counter: Long): Long {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val existingToday = activity.observeDay(today).first()
            ?.takeIf { it.source == DataSource.SENSOR }
            ?.steps
        val update = StepCounterMath.update(
            previous = loadState(),
            counter = counter,
            bootCount = bootCount(),
            today = today,
            nowMillis = now,
            bootTimeMillis = now - SystemClock.elapsedRealtime(),
            zone = zone,
            existingTodaySteps = existingToday,
        )
        saveState(update.state)
        val previousDate = update.previousDate
        val previousSteps = update.previousDateSteps
        if (previousDate != null && previousSteps != null) {
            activity.upsertFromSensor(previousDate, previousSteps)
        }
        activity.upsertFromSensor(today, update.todaySteps)
        return update.todaySteps
    }

    private suspend fun readCounter(): Long? {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine<Long?> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val value = event.values.firstOrNull() ?: return
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(value.toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                val registered = try {
                    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                } catch (e: Exception) {
                    Log.w(TAG, "registerListener failed", e)
                    false
                }
                if (!registered) {
                    sm.unregisterListener(listener)
                    if (cont.isActive) cont.resume(null)
                } else {
                    cont.invokeOnCancellation { sm.unregisterListener(listener) }
                }
            }
        }
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    private fun loadState(): StepSensorState? {
        val epochDay = prefs.getLong(KEY_BASELINE_DATE, Long.MIN_VALUE)
        if (epochDay == Long.MIN_VALUE) return null
        return StepSensorState(
            baselineDate = LocalDate.ofEpochDay(epochDay),
            baselineCounter = prefs.getLong(KEY_BASELINE_COUNTER, 0L),
            lastCounter = prefs.getLong(KEY_LAST_COUNTER, 0L),
            lastReadAtMillis = prefs.getLong(KEY_LAST_READ_AT, 0L),
            bootCount = prefs.getInt(KEY_BOOT_COUNT, -1),
        )
    }

    private fun saveState(state: StepSensorState) {
        prefs.edit()
            .putLong(KEY_BASELINE_DATE, state.baselineDate.toEpochDay())
            .putLong(KEY_BASELINE_COUNTER, state.baselineCounter)
            .putLong(KEY_LAST_COUNTER, state.lastCounter)
            .putLong(KEY_LAST_READ_AT, state.lastReadAtMillis)
            .putInt(KEY_BOOT_COUNT, state.bootCount)
            .apply()
    }

    private companion object {
        const val TAG = "StepSensorTracker"
        const val PREFS_NAME = "step_sensor"
        const val KEY_BASELINE_DATE = "baseline_date"
        const val KEY_BASELINE_COUNTER = "baseline_counter"
        const val KEY_LAST_COUNTER = "last_counter"
        const val KEY_LAST_READ_AT = "last_read_at"
        const val KEY_BOOT_COUNT = "boot_count"
        const val SENSOR_TIMEOUT_MS = 5_000L
    }
}
