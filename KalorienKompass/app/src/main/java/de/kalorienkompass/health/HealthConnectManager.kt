package de.kalorienkompass.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContract
import java.time.LocalDate

enum class HcAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED, NOT_SUPPORTED }

data class HcMeasurement(val id: String, val date: LocalDate, val value: Double)

/**
 * STUB (owned by HEALTH): wrapper around the Health Connect client.
 * TODO(HEALTH): implement with androidx.health.connect:connect-client (see spec §11 HEALTH).
 */
class HealthConnectManager(context: Context) {

    private val appContext: Context = context.applicationContext

    fun availability(): HcAvailability = HcAvailability.NOT_SUPPORTED

    val requiredPermissions: Set<String> = setOf(
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
        "android.permission.health.READ_WEIGHT",
        "android.permission.health.WRITE_WEIGHT",
        "android.permission.health.READ_BODY_FAT",
    )

    suspend fun hasAllPermissions(): Boolean = false

    /** TODO(HEALTH): PermissionController.createRequestPermissionResultContract() */
    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        object : ActivityResultContract<Set<String>, Set<String>>() {
            override fun createIntent(context: Context, input: Set<String>): Intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

            override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
        }

    suspend fun readDailySteps(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = emptyMap()

    suspend fun readDailyActiveCalories(from: LocalDate, to: LocalDate): Map<LocalDate, Double> = emptyMap()

    suspend fun readWeights(from: LocalDate, to: LocalDate): List<HcMeasurement> = emptyList()

    suspend fun readBodyFat(from: LocalDate, to: LocalDate): List<HcMeasurement> = emptyList()

    suspend fun writeWeight(date: LocalDate, kg: Double) {
        // TODO(HEALTH): insert a WeightRecord
    }
}
