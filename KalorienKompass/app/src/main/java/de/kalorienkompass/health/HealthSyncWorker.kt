package de.kalorienkompass.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.kalorienkompass.di.appContainer
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hourly background job (scheduled by [HealthWorkScheduler]): imports the last 3 days from Health Connect
 * and reads the phone's step counter. Always reports success – a failed run is simply repeated next hour.
 */
class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = try {
            applicationContext.appContainer
        } catch (e: Exception) {
            Log.w(TAG, "App container not available", e)
            return Result.success()
        }
        val settings = try {
            container.settingsRepository.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Settings not readable", e)
            return Result.success()
        }

        if (settings.healthConnectEnabled) {
            try {
                if (canReadInBackground(container.healthConnectManager)) {
                    val result = container.healthSyncService.syncRecent(3)
                    Log.i(TAG, "Health Connect sync: ${result.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background Health Connect sync failed", e)
            }
        }

        if (settings.stepSensorEnabled) {
            try {
                container.stepSensorTracker.readAndStore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background step sensor reading failed", e)
            }
        }
        return Result.success()
    }

    /**
     * Health Connect versions with the background-read feature require the extra permission; older
     * versions are simply tried (errors are logged and ignored).
     */
    private suspend fun canReadInBackground(hc: HealthConnectManager): Boolean {
        if (hc.availability() != HcAvailability.AVAILABLE) return false
        if (!hc.isBackgroundReadAvailable()) return true
        return hc.backgroundReadPermission in hc.grantedPermissions()
    }

    private companion object {
        const val TAG = "HealthSyncWorker"
    }
}
