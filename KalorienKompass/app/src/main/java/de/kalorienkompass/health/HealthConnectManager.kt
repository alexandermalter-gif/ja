package de.kalorienkompass.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

enum class HcAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED, NOT_SUPPORTED }

/**
 * A body value read from Health Connect.
 *
 * @param id Health Connect record id (stored as `externalId`)
 * @param sourcePackage package of the app that wrote the record (e.g. Honor Health); records written by
 *   KalorienKompass itself are never returned by [HealthConnectManager].
 */
data class HcMeasurement(
    val id: String,
    val date: LocalDate,
    val value: Double,
    val sourcePackage: String = "",
)

/**
 * Thin wrapper around the Health Connect client (androidx.health.connect:connect-client 1.1.0-alpha10).
 *
 * The public read/write functions never throw (except [CancellationException]): on errors they log and
 * return empty results. [HealthSyncService] uses the internal `fetch…` variants, which throw, so that it
 * can report meaningful German error messages.
 */
class HealthConnectManager(context: Context) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var cachedClient: HealthConnectClient? = null

    /** Whether Health Connect can be used on this device (cheap PackageManager queries; call off the main thread). */
    fun availability(): HcAvailability {
        val status = try {
            HealthConnectClient.getSdkStatus(appContext, PROVIDER_PACKAGE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "getSdkStatus failed", e)
            return HcAvailability.NOT_SUPPORTED
        }
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                if (isProviderPackageInstalled()) HcAvailability.UPDATE_REQUIRED else HcAvailability.NOT_INSTALLED
            else -> HcAvailability.NOT_SUPPORTED
        }
    }

    /** Data permissions used by the app (the background permission is optional, see [permissionsToRequest]). */
    val requiredPermissions: Set<String> by lazy {
        setOf(
            readStepsPermission,
            readActiveCaloriesPermission,
            readWeightPermission,
            writeWeightPermission,
            readBodyFatPermission,
        )
    }

    val readStepsPermission: String get() = HealthPermission.getReadPermission(StepsRecord::class)
    val readActiveCaloriesPermission: String get() = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    val readWeightPermission: String get() = HealthPermission.getReadPermission(WeightRecord::class)
    val writeWeightPermission: String get() = HealthPermission.getWritePermission(WeightRecord::class)
    val readBodyFatPermission: String get() = HealthPermission.getReadPermission(BodyFatRecord::class)

    /** Allows the hourly [HealthSyncWorker] to read while the app is in the background. */
    val backgroundReadPermission: String get() = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** All read permissions (any one of them is enough for a useful sync). */
    val readPermissions: Set<String>
        get() = setOf(readStepsPermission, readActiveCaloriesPermission, readWeightPermission, readBodyFatPermission)

    /** True if this Health Connect version supports reading in the background (Android 14+ / recent APK). */
    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    fun isBackgroundReadAvailable(): Boolean {
        val client = clientOrNull() ?: return false
        return try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "Feature check failed", e)
            false
        }
    }

    /** [requiredPermissions] plus the background permission where Health Connect supports it. */
    fun permissionsToRequest(): Set<String> =
        if (isBackgroundReadAvailable()) requiredPermissions + backgroundReadPermission else requiredPermissions

    /** Currently granted Health Connect permissions (empty if unavailable or on errors). */
    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return try {
            withContext(Dispatchers.IO) { client.permissionController.getGrantedPermissions() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions failed", e)
            emptySet()
        }
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(requiredPermissions)

    /** Use with `rememberLauncherForActivityResult`; input = permissions to request, output = granted ones. */
    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    // ---------------------------------------------------------------- reading (never throw)

    suspend fun readDailySteps(from: LocalDate, to: LocalDate): Map<LocalDate, Long> =
        safely(emptyMap(), "readDailySteps") { fetchDailySteps(from, to) }

    suspend fun readDailyActiveCalories(from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        safely(emptyMap(), "readDailyActiveCalories") { fetchDailyActiveCalories(from, to) }

    suspend fun readWeights(from: LocalDate, to: LocalDate): List<HcMeasurement> =
        safely(emptyList(), "readWeights") { fetchWeights(from, to) }

    suspend fun readBodyFat(from: LocalDate, to: LocalDate): List<HcMeasurement> =
        safely(emptyList(), "readBodyFat") { fetchBodyFat(from, to) }

    /**
     * Writes a manually entered weight as [WeightRecord] (08:00 local time of [date], or "now" for an
     * earlier time today). The client record id "kk-weight-<epochDay>" makes repeated exports of the same
     * day replace (upsert) one record instead of creating duplicates. Errors are logged, never thrown.
     */
    suspend fun writeWeight(date: LocalDate, kg: Double) {
        safely(false, "writeWeight") { insertWeight(date, kg) }
    }

    // ---------------------------------------------------------------- internal (throwing) variants

    internal suspend fun fetchDailySteps(from: LocalDate, to: LocalDate): Map<LocalDate, Long> =
        aggregateDaily(from, to, StepsRecord.COUNT_TOTAL) { it }

    internal suspend fun fetchDailyActiveCalories(from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        aggregateDaily(from, to, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it.inKilocalories }

    internal suspend fun fetchWeights(from: LocalDate, to: LocalDate): List<HcMeasurement> =
        readAll(WeightRecord::class, from, to).mapNotNull { record ->
            val origin = record.metadata.dataOrigin.packageName
            if (origin == appContext.packageName) return@mapNotNull null   // our own exports
            val kg = record.weight.inKilograms
            if (kg.isNaN() || kg <= 0.0) return@mapNotNull null
            HcMeasurement(
                id = record.metadata.id,
                date = localDateOf(record.time, record.zoneOffset),
                value = kg,
                sourcePackage = origin,
            )
        }

    internal suspend fun fetchBodyFat(from: LocalDate, to: LocalDate): List<HcMeasurement> =
        readAll(BodyFatRecord::class, from, to).mapNotNull { record ->
            val origin = record.metadata.dataOrigin.packageName
            if (origin == appContext.packageName) return@mapNotNull null
            val percent = record.percentage.value
            if (percent.isNaN() || percent <= 0.0) return@mapNotNull null
            HcMeasurement(
                id = record.metadata.id,
                date = localDateOf(record.time, record.zoneOffset),
                value = percent,
                sourcePackage = origin,
            )
        }

    /** @return true if a record was written, false if not possible (no permission, invalid value, …). */
    internal suspend fun insertWeight(date: LocalDate, kg: Double): Boolean {
        if (kg.isNaN() || kg <= 0.0 || kg > MAX_WEIGHT_KG) return false
        val client = clientOrNull() ?: return false
        if (writeWeightPermission !in grantedPermissions()) {
            Log.i(TAG, "Weight not exported: WRITE_WEIGHT not granted")
            return false
        }
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        if (date.isAfter(LocalDate.now(zone))) return false
        var time = date.atTime(EXPORT_HOUR, 0).atZone(zone).toInstant()
        if (time.isAfter(now)) time = now.minusSeconds(1)
        val record = WeightRecord(
            time = time,
            zoneOffset = zone.rules.getOffset(time),
            weight = Mass.kilograms(kg),
            metadata = Metadata(
                clientRecordId = weightClientRecordId(date),
                // Must grow with every export so that Health Connect replaces the existing record.
                clientRecordVersion = System.currentTimeMillis(),
                recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY,
            ),
        )
        withContext(Dispatchers.IO) { client.insertRecords(listOf(record)) }
        return true
    }

    /** Throws [IllegalStateException] if Health Connect is not available. */
    private fun requireClient(): HealthConnectClient =
        clientOrNull() ?: throw IllegalStateException("Health Connect ist nicht verfügbar.")

    private fun clientOrNull(): HealthConnectClient? {
        cachedClient?.let { return it }
        if (availability() != HcAvailability.AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(appContext, PROVIDER_PACKAGE_NAME).also { cachedClient = it }
        } catch (e: Exception) {
            Log.w(TAG, "HealthConnectClient.getOrCreate failed", e)
            null
        }
    }

    private suspend fun <T : Any, R : Any> aggregateDaily(
        from: LocalDate,
        to: LocalDate,
        metric: AggregateMetric<T>,
        convert: (T) -> R,
    ): Map<LocalDate, R> {
        if (to.isBefore(from)) return emptyMap()
        val client = requireClient()
        val groups = withContext(Dispatchers.IO) {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(metric),
                    timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )
        }
        val result = LinkedHashMap<LocalDate, R>()
        for (group in groups) {
            val value = group.result[metric] ?: continue
            result[group.startTime.toLocalDate()] = convert(value)
        }
        return result
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, from: LocalDate, to: LocalDate): List<T> {
        if (to.isBefore(from)) return emptyList()
        val client = requireClient()
        val zone = ZoneId.systemDefault()
        val filter = TimeRangeFilter.between(
            from.atStartOfDay(zone).toInstant(),
            to.plusDays(1).atStartOfDay(zone).toInstant(),
        )
        return withContext(Dispatchers.IO) {
            val records = ArrayList<T>()
            var pageToken: String? = null
            var pages = 0
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageToken = pageToken),
                )
                records += response.records
                pageToken = response.pageToken
                pages++
            } while (!pageToken.isNullOrEmpty() && pages < MAX_PAGES)
            records
        }
    }

    private suspend fun <T> safely(fallback: T, what: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // SecurityException (permission revoked / background read not allowed), RemoteException,
            // IOException, IllegalStateException (service unavailable), IllegalArgumentException …
            Log.w(TAG, "$what failed", e)
            fallback
        }

    private fun isProviderPackageInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(PROVIDER_PACKAGE_NAME, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val TAG = "HealthConnectManager"

        /** Health Connect app (Android 13 and lower); on Android 14+ Health Connect is part of the system. */
        const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"

        /** Play Store page that also starts the Health Connect onboarding after installation. */
        const val PLAY_STORE_URI =
            "market://details?id=$PROVIDER_PACKAGE_NAME&url=healthconnect%3A%2F%2Fonboarding"
        const val PLAY_STORE_WEB_URL =
            "https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE_NAME&url=healthconnect%3A%2F%2Fonboarding"

        private const val ACTION_MANAGE_HEALTH_PERMISSIONS = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"

        private const val EXPORT_HOUR = 8
        private const val MAX_WEIGHT_KG = 1000.0
        private const val MAX_PAGES = 50

        /** Client record id of the exported weight of [date] (one record per day, upserted). */
        fun weightClientRecordId(date: LocalDate): String = "kk-weight-${date.toEpochDay()}"

        /** Opens the Health Connect home screen (settings). */
        fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

        /**
         * Opens the Health Connect permission page of this app (Android 14+), otherwise the Health Connect
         * settings where the user can pick "App-Berechtigungen".
         */
        fun managePermissionsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(ACTION_MANAGE_HEALTH_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            } else {
                settingsIntent()
            }

        /** Local date of an instantaneous record (uses the record's own offset if present). */
        internal fun localDateOf(time: Instant, offset: ZoneOffset?): LocalDate =
            if (offset != null) time.atOffset(offset).toLocalDate() else time.atZone(ZoneId.systemDefault()).toLocalDate()

        /** German, user-facing description of a Health Connect error. */
        internal fun describeError(e: Throwable): String {
            val text = (e.message ?: "").lowercase()
            return when {
                text.contains("rate limit") || text.contains("quota") ->
                    "Zu viele Anfragen an Health Connect. Bitte in ein paar Minuten erneut versuchen."
                e is SecurityException ->
                    "Health Connect hat den Zugriff verweigert. Bitte prüfe die Berechtigungen."
                e is RemoteException || e is IOException || e is IllegalStateException ->
                    "Health Connect ist gerade nicht erreichbar. Bitte später erneut versuchen."
                else -> "Die Daten aus Health Connect konnten nicht gelesen werden."
            }
        }
    }
}
