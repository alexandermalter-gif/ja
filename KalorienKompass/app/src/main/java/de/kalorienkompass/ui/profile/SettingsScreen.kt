package de.kalorienkompass.ui.profile

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.BuildConfig
import de.kalorienkompass.backup.BackupManager
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.navigation.appViewModelFactory

private const val ANTHROPIC_CONSOLE_URL = "https://console.anthropic.com/settings/keys"
private const val OPEN_FOOD_FACTS_URL = "https://world.openfoodfacts.org"
private const val ANTHROPIC_URL = "https://www.anthropic.com"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val vm: SettingsViewModel = viewModel(
        factory = appViewModelFactory {
            SettingsViewModel(it.settingsRepository, it.foodPhotoAnalyzer, it.backupManager)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(vm) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_JSON),
    ) { uri -> if (uri != null) vm.exportBackup(uri) }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.onImportFilePicked(uri) }
    val diaryCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_CSV),
    ) { uri -> if (uri != null) vm.exportDiaryCsv(uri) }
    val measurementsCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_CSV),
    ) { uri -> if (uri != null) vm.exportMeasurementsCsv(uri) }
    val activityCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_CSV),
    ) { uri -> if (uri != null) vm.exportActivityCsv(uri) }

    /** Launching the system file picker can fail on stripped-down devices. */
    val launchSafely: (() -> Unit) -> Unit = { launch ->
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            vm.showMessage("Auf diesem Gerät ist keine Dateiauswahl verfügbar.")
        }
    }
    val openUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: Exception) {
            vm.showMessage("Es wurde kein Browser gefunden.")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackSafely() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (!state.loaded) {
            LoadingBox(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppearanceSection(selected = state.settings.themeMode, onSelect = vm::setTheme)
                BudgetSection(checked = state.settings.addBurnedCalories, onChange = vm::setAddBurnedCalories)
                AiSection(
                    state = state,
                    keyInput = vm.keyInput,
                    keyTest = vm.keyTest,
                    onKeyInputChange = vm::onKeyInputChange,
                    onSave = vm::saveKey,
                    onTest = vm::testKey,
                    onRemove = vm::removeKey,
                    onModel = vm::setModel,
                    onOpenConsole = { openUrl(ANTHROPIC_CONSOLE_URL) },
                )
                DataSection(
                    busy = vm.busyAction,
                    onExportBackup = { launchSafely { exportBackupLauncher.launch(BackupManager.backupFileName()) } },
                    onImportBackup = { launchSafely { importBackupLauncher.launch(BackupManager.IMPORT_MIME_TYPES) } },
                    onDiaryCsv = { launchSafely { diaryCsvLauncher.launch(BackupManager.diaryCsvFileName()) } },
                    onMeasurementsCsv = {
                        launchSafely { measurementsCsvLauncher.launch(BackupManager.measurementsCsvFileName()) }
                    },
                    onActivityCsv = { launchSafely { activityCsvLauncher.launch(BackupManager.activityCsvFileName()) } },
                )
                AboutSection(
                    onOpenFoodFacts = { openUrl(OPEN_FOOD_FACTS_URL) },
                    onAnthropic = { openUrl(ANTHROPIC_URL) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) { launchSingleTop = true } },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    vm.pendingImport?.let { pending ->
        BackupImportConfirmDialog(
            summary = pending.summary,
            onConfirm = vm::confirmImport,
            onDismiss = vm::dismissImport,
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun AppearanceSection(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionCard(title = "Darstellung", icon = Icons.Filled.Palette) {
        ThemeMode.entries.forEach { mode ->
            RadioRow(
                selected = mode == selected,
                title = mode.label,
                subtitle = when (mode) {
                    ThemeMode.SYSTEM -> "Folgt der Einstellung deines Telefons"
                    ThemeMode.LIGHT -> null
                    ThemeMode.DARK -> "Standard – schont den Akku bei OLED-Displays"
                },
                icon = when (mode) {
                    ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                    ThemeMode.LIGHT -> Icons.Filled.LightMode
                    ThemeMode.DARK -> Icons.Filled.DarkMode
                },
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun BudgetSection(checked: Boolean, onChange: (Boolean) -> Unit) {
    SectionCard(title = "Tagesbudget", icon = Icons.Filled.LocalFireDepartment) {
        SwitchRow(
            title = "Verbrannte Kalorien zum Tagesbudget addieren",
            subtitle = "Durch Schritte bzw. Aktivität (Health Connect) verbrannte Kalorien erhöhen dein verfügbares Budget.",
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSection(
    state: SettingsUiState,
    keyInput: String,
    keyTest: KeyTestState,
    onKeyInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onModel: (AiModel) -> Unit,
    onOpenConsole: () -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val stored = state.storedKey
    val input = keyInput.trim()
    val unsaved = input.isNotEmpty() && input != stored

    SectionCard(
        title = "KI-Fotoerkennung",
        icon = Icons.Filled.AutoAwesome,
        trailing = { StatusBadge(active = stored != null) },
    ) {
        Text(
            text = "Mit deinem eigenen Anthropic-API-Schlüssel erkennt Claude Lebensmittel auf Fotos und schätzt " +
                "die Mengen. Die Kalorien werden anschließend mit der Lebensmitteldatenbank berechnet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        InfoBox(
            icon = Icons.Filled.TravelExplore,
            tint = MaterialTheme.colorScheme.primary,
            text = "Derselbe Schlüssel wird auch für „Nährwerttabelle fotografieren“ und die „KI-Websuche“ " +
                "bei unbekannten Produkten verwendet (z. B. wenn ein Barcode weder lokal noch bei Open Food Facts " +
                "gefunden wird).",
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = onKeyInputChange,
            label = { Text("Anthropic-API-Schlüssel") },
            placeholder = { Text("sk-ant-…") },
            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (keyInput.isEmpty()) {
                        IconButton(
                            onClick = {
                                val pasted = clipboard.getText()?.text.orEmpty()
                                if (pasted.isNotBlank()) onKeyInputChange(pasted)
                            },
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Aus Zwischenablage einfügen")
                        }
                    } else {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Schlüssel verbergen" else "Schlüssel anzeigen",
                            )
                        }
                    }
                }
            },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (unsaved) onSave()
                },
            ),
            supportingText = {
                Text(
                    when {
                        input.isNotEmpty() && !input.startsWith("sk-") ->
                            "Anthropic-Schlüssel beginnen normalerweise mit „sk-ant-“."
                        unsaved -> "Noch nicht gespeichert"
                        stored != null -> "Gespeichert – nur auf diesem Gerät"
                        else -> "Noch kein Schlüssel hinterlegt"
                    },
                )
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSave()
                },
                enabled = unsaved,
            ) {
                Text("Speichern")
            }
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    onTest()
                },
                enabled = (stored != null || input.isNotEmpty()) && keyTest !is KeyTestState.Running,
            ) {
                Text("Schlüssel testen")
            }
            if (stored != null) {
                TextButton(
                    onClick = { confirmRemove = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Entfernen")
                }
            }
        }
        KeyTestResult(keyTest = keyTest, model = state.settings.aiModel)

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(text = "Modell", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        AiModel.entries.forEach { model ->
            RadioRow(
                selected = model == state.settings.aiModel,
                title = model.label,
                subtitle = model.costHint,
                onClick = { onModel(model) },
            )
        }
        Text(
            text = "Die Kosten werden direkt über dein Anthropic-Konto abgerechnet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        InfoBox(
            icon = Icons.Filled.Info,
            text = "So bekommst du einen Schlüssel:\n" +
                "1. console.anthropic.com öffnen und anmelden bzw. registrieren\n" +
                "2. Unter „API Keys“ einen neuen Schlüssel erstellen\n" +
                "3. Schlüssel kopieren und hier einfügen",
        ) {
            TextButton(
                onClick = onOpenConsole,
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Anthropic-Konsole öffnen")
            }
        }
        Spacer(Modifier.height(8.dp))
        InfoBox(
            icon = Icons.Filled.Lock,
            tint = MaterialTheme.colorScheme.secondary,
            text = "Fotos (und bei der KI-Websuche Barcode bzw. Produktname) werden zur Analyse an Anthropic " +
                "gesendet. Der Schlüssel wird nur auf diesem Gerät gespeichert – er ist weder im Backup noch in " +
                "der Android-Datensicherung enthalten.",
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Schlüssel entfernen?",
            text = "Die KI-Fotoerkennung, „Nährwerttabelle fotografieren“ und die KI-Websuche sind danach nicht " +
                "mehr verfügbar, bis du wieder einen Schlüssel hinterlegst.",
            confirmLabel = "Entfernen",
            destructive = true,
            onConfirm = onRemove,
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun StatusBadge(active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = if (active) "Aktiv" else "Aus",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun KeyTestResult(keyTest: KeyTestState, model: AiModel) {
    val (icon, color, text) = when (keyTest) {
        KeyTestState.Idle -> return
        KeyTestState.Running -> Triple(null, MaterialTheme.colorScheme.onSurfaceVariant, "Schlüssel wird geprüft …")
        KeyTestState.Success -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Der Schlüssel funktioniert (${model.label.substringBefore(" –")}).",
        )
        is KeyTestState.Failed -> Triple(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, keyTest.message)
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun DataSection(
    busy: DataAction?,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onDiaryCsv: () -> Unit,
    onMeasurementsCsv: () -> Unit,
    onActivityCsv: () -> Unit,
) {
    val idle = busy == null
    SectionCard(title = "Daten", icon = Icons.Filled.Storage) {
        NavRow(
            icon = Icons.Filled.Backup,
            title = "Backup exportieren",
            subtitle = "Alle Daten als JSON-Datei sichern (ohne API-Schlüssel)",
            enabled = idle,
            onClick = onExportBackup,
            trailing = busyTrailing(busy == DataAction.EXPORT_JSON),
        )
        NavRow(
            icon = Icons.Filled.Restore,
            title = "Backup importieren",
            subtitle = "Ersetzt alle Daten auf diesem Gerät",
            enabled = idle,
            tint = MaterialTheme.colorScheme.secondary,
            onClick = onImportBackup,
            trailing = busyTrailing(busy == DataAction.IMPORT_JSON),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        NavRow(
            icon = Icons.Filled.TableChart,
            title = "CSV-Export Tagebuch",
            subtitle = "Alle Einträge mit Nährwerten",
            enabled = idle,
            tint = MaterialTheme.colorScheme.tertiary,
            onClick = onDiaryCsv,
            trailing = busyTrailing(busy == DataAction.CSV_DIARY),
        )
        NavRow(
            icon = Icons.Filled.MonitorWeight,
            title = "CSV-Export Körperwerte",
            subtitle = "Gewicht, Körperfett und Umfänge",
            enabled = idle,
            tint = MaterialTheme.colorScheme.tertiary,
            onClick = onMeasurementsCsv,
            trailing = busyTrailing(busy == DataAction.CSV_MEASUREMENTS),
        )
        NavRow(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = "CSV-Export Schritte",
            subtitle = "Tägliche Schritte und aktive Kalorien",
            enabled = idle,
            tint = MaterialTheme.colorScheme.tertiary,
            onClick = onActivityCsv,
            trailing = busyTrailing(busy == DataAction.CSV_ACTIVITY),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "CSV-Dateien verwenden Semikolon und Dezimalkomma und lassen sich direkt mit Excel oder " +
                "LibreOffice öffnen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Spinner while the action runs, otherwise the default chevron. */
private fun busyTrailing(running: Boolean): (@Composable () -> Unit)? =
    if (running) {
        { SmallSpinner() }
    } else {
        null
    }

@Composable
private fun AboutSection(
    onOpenFoodFacts: () -> Unit,
    onAnthropic: () -> Unit,
    onPrivacy: () -> Unit,
) {
    SectionCard(title = "Über", icon = Icons.Filled.Info) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("KalorienKompass", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        NavRow(
            icon = Icons.Filled.Public,
            title = "Lebensmitteldaten: Open Food Facts (ODbL)",
            subtitle = "Freie Produktdatenbank – openfoodfacts.org",
            onClick = onOpenFoodFacts,
            trailing = { ExternalIcon() },
        )
        NavRow(
            icon = Icons.Filled.AutoAwesome,
            title = "KI: Anthropic Claude",
            subtitle = "Fotoerkennung, Nährwerttabellen und Websuche",
            onClick = onAnthropic,
            trailing = { ExternalIcon() },
        )
        NavRow(
            icon = Icons.Filled.PrivacyTip,
            title = "Datenschutz",
            subtitle = "Welche Daten wohin gehen",
            onClick = onPrivacy,
        )
    }
}

@Composable
private fun ExternalIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

