package de.kalorienkompass.ui.health

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.health.HcAvailability
import de.kalorienkompass.health.HealthConnectManager
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatDateTime
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.formatSteps
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectScreen(navController: NavController) {
    val vm: HealthConnectViewModel = viewModel(
        factory = appViewModelFactory {
            HealthConnectViewModel(
                appContext = it.context,
                hc = it.healthConnectManager,
                settingsRepository = it.settingsRepository,
                syncService = it.healthSyncService,
                sensor = it.stepSensorTracker,
                activityRepository = it.activityRepository,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val hcContract = remember(vm) { vm.permissionRequestContract() }
    val hcPermissionLauncher = rememberLauncherForActivityResult(hcContract) { vm.onHcPermissionsResult() }
    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val activity = context.findActivity()
        val permanentlyDenied = !granted && activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACTIVITY_RECOGNITION)
        vm.onActivityRecognitionResult(granted, permanentlyDenied)
    }

    fun showMessage(text: String, action: HealthMessageAction? = null) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = text,
                actionLabel = action?.label,
                withDismissAction = action != null,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && action != null) {
                val opened = when (action) {
                    HealthMessageAction.OPEN_HC_PERMISSIONS -> openHealthConnectPermissions(context)
                    HealthMessageAction.OPEN_APP_SETTINGS -> openAppSettings(context)
                    HealthMessageAction.INSTALL_HC -> openHealthConnectInStore(context)
                }
                if (!opened) {
                    snackbarHostState.showSnackbar(
                        if (action == HealthMessageAction.INSTALL_HC) {
                            "Der Play Store konnte nicht geöffnet werden."
                        } else {
                            "Die Einstellungen konnten nicht geöffnet werden."
                        },
                    )
                }
            }
        }
    }

    // Status and permissions can change outside the app (Play Store, Health Connect, system settings).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is HealthEvent.RequestHcPermissions -> try {
                    hcPermissionLauncher.launch(event.permissions)
                } catch (e: ActivityNotFoundException) {
                    vm.onPermissionRequestFailed()
                } catch (e: IllegalStateException) {
                    vm.onPermissionRequestFailed()
                }

                HealthEvent.RequestActivityRecognition ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        vm.onActivityRecognitionResult(granted = true, permanentlyDenied = false)
                    }

                is HealthEvent.Message -> showMessage(event.text, event.action)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Health Connect & Schritte") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "status") {
                StatusCard(
                    state = state,
                    onToggle = vm::onHealthConnectSwitch,
                    onInstall = {
                        if (!openHealthConnectInStore(context)) showMessage("Der Play Store konnte nicht geöffnet werden.")
                    },
                )
            }
            if (state.hcAvailable) {
                item(key = "permissions") {
                    PermissionsCard(
                        state = state,
                        onRequest = vm::requestMissingPermissions,
                        onManage = {
                            if (!openHealthConnectPermissions(context)) {
                                showMessage("Health Connect konnte nicht geöffnet werden.")
                            }
                        },
                    )
                }
            }
            if (state.hcAvailable && state.settings.healthConnectEnabled) {
                item(key = "sync") {
                    SyncCard(
                        state = state,
                        onSync = vm::syncNow,
                        onWriteWeight = vm::onWriteWeightSwitch,
                    )
                }
            }
            item(key = "honor") {
                HonorHealthGuideCard(
                    onOpenHonorHealth = {
                        if (!openHonorHealth(context)) showMessage("Honor Health wurde auf diesem Gerät nicht gefunden.")
                    },
                )
            }
            item(key = "sensor") {
                StepSensorCard(
                    state = state,
                    onToggle = vm::onStepSensorSwitch,
                    onReadNow = vm::readSensorNow,
                )
            }
            item(key = "footer") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.hcAvailable) {
                        OutlinedButton(
                            onClick = {
                                if (!openHealthConnectSettings(context)) {
                                    showMessage("Health Connect konnte nicht geöffnet werden.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Health-Connect-Einstellungen öffnen")
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    TextButton(onClick = { navController.navigate(Routes.PRIVACY) }) {
                        Icon(Icons.Filled.PrivacyTip, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Datenschutz")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- cards

@Composable
private fun StatusCard(
    state: HealthConnectUiState,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
) {
    val availability = state.device.availability
    val enabled = state.settings.healthConnectEnabled
    val (pillLabel, pillColor) = when (availability) {
        HcAvailability.AVAILABLE -> when {
            enabled && state.hasAnyReadPermission -> "Verbunden" to MaterialTheme.colorScheme.primary
            enabled -> "Berechtigungen fehlen" to MaterialTheme.colorScheme.secondary
            else -> "Nicht verbunden" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        HcAvailability.NOT_INSTALLED -> "Nicht installiert" to MaterialTheme.colorScheme.secondary
        HcAvailability.UPDATE_REQUIRED -> "Update nötig" to MaterialTheme.colorScheme.secondary
        HcAvailability.NOT_SUPPORTED -> "Nicht verfügbar" to MaterialTheme.colorScheme.error
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.MonitorHeart, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Health Connect",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            StatusPill(pillLabel, pillColor)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Health Connect sammelt Gesundheitsdaten verschiedener Apps – z. B. Honor Health – sicher auf " +
                "deinem Gerät. KalorienKompass übernimmt daraus Schritte, aktive Kalorien, Gewicht und Körperfett.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (availability) {
            HcAvailability.AVAILABLE -> {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "Health Connect verwenden",
                    subtitle = "Schritte, Kalorien und Körperwerte automatisch übernehmen",
                    checked = enabled,
                    onCheckedChange = onToggle,
                )
            }
            HcAvailability.NOT_INSTALLED, HcAvailability.UPDATE_REQUIRED -> {
                Spacer(Modifier.height(12.dp))
                InfoLine(
                    icon = Icons.Filled.Info,
                    text = if (availability == HcAvailability.NOT_INSTALLED) {
                        "Auf diesem Gerät ist die App „Health Connect“ noch nicht installiert. Sie ist kostenlos im Play Store erhältlich."
                    } else {
                        "Die installierte Version von Health Connect ist zu alt. Bitte aktualisiere sie im Play Store."
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (availability == HcAvailability.NOT_INSTALLED) {
                            "Health Connect installieren"
                        } else {
                            "Health Connect aktualisieren"
                        },
                    )
                }
                if (enabled) {
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = "Health Connect verwenden",
                        subtitle = "Derzeit nicht verfügbar",
                        checked = true,
                        onCheckedChange = onToggle,
                    )
                }
            }
            HcAvailability.NOT_SUPPORTED -> {
                Spacer(Modifier.height(12.dp))
                InfoLine(
                    icon = Icons.Filled.Info,
                    text = "Health Connect wird auf diesem Gerät nicht unterstützt (benötigt Android 9 oder neuer). " +
                        "Nutze den Schrittsensor weiter unten oder trage deine Schritte im Tagebuch selbst ein.",
                )
                if (enabled) {
                    SwitchRow(
                        title = "Health Connect verwenden",
                        subtitle = "Derzeit nicht verfügbar",
                        checked = true,
                        onCheckedChange = onToggle,
                    )
                }
            }
        }
        if (availability == HcAvailability.AVAILABLE && enabled && !state.hasAnyReadPermission) {
            Spacer(Modifier.height(4.dp))
            InfoLine(
                icon = Icons.Filled.ErrorOutline,
                text = "Health Connect ist aktiviert, aber es wurde noch keine Leseberechtigung erteilt.",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun PermissionsCard(
    state: HealthConnectUiState,
    onRequest: () -> Unit,
    onManage: () -> Unit,
) {
    val items = state.permissionItems
    val requiredItems = items.filter { !it.optional }
    val grantedRequired = requiredItems.count { it.granted }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Berechtigungen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$grantedRequired von ${requiredItems.size} erteilt",
                style = MaterialTheme.typography.labelMedium,
                color = if (grantedRequired == requiredItems.size) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            PermissionRow(item)
        }
        Spacer(Modifier.height(8.dp))
        if (state.missingRequired || (state.device.backgroundReadAvailable && !state.backgroundGranted)) {
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(if (grantedRequired == 0) "Berechtigungen erteilen" else "Fehlende Berechtigungen erteilen")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Erscheint kein Dialog mehr? Dann kannst du die Berechtigungen direkt in Health Connect erteilen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onManage) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Berechtigungen in Health Connect verwalten")
        }
    }
}

@Composable
private fun PermissionRow(item: HcPermissionItem) {
    val icon = when (item.kind) {
        HcPermissionKind.STEPS -> Icons.AutoMirrored.Filled.DirectionsWalk
        HcPermissionKind.ACTIVE_CALORIES -> Icons.Filled.LocalFireDepartment
        HcPermissionKind.READ_WEIGHT -> Icons.Filled.MonitorWeight
        HcPermissionKind.WRITE_WEIGHT -> Icons.Filled.Edit
        HcPermissionKind.BODY_FAT -> Icons.Filled.Percent
        HcPermissionKind.BACKGROUND -> Icons.Filled.Schedule
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (item.granted) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Erteilt",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                Icons.Filled.RadioButtonUnchecked,
                contentDescription = "Nicht erteilt",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SyncCard(
    state: HealthConnectUiState,
    onSync: () -> Unit,
    onWriteWeight: (Boolean) -> Unit,
) {
    val lastSync = state.settings.lastHealthSyncAt
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Sync, MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Synchronisierung",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (lastSync != null) {
                        "Zuletzt: ${formatDateTime(lastSync)}"
                    } else {
                        "Noch nicht synchronisiert"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val today = state.today
        if (today != null && today.source == DataSource.HEALTH_CONNECT) {
            Spacer(Modifier.height(12.dp))
            TodayActivityRow(today)
        }

        state.lastResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            InfoLine(
                icon = if (result.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                text = result.message,
                color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSync,
            enabled = !state.syncing && state.hasAnyReadPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Synchronisiere …")
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Jetzt synchronisieren")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                state.backgroundGranted ->
                    "Der Abgleich läuft zusätzlich etwa stündlich im Hintergrund."
                state.device.backgroundReadAvailable ->
                    "Der Abgleich läuft beim Öffnen des Tagebuchs. Für den stündlichen Abgleich im Hintergrund " +
                        "erteile zusätzlich „Im Hintergrund lesen“."
                else ->
                    "Der Abgleich läuft beim Öffnen des Tagebuchs und – soweit Health Connect es erlaubt – etwa stündlich."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            title = "Gewicht an Health Connect senden",
            subtitle = "Selbst eingetragene Gewichte auch in Health Connect speichern – z. B. für Honor Health",
            checked = state.settings.writeWeightToHealthConnect,
            onCheckedChange = onWriteWeight,
        )
        if (state.settings.writeWeightToHealthConnect && !state.canWriteWeight) {
            InfoLine(
                icon = Icons.Filled.ErrorOutline,
                text = "Die Berechtigung „Gewicht schreiben“ fehlt – es wird nichts gesendet.",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun HonorHealthGuideCard(onOpenHonorHealth: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Watch, MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "So verbindest du Honor Health",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        GuideStep(
            number = 1,
            text = "Öffne Honor Health und tippe auf „Ich“ → „Einstellungen“ bzw. „Datenfreigabe“ → „Health Connect“.",
        )
        GuideStep(
            number = 2,
            text = "Aktiviere die Synchronisierung und gib „Schritte“ (und „Gewicht“) für Health Connect frei.",
        )
        GuideStep(
            number = 3,
            text = "Aktiviere hier „Health Connect verwenden“ und erteile die Berechtigungen.",
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Die Menünamen können je nach Version von Honor Health etwas abweichen. Bietet deine Version " +
                "keine Freigabe für Health Connect an, nutze den Schrittsensor als Alternative.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenHonorHealth, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Honor Health öffnen")
        }
    }
}

@Composable
private fun StepSensorCard(
    state: HealthConnectUiState,
    onToggle: (Boolean) -> Unit,
    onReadNow: () -> Unit,
) {
    val enabled = state.settings.stepSensorEnabled
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !state.device.activityRecognitionGranted
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Sensors, AppColors.burned)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Schrittsensor des Handys",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Alternative ohne Health Connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Nutzt den eingebauten Schrittzähler. Die Schritte werden beim Öffnen des Tagebuchs und etwa " +
                "stündlich übernommen und ab der Aktivierung gezählt. Schritte aus Health Connect haben Vorrang, " +
                "selbst eingetragene Schritte gelten immer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.device.sensorAvailable) {
            Spacer(Modifier.height(12.dp))
            InfoLine(
                icon = Icons.Filled.Info,
                text = "Dein Gerät hat keinen Schrittzähler-Sensor.",
                color = MaterialTheme.colorScheme.secondary,
            )
            return@AppCard
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            title = "Schrittsensor verwenden",
            subtitle = if (needsPermission) "Benötigt die Berechtigung „Körperliche Aktivitäten“" else null,
            checked = enabled,
            onCheckedChange = onToggle,
        )
        if (enabled && needsPermission) {
            InfoLine(
                icon = Icons.Filled.ErrorOutline,
                text = "Die Berechtigung „Körperliche Aktivitäten“ fehlt – der Sensor kann nicht gelesen werden.",
                color = MaterialTheme.colorScheme.secondary,
            )
            TextButton(onClick = { onToggle(true) }) {
                Text("Berechtigung erteilen")
            }
        } else if (enabled) {
            val today = state.today
            Spacer(Modifier.height(4.dp))
            if (today != null) {
                TodayActivityRow(today)
                Spacer(Modifier.height(4.dp))
            }
            TextButton(onClick = onReadNow, enabled = !state.sensorBusy) {
                if (state.sensorBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Sensor jetzt lesen")
            }
        }
    }
}

// ------------------------------------------------------------------------------------ building blocks

@Composable
private fun TodayActivityRow(today: DailyActivityEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Heute",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${formatSteps(today.steps)} Schritte",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            today.activeKcal?.let { kcal ->
                Text(
                    text = "${formatKcal(kcal)} kcal aktiv",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.burned,
                )
            }
            Text(
                text = "Quelle: ${today.source.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IconBadge(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoLine(
    icon: ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------------------------------ intents

private fun Context.startSafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Play Store page of Health Connect (installation or update), falling back to other stores / the web. */
private fun openHealthConnectInStore(context: Context): Boolean {
    val uri = Uri.parse(HealthConnectManager.PLAY_STORE_URI)
    val playStore = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.android.vending")
        .putExtra("overlay", true)
        .putExtra("callerId", context.packageName)
    return context.startSafely(playStore) ||
        context.startSafely(Intent(Intent.ACTION_VIEW, uri)) ||
        context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(HealthConnectManager.PLAY_STORE_WEB_URL)))
}

private fun openHealthConnectSettings(context: Context): Boolean =
    context.startSafely(HealthConnectManager.settingsIntent())

private fun openHealthConnectPermissions(context: Context): Boolean =
    context.startSafely(HealthConnectManager.managePermissionsIntent(context)) ||
        context.startSafely(HealthConnectManager.settingsIntent())

private fun openAppSettings(context: Context): Boolean = context.startSafely(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
)

/** Honor Health (MagicOS) or, on older Honor phones, Huawei Health. */
private fun openHonorHealth(context: Context): Boolean =
    HONOR_HEALTH_PACKAGES.any { pkg ->
        context.startSafely(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

private val HONOR_HEALTH_PACKAGES = listOf("com.hihonor.health", "com.huawei.health")
