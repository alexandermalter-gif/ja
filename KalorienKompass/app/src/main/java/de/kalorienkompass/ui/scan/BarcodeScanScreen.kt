package de.kalorienkompass.ui.scan

import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.util.dayLabel
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Barcode scanner (spec §11 SCAN + addendum §13).
 *
 * Camera preview with scan window → ML Kit detection → [BarcodeScanViewModel] looks the code up
 * (local database first, then Open Food Facts):
 * - found → food detail (the scanner is replaced, pick mode is passed on),
 * - unknown → "Produkt nicht gefunden" sheet (Nährwerttabelle fotografieren / Im Web suchen (KI) /
 *   Manuell eingeben / Erneut scannen),
 * - error → message with retry.
 * Without camera (permission denied, no hardware) the barcode can be typed in.
 */
@Composable
fun BarcodeScanScreen(navController: NavController, date: LocalDate?, meal: MealType?, pickMode: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel: BarcodeScanViewModel = viewModel(
        factory = appViewModelFactory { container ->
            BarcodeScanViewModel(
                lookup = container.foodRepository::lookupBarcode,
                aiKeyAvailable = container.settingsRepository.aiApiKey.map { !it.isNullOrBlank() },
            )
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val status = uiState.status

    val hasCameraHardware = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val permission = rememberCameraPermissionState(autoRequest = hasCameraHardware)
    val cameraMode = hasCameraHardware && permission.granted
    val cameraState = remember { ScanCameraState() }
    var showManualDialog by rememberSaveable { mutableStateOf(false) }

    // ---- navigation: the scanner is always replaced by the next screen ----
    val leaveTo: (String) -> Unit = { route ->
        if (viewModel.beginLeaving()) {
            navController.navigate(route) {
                popUpTo(Routes.BARCODE) { inclusive = true }
            }
        }
    }
    val customFoodRoute: (String) -> String = { code ->
        Routes.customFood(barcode = code, date = date, meal = meal, pick = pickMode)
    }
    LaunchedEffect(status) {
        if (status is ScanStatus.Found && viewModel.beginLeaving(success = true)) {
            navController.navigate(Routes.foodDetail(status.foodId, date, meal, pickMode)) {
                popUpTo(Routes.BARCODE) { inclusive = true }
            }
        }
    }
    val onBack: () -> Unit = {
        // Ignore double taps while the screen is already leaving.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            navController.popBackStack()
        }
    }
    val openManualEntry: () -> Unit = { showManualDialog = true }

    val subtitle = when {
        pickMode -> "Zutat für ein Rezept"
        meal != null && date != null -> "${meal.label} · ${dayLabel(date)}"
        meal != null -> meal.label
        else -> null
    }

    if (cameraMode) {
        KeepScreenOnEffect(view)
        LightSystemBarIconsEffect(view)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            CameraScanLayer(
                status = status,
                analysisActive = uiState.isScanning && !showManualDialog,
                cameraState = cameraState,
                onDetected = { code ->
                    if (viewModel.onBarcodeDetected(code)) view.performScanHaptic()
                },
                onCancelLookup = viewModel::cancelLookup,
                onRetry = viewModel::retry,
                onRescan = viewModel::resumeScanning,
                onCreateManually = { code -> leaveTo(customFoodRoute(code)) },
                onManualEntry = openManualEntry,
            )
            ScanTopBar(
                subtitle = subtitle,
                overCamera = true,
                torchVisible = cameraState.hasTorch,
                torchOn = cameraState.torchOn,
                onBack = onBack,
                onToggleTorch = cameraState::toggleTorch,
                onManualEntry = openManualEntry,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ScanTopBar(
                subtitle = subtitle,
                overCamera = false,
                torchVisible = false,
                torchOn = false,
                onBack = onBack,
                onToggleTorch = {},
                onManualEntry = openManualEntry,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (!hasCameraHardware) {
                    NoCameraContent(onManualEntry = openManualEntry)
                } else {
                    CameraPermissionContent(
                        permission = permission,
                        onOpenSettings = { openAppSettings(context) },
                        onManualEntry = openManualEntry,
                    )
                }
                LookupStatusOverlay(
                    status = status,
                    onCancel = viewModel::cancelLookup,
                    onRetry = viewModel::retry,
                    onOtherCode = {
                        viewModel.resumeScanning()
                        showManualDialog = true
                    },
                    onCreateManually = { code -> leaveTo(customFoodRoute(code)) },
                )
            }
        }
    }

    if (showManualDialog) {
        ManualBarcodeDialog(
            onConfirm = viewModel::onManualCode,
            onDismiss = { showManualDialog = false },
        )
    }

    if (status is ScanStatus.NotFound) {
        val code = status.code
        key(code) {
            ProductNotFoundSheet(
                code = code,
                aiKeyAvailable = uiState.aiKeyAvailable,
                rescanLabel = if (cameraMode) "Erneut scannen" else "Anderen Barcode eingeben",
                // Three separate callbacks: the integration step adds the requested action to each route.
                onPhotoNutritionLabel = { leaveTo(customFoodRoute(code)) },
                onWebSearch = { leaveTo(customFoodRoute(code)) },
                onManualEntry = { leaveTo(customFoodRoute(code)) },
                onRescan = {
                    viewModel.resumeScanning()
                    if (!cameraMode) showManualDialog = true
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Camera layer
// ---------------------------------------------------------------------------------------------

@Composable
private fun CameraScanLayer(
    status: ScanStatus,
    analysisActive: Boolean,
    cameraState: ScanCameraState,
    onDetected: (String) -> Unit,
    onCancelLookup: () -> Unit,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
    onCreateManually: (String) -> Unit,
    onManualEntry: () -> Unit,
) {
    val currentOnDetected by rememberUpdatedState(onDetected)
    var detectorFailed by remember { mutableStateOf(false) }
    val analyzer = remember {
        BarcodeAnalyzer(
            onDetected = { code -> currentOnDetected(code) },
            onFailure = { detectorFailed = true },
        )
    }
    // Declared before the preview: disposed after the camera has been unbound.
    DisposableEffect(analyzer) {
        onDispose { analyzer.close() }
    }
    SideEffect { analyzer.isEnabled = analysisActive }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val window = scanWindowFor(maxWidth, maxHeight)
        val cameraError = cameraState.error
        val success = status is ScanStatus.Found || (status is ScanStatus.Closing && status.success)
        val overlayMode = when {
            cameraError != null -> OverlayMode.IDLE
            success -> OverlayMode.SUCCESS
            !cameraState.isStreaming || status is ScanStatus.LookingUp -> OverlayMode.BUSY
            status is ScanStatus.Scanning -> OverlayMode.SCANNING
            else -> OverlayMode.IDLE
        }

        ScanCameraPreview(
            state = cameraState,
            analyzer = analyzer,
            modifier = Modifier.fillMaxSize(),
        )
        ScanOverlay(
            window = window,
            mode = overlayMode,
            modifier = Modifier.fillMaxSize(),
            onTap = { point -> cameraState.focusAt(point) },
        )

        // Inside the scan window.
        Box(
            modifier = Modifier
                .offset(x = window.left, y = window.top)
                .size(width = window.width, height = window.height),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cameraError != null -> Icon(
                    imageVector = Icons.Filled.VideocamOff,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(44.dp),
                )
                success -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ScannerAccent,
                    modifier = Modifier.size(56.dp),
                )
                !cameraState.isStreaming || status is ScanStatus.LookingUp -> CircularProgressIndicator(
                    color = ScannerAccent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    strokeWidth = 3.dp,
                )
            }
        }

        // Status text below the scan window.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = window.bottom + 20.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                cameraError != null -> Unit
                success -> StatusText(title = "Produkt gefunden")
                !cameraState.isStreaming -> StatusText(title = "Kamera wird gestartet …")
                status is ScanStatus.Scanning -> StatusText(
                    title = "Barcode in den Rahmen halten",
                    text = "Wird automatisch erkannt · Tippe ins Bild zum Scharfstellen",
                )
                status is ScanStatus.LookingUp -> {
                    StatusText(title = "Produkt wird gesucht …")
                    Spacer(Modifier.height(8.dp))
                    BarcodeChip(
                        code = status.code,
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCancelLookup) {
                        Text("Abbrechen", color = Color.White)
                    }
                }
                else -> Unit
            }
        }

        // Bottom area: errors or manual entry.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))),
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                cameraError != null -> CameraErrorCard(
                    message = cameraError,
                    onRetry = cameraState::retry,
                    onManualEntry = onManualEntry,
                )
                status is ScanStatus.Failed -> LookupErrorCard(
                    status = status,
                    rescanLabel = "Weiter scannen",
                    onRetry = onRetry,
                    onRescan = onRescan,
                    onCreateManually = { onCreateManually(status.code) },
                )
                status is ScanStatus.Closing || status is ScanStatus.Found -> Unit
                else -> {
                    if (detectorFailed) {
                        DetectorWarning()
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedButton(
                        onClick = onManualEntry,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier
                            .widthIn(min = 220.dp)
                            .heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Barcode eintippen")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(title: String, text: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        textAlign = TextAlign.Center,
    )
    if (text != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DetectorWarning() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Die automatische Erkennung funktioniert gerade nicht. Tippe den Barcode bitte ein.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards and overlays
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Lookup failed (e.g. no internet): message, retry, continue scanning, create without lookup. */
@Composable
private fun LookupErrorCard(
    status: ScanStatus.Failed,
    rescanLabel: String,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
    onCreateManually: () -> Unit,
) {
    ScanCard {
        CardHeader(icon = Icons.Filled.CloudOff, title = "Suche fehlgeschlagen", tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Text(
            text = status.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        BarcodeChip(code = status.code)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Erneut versuchen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRescan,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(rescanLabel)
        }
        TextButton(
            onClick = onCreateManually,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Selbst anlegen")
        }
    }
}

@Composable
private fun CameraErrorCard(message: String, onRetry: () -> Unit, onManualEntry: () -> Unit) {
    ScanCard {
        CardHeader(icon = Icons.Filled.VideocamOff, title = "Kamera nicht verfügbar", tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Neu starten", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onManualEntry,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Eintippen", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lookup progress / error on top of the permission or "no camera" content. */
@Composable
private fun LookupStatusOverlay(
    status: ScanStatus,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOtherCode: () -> Unit,
    onCreateManually: (String) -> Unit,
) {
    if (status !is ScanStatus.LookingUp && status !is ScanStatus.Failed) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            // Swallow taps so the content below cannot be used meanwhile.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            is ScanStatus.LookingUp -> ScanCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Produkt wird gesucht …",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        BarcodeChip(code = status.code)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("Abbrechen")
                }
            }
            is ScanStatus.Failed -> LookupErrorCard(
                status = status,
                rescanLabel = "Anderen Barcode eingeben",
                onRetry = onRetry,
                onRescan = onOtherCode,
                onCreateManually = { onCreateManually(status.code) },
            )
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar & window effects
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanTopBar(
    subtitle: String?,
    overCamera: Boolean,
    torchVisible: Boolean,
    torchOn: Boolean,
    onBack: () -> Unit,
    onToggleTorch: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val contentColor = if (overCamera) Color.White else MaterialTheme.colorScheme.onBackground
    TopAppBar(
        title = {
            Column {
                Text(text = "Barcode scannen", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
        },
        actions = {
            if (torchVisible) {
                IconButton(onClick = onToggleTorch) {
                    Icon(
                        imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        contentDescription = if (torchOn) "Taschenlampe ausschalten" else "Taschenlampe einschalten",
                        tint = if (torchOn) ScannerAccent else contentColor,
                    )
                }
            }
            IconButton(onClick = onManualEntry) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Barcode eintippen")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (overCamera) Color.Transparent else MaterialTheme.colorScheme.background,
            scrolledContainerColor = if (overCamera) Color.Transparent else MaterialTheme.colorScheme.background,
            navigationIconContentColor = contentColor,
            titleContentColor = contentColor,
            actionIconContentColor = contentColor,
        ),
        modifier = if (overCamera) {
            Modifier.background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)),
            )
        } else {
            Modifier
        },
    )
}

/** Keeps the display on while the camera is scanning. */
@Composable
private fun KeepScreenOnEffect(view: View) {
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}

/** White status/navigation bar icons on the black camera screen (also in the light theme). */
@Composable
private fun LightSystemBarIconsEffect(view: View) {
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null || view.isInEditMode) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNavigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }
}

private fun View.performScanHaptic() {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(feedback)
}
