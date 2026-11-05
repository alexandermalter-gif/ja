package de.kalorienkompass.ui.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.health.HcAvailability
import de.kalorienkompass.health.HealthConnectManager
import de.kalorienkompass.health.HealthSyncResult
import de.kalorienkompass.health.HealthSyncService
import de.kalorienkompass.health.HealthWorkScheduler
import de.kalorienkompass.health.StepSensorTracker
import de.kalorienkompass.util.formatSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Device-related state that has to be queried (Health Connect status, permissions, sensor). */
data class HealthDeviceStatus(
    val loaded: Boolean = false,
    val availability: HcAvailability = HcAvailability.NOT_SUPPORTED,
    val grantedPermissions: Set<String> = emptySet(),
    val permissionsToRequest: Set<String> = emptySet(),
    val backgroundReadAvailable: Boolean = false,
    val sensorAvailable: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
)

enum class HcPermissionKind { STEPS, ACTIVE_CALORIES, READ_WEIGHT, WRITE_WEIGHT, BODY_FAT, BACKGROUND }

/** One row of the permission list. */
data class HcPermissionItem(val kind: HcPermissionKind, val label: String, val granted: Boolean) {
    val optional: Boolean get() = kind == HcPermissionKind.BACKGROUND
}

data class HealthConnectUiState(
    val device: HealthDeviceStatus = HealthDeviceStatus(),
    val settings: AppSettings = AppSettings(),
    val today: DailyActivityEntity? = null,
    val syncing: Boolean = false,
    val lastResult: HealthSyncResult? = null,
    val sensorBusy: Boolean = false,
    val permissionItems: List<HcPermissionItem> = emptyList(),
    val hasAnyReadPermission: Boolean = false,
    val canWriteWeight: Boolean = false,
    val backgroundGranted: Boolean = false,
) {
    val loading: Boolean get() = !device.loaded
    val hcAvailable: Boolean get() = device.availability == HcAvailability.AVAILABLE
    val missingRequired: Boolean get() = permissionItems.any { !it.granted && !it.optional }
}

enum class HealthMessageAction(val label: String) {
    OPEN_HC_PERMISSIONS("Öffnen"),
    OPEN_APP_SETTINGS("Einstellungen"),
    INSTALL_HC("Installieren"),
}

sealed interface HealthEvent {
    data class RequestHcPermissions(val permissions: Set<String>) : HealthEvent
    data object RequestActivityRecognition : HealthEvent
    data class Message(val text: String, val action: HealthMessageAction? = null) : HealthEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class HealthConnectViewModel(
    private val appContext: Context,
    private val hc: HealthConnectManager,
    private val settingsRepository: SettingsRepository,
    private val syncService: HealthSyncService,
    private val sensor: StepSensorTracker,
    activityRepository: ActivityRepository,
) : ViewModel() {

    private enum class Pending { NONE, ENABLE, WRITE_WEIGHT, GRANT }

    private val device = MutableStateFlow(HealthDeviceStatus())
    private val syncing = MutableStateFlow(false)
    private val sensorBusy = MutableStateFlow(false)
    private val lastResult = MutableStateFlow<HealthSyncResult?>(null)
    private val todayDate = MutableStateFlow(LocalDate.now())
    private var pending = Pending.NONE

    private val eventChannel = Channel<HealthEvent>(Channel.BUFFERED)
    val events: Flow<HealthEvent> = eventChannel.receiveAsFlow()

    private val todayActivity: Flow<DailyActivityEntity?> =
        todayDate.flatMapLatest { activityRepository.observeDay(it) }

    private val busyFlags: Flow<Triple<Boolean, Boolean, HealthSyncResult?>> =
        combine(syncing, sensorBusy, lastResult) { s, b, r -> Triple(s, b, r) }

    val uiState: StateFlow<HealthConnectUiState> = combine(
        device,
        settingsRepository.settings,
        todayActivity,
        busyFlags,
    ) { device, settings, today, (isSyncing, isSensorBusy, result) ->
        val granted = device.grantedPermissions
        val available = device.availability == HcAvailability.AVAILABLE
        HealthConnectUiState(
            device = device,
            settings = settings,
            today = today,
            syncing = isSyncing,
            lastResult = result,
            sensorBusy = isSensorBusy,
            permissionItems = if (available) permissionItems(device) else emptyList(),
            hasAnyReadPermission = available && granted.any { it in hc.readPermissions },
            canWriteWeight = available && hc.writeWeightPermission in granted,
            backgroundGranted = available && hc.backgroundReadPermission in granted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthConnectUiState())

    init {
        refresh()
    }

    /** Contract for `rememberLauncherForActivityResult` (Health Connect permission dialog). */
    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> = hc.permissionRequestContract()

    /** Re-reads Health Connect status and permissions (called on every resume of the screen). */
    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow(): HealthDeviceStatus {
        todayDate.value = LocalDate.now()
        val status = withContext(Dispatchers.IO) {
            val availability = hc.availability()
            val available = availability == HcAvailability.AVAILABLE
            HealthDeviceStatus(
                loaded = true,
                availability = availability,
                grantedPermissions = if (available) hc.grantedPermissions() else emptySet(),
                permissionsToRequest = if (available) hc.permissionsToRequest() else emptySet(),
                backgroundReadAvailable = available && hc.isBackgroundReadAvailable(),
                sensorAvailable = sensor.isSensorAvailable(),
                activityRecognitionGranted = sensor.hasActivityRecognitionPermission(),
            )
        }
        device.value = status
        return status
    }

    // ------------------------------------------------------------------ Health Connect

    fun onHealthConnectSwitch(enable: Boolean) {
        viewModelScope.launch {
            if (!enable) {
                settingsRepository.updateSettings { it.copy(healthConnectEnabled = false) }
                HealthWorkScheduler.schedule(appContext)
                message("Health Connect deaktiviert. Bereits importierte Werte bleiben erhalten.")
                return@launch
            }
            val status = refreshNow()
            when (status.availability) {
                HcAvailability.AVAILABLE -> Unit
                HcAvailability.NOT_INSTALLED -> {
                    message("Bitte installiere zuerst Health Connect.", HealthMessageAction.INSTALL_HC)
                    return@launch
                }
                HcAvailability.UPDATE_REQUIRED -> {
                    message("Bitte aktualisiere zuerst Health Connect.", HealthMessageAction.INSTALL_HC)
                    return@launch
                }
                HcAvailability.NOT_SUPPORTED -> {
                    message("Health Connect wird auf diesem Gerät nicht unterstützt.")
                    return@launch
                }
            }
            if (status.grantedPermissions.any { it in hc.readPermissions }) {
                enableAndSync()
            } else {
                pending = Pending.ENABLE
                eventChannel.send(HealthEvent.RequestHcPermissions(status.permissionsToRequest))
            }
        }
    }

    /** "Berechtigungen erteilen" button: asks for all missing permissions. */
    fun requestMissingPermissions() {
        viewModelScope.launch {
            val status = refreshNow()
            if (status.availability != HcAvailability.AVAILABLE) return@launch
            pending = Pending.GRANT
            eventChannel.send(HealthEvent.RequestHcPermissions(status.permissionsToRequest))
        }
    }

    fun onWriteWeightSwitch(enable: Boolean) {
        viewModelScope.launch {
            if (!enable) {
                settingsRepository.updateSettings { it.copy(writeWeightToHealthConnect = false) }
                return@launch
            }
            val status = refreshNow()
            if (status.availability != HcAvailability.AVAILABLE) {
                message("Health Connect ist nicht verfügbar.")
                return@launch
            }
            if (hc.writeWeightPermission in status.grantedPermissions) {
                settingsRepository.updateSettings { it.copy(writeWeightToHealthConnect = true) }
                message("Neue Gewichtseinträge werden jetzt an Health Connect gesendet.")
            } else {
                pending = Pending.WRITE_WEIGHT
                eventChannel.send(HealthEvent.RequestHcPermissions(setOf(hc.writeWeightPermission)))
            }
        }
    }

    /**
     * The Health Connect permission dialog was closed. The granted set is re-read from Health Connect
     * (the dialog result only contains the permissions of this one request).
     */
    fun onHcPermissionsResult() {
        viewModelScope.launch {
            val action = pending
            pending = Pending.NONE
            val status = refreshNow()
            val hasRead = status.grantedPermissions.any { it in hc.readPermissions }
            when (action) {
                Pending.ENABLE -> if (hasRead) {
                    enableAndSync()
                } else {
                    message(
                        "Ohne Berechtigungen kann KalorienKompass keine Daten aus Health Connect lesen.",
                        HealthMessageAction.OPEN_HC_PERMISSIONS,
                    )
                }

                Pending.WRITE_WEIGHT -> if (hc.writeWeightPermission in status.grantedPermissions) {
                    settingsRepository.updateSettings { it.copy(writeWeightToHealthConnect = true) }
                    message("Neue Gewichtseinträge werden jetzt an Health Connect gesendet.")
                } else {
                    message(
                        "Ohne Schreibberechtigung kann das Gewicht nicht gesendet werden.",
                        HealthMessageAction.OPEN_HC_PERMISSIONS,
                    )
                }

                Pending.GRANT, Pending.NONE -> when {
                    !hasRead -> message(
                        "Es wurden keine Leseberechtigungen erteilt.",
                        HealthMessageAction.OPEN_HC_PERMISSIONS,
                    )
                    settingsRepository.settings.first().healthConnectEnabled -> syncNow()
                    else -> message("Berechtigungen erteilt. Aktiviere „Health Connect verwenden“, um Daten zu übernehmen.")
                }
            }
        }
    }

    /** The permission dialog could not be opened (Health Connect missing or broken). */
    fun onPermissionRequestFailed() {
        pending = Pending.NONE
        viewModelScope.launch {
            message(
                "Der Berechtigungsdialog von Health Connect konnte nicht geöffnet werden.",
                HealthMessageAction.OPEN_HC_PERMISSIONS,
            )
        }
    }

    fun syncNow() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            try {
                lastResult.value = syncService.syncRecent(30)
            } finally {
                syncing.value = false
            }
        }
    }

    private suspend fun enableAndSync() {
        settingsRepository.updateSettings { it.copy(healthConnectEnabled = true) }
        HealthWorkScheduler.schedule(appContext)
        syncNow()
    }

    // ------------------------------------------------------------------ step sensor

    fun onStepSensorSwitch(enable: Boolean) {
        viewModelScope.launch {
            if (!enable) {
                settingsRepository.updateSettings { it.copy(stepSensorEnabled = false) }
                sensor.resetState()
                HealthWorkScheduler.schedule(appContext)
                message("Schrittsensor deaktiviert.")
                return@launch
            }
            val status = refreshNow()
            when {
                !status.sensorAvailable -> message("Dein Gerät hat keinen Schrittzähler-Sensor.")
                !status.activityRecognitionGranted -> eventChannel.send(HealthEvent.RequestActivityRecognition)
                else -> enableSensor()
            }
        }
    }

    fun onActivityRecognitionResult(granted: Boolean, permanentlyDenied: Boolean) {
        viewModelScope.launch {
            refreshNow()
            when {
                granted -> enableSensor()
                permanentlyDenied -> message(
                    "Bitte erlaube „Körperliche Aktivitäten“ in den App-Einstellungen, um den Schrittsensor zu nutzen.",
                    HealthMessageAction.OPEN_APP_SETTINGS,
                )
                else -> message(
                    "Ohne die Berechtigung „Körperliche Aktivitäten“ kann der Schrittsensor nicht gelesen werden.",
                )
            }
        }
    }

    /** "Jetzt lesen" for the step sensor. */
    fun readSensorNow() {
        if (sensorBusy.value) return
        sensorBusy.value = true
        viewModelScope.launch {
            try {
                val steps = sensor.readNow()
                val todaySource = activitySourceToday()
                when {
                    steps == null ->
                        message("Der Schrittsensor hat keinen Wert geliefert. Bitte später erneut versuchen.")
                    todaySource != null && todaySource != DataSource.SENSOR ->
                        message("Sensor gelesen. Für heute zählen die Schritte aus „${todaySource.label}“.")
                    else -> message("Heute bisher ${formatSteps(steps)} Schritte laut Schrittsensor.")
                }
            } finally {
                sensorBusy.value = false
            }
        }
    }

    private suspend fun enableSensor() {
        settingsRepository.updateSettings { it.copy(stepSensorEnabled = true) }
        HealthWorkScheduler.schedule(appContext)
        sensorBusy.value = true
        try {
            val steps = sensor.readNow()
            when {
                steps == null -> message("Schrittsensor aktiviert. Der Sensor hat noch keinen Wert geliefert.")
                steps == 0L -> message("Schrittsensor aktiviert – ab jetzt werden deine Schritte gezählt.")
                else -> message("Schrittsensor aktiviert – heute bisher ${formatSteps(steps)} Schritte.")
            }
        } finally {
            sensorBusy.value = false
        }
    }

    private fun activitySourceToday(): DataSource? = uiState.value.today?.source

    // ------------------------------------------------------------------ helpers

    private suspend fun message(text: String, action: HealthMessageAction? = null) {
        eventChannel.send(HealthEvent.Message(text, action))
    }

    private fun permissionItems(device: HealthDeviceStatus): List<HcPermissionItem> {
        val granted = device.grantedPermissions
        val items = mutableListOf(
            HcPermissionItem(HcPermissionKind.STEPS, "Schritte lesen", hc.readStepsPermission in granted),
            HcPermissionItem(
                HcPermissionKind.ACTIVE_CALORIES,
                "Aktive Kalorien lesen",
                hc.readActiveCaloriesPermission in granted,
            ),
            HcPermissionItem(HcPermissionKind.READ_WEIGHT, "Gewicht lesen", hc.readWeightPermission in granted),
            HcPermissionItem(HcPermissionKind.WRITE_WEIGHT, "Gewicht schreiben", hc.writeWeightPermission in granted),
            HcPermissionItem(HcPermissionKind.BODY_FAT, "Körperfett lesen", hc.readBodyFatPermission in granted),
        )
        if (device.backgroundReadAvailable) {
            items += HcPermissionItem(
                HcPermissionKind.BACKGROUND,
                "Im Hintergrund lesen (optional)",
                hc.backgroundReadPermission in granted,
            )
        }
        return items
    }
}
