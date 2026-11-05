package de.kalorienkompass.health

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.kalorienkompass.di.appContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Schedules the hourly [HealthSyncWorker] as unique periodic work ([ExistingPeriodicWorkPolicy.UPDATE],
 * so an app start never resets the schedule).
 *
 * Called from `Application.onCreate` and again whenever the user changes the Health Connect or
 * step-sensor switches. When neither source is enabled, the work is removed again (checked
 * asynchronously, the settings live in DataStore) so the app is not woken up every hour for nothing.
 * Never throws and never blocks the caller.
 */
object HealthWorkScheduler {

    const val UNIQUE_WORK_NAME = "kalorienkompass_health_sync"
    private const val TAG = "HealthWorkScheduler"
    private const val INTERVAL_HOURS = 1L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Health sync scheduling failed", e)
        },
    )

    /** Guards [generation]: only the check of the latest [schedule] call may remove the work. */
    private val lock = Any()
    private var generation = 0L

    fun schedule(context: Context) {
        try {
            val appContext = context.applicationContext ?: context
            val myGeneration = synchronized(lock) {
                enqueue(appContext)
                ++generation
            }
            scope.launch {
                val settings = try {
                    appContext.appContainer.settingsRepository.settings.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Settings not available", e)
                    return@launch
                }
                if (!settings.healthConnectEnabled && !settings.stepSensorEnabled) {
                    synchronized(lock) {
                        // A newer schedule() call (settings changed again) decides instead.
                        if (myGeneration == generation) cancel(appContext)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not schedule health sync", e)
        }
    }

    /** Removes the periodic work. Never throws. */
    fun cancel(context: Context) {
        try {
            workManager(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not cancel health sync", e)
        }
    }

    private fun enqueue(context: Context) {
        try {
            val workManager = workManager(context) ?: return
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not enqueue health sync", e)
        }
    }

    /** WorkManager.getInstance throws IllegalStateException if WorkManager is not initialized. */
    private fun workManager(context: Context): WorkManager? = try {
        WorkManager.getInstance(context.applicationContext ?: context)
    } catch (e: Throwable) {
        Log.w(TAG, "WorkManager not available", e)
        null
    }
}
