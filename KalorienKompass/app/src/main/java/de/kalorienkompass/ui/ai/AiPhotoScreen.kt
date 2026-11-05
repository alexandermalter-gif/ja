package de.kalorienkompass.ui.ai

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiFoodMatcher
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.util.dayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private const val CONSOLE_KEYS_URL = "https://console.anthropic.com/settings/keys"
private const val CONSOLE_BILLING_URL = "https://console.anthropic.com/settings/billing"
private const val MAX_HINT_LENGTH = 300

private enum class DiscardAction { LEAVE, RESTART }

/**
 * Photo → foods: take/pick a photo, Claude recognizes the foods and amounts, each item is matched
 * against the food database, the user reviews everything and adds it to the diary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPhotoScreen(navController: NavController, date: LocalDate, meal: MealType) {
    val context = LocalContext.current
    val vm: AiPhotoViewModel = viewModel(
        key = "ai_photo_${date.toEpochDay()}_${meal.name}",
        factory = appViewModelFactory { c ->
            AiPhotoViewModel(
                appContext = c.context,
                analyzer = c.foodPhotoAnalyzer,
                matcher = AiFoodMatcher(c.foodRepository),
                foodRepository = c.foodRepository,
                diaryRepository = c.diaryRepository,
                database = c.database,
                settingsRepository = c.settingsRepository,
                date = date,
                initialMeal = meal,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val aiModel by vm.aiModel.collectAsStateWithLifecycle()
    val selectedMeal by vm.meal.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var hint by rememberSaveable { mutableStateOf("") }
    var pendingCapturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showCameraSettings by rememberSaveable { mutableStateOf(false) }
    var discardAction by rememberSaveable { mutableStateOf<DiscardAction?>(null) }
    var searchItemKey by rememberSaveable { mutableStateOf<Long?>(null) }

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    // --- camera & gallery -------------------------------------------------------------------
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingCapturePath
        pendingCapturePath = null
        if (path != null) {
            val file = File(path)
            if (success) {
                vm.onPhotoCaptured(file, hint)
            } else {
                scope.launch { withContext(Dispatchers.IO) { file.delete() } }
            }
        }
    }

    fun launchCamera() {
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { AiPhotoFiles.newCaptureFile(context) }
            } catch (e: Exception) {
                showMessage("Das Foto kann nicht gespeichert werden (kein Speicherplatz?).")
                return@launch
            }
            try {
                val uri = AiPhotoFiles.contentUri(context, file)
                pendingCapturePath = file.absolutePath
                takePicture.launch(uri)
            } catch (e: ActivityNotFoundException) {
                pendingCapturePath = null
                withContext(Dispatchers.IO) { file.delete() }
                showMessage("Keine Kamera-App gefunden. Bitte wähle ein Foto aus der Galerie.")
            } catch (e: IllegalArgumentException) {
                pendingCapturePath = null
                withContext(Dispatchers.IO) { file.delete() }
                showMessage("Die Kamera konnte nicht gestartet werden.")
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            val activity = context.findActivity()
            val permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            if (permanentlyDenied) {
                showCameraSettings = true
            } else {
                showMessage("Ohne Kamerazugriff kannst du ein Foto aus der Galerie wählen.")
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhotoPicked(uri, hint)
    }

    val onTakePhoto: () -> Unit = {
        focusManager.clearFocus()
        // The manifest declares CAMERA, so the camera app may only be started once it is granted.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val onPickPhoto: () -> Unit = {
        focusManager.clearFocus()
        try {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            showMessage("Keine Galerie-App gefunden.")
        }
    }
    val openSettings: () -> Unit = { navController.navigate(Routes.SETTINGS) }

    // --- events -----------------------------------------------------------------------------
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AiPhotoEvent.Added -> {
                    Toast.makeText(
                        context,
                        "${event.count} Lebensmittel zu ${event.meal.label} hinzugefügt",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (!navController.popBackStack(Routes.FOOD_SEARCH, inclusive = true)) {
                        navController.popBackStack()
                    }
                }
                is AiPhotoEvent.ShowMessage -> showMessage(event.text)
                is AiPhotoEvent.ItemRemoved -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "„${event.name}“ entfernt",
                        actionLabel = "Rückgängig",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoRemove(event.key)
                }
            }
        }
    }

    // --- back navigation --------------------------------------------------------------------
    val onBack: () -> Unit = {
        when (val s = state) {
            AiPhotoUiState.Start -> navController.popBackStack()
            is AiPhotoUiState.Analyzing -> vm.cancelAnalysis()
            is AiPhotoUiState.Failure -> vm.startOver()
            is AiPhotoUiState.Review ->
                if (s.items.isEmpty()) navController.popBackStack() else discardAction = DiscardAction.LEAVE
        }
    }
    BackHandler(enabled = state !is AiPhotoUiState.Start) { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Foto-Erkennung", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${selectedMeal.emoji} ${selectedMeal.label} · ${dayLabel(date)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    val review = state as? AiPhotoUiState.Review
                    if (review != null && !review.saving) {
                        IconButton(onClick = {
                            if (review.items.isEmpty()) vm.startOver() else discardAction = DiscardAction.RESTART
                        }) {
                            Icon(Icons.Filled.AddAPhoto, contentDescription = "Neues Foto")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val review = state as? AiPhotoUiState.Review
            if (review != null && review.items.isNotEmpty()) {
                ReviewBottomBar(state = review, onAddAll = vm::addAll)
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "aiPhotoState",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) { current ->
            when (current) {
                AiPhotoUiState.Start -> StartContent(
                    hasApiKey = hasApiKey,
                    model = aiModel,
                    hint = hint,
                    onHintChange = { if (it.length <= MAX_HINT_LENGTH) hint = it },
                    onTakePhoto = onTakePhoto,
                    onPickPhoto = onPickPhoto,
                    onOpenSettings = openSettings,
                    onOpenConsole = { context.openUrl(CONSOLE_KEYS_URL) },
                )
                is AiPhotoUiState.Analyzing -> AnalyzingContent(state = current, onCancel = vm::cancelAnalysis)
                is AiPhotoUiState.Review -> ReviewContent(
                    state = current,
                    selectedMeal = selectedMeal,
                    model = aiModel,
                    onMealChange = vm::setMeal,
                    onAmountChange = vm::updateAmount,
                    onSelectFood = vm::selectFood,
                    onSearchFood = { searchItemKey = it },
                    onRemove = vm::removeItem,
                    onRetakePhoto = vm::startOver,
                    onManualSearch = {
                        if (!navController.popBackStack(Routes.FOOD_SEARCH, inclusive = false)) {
                            navController.navigate(Routes.foodSearch(date, selectedMeal))
                        }
                    },
                )
                is AiPhotoUiState.Failure -> FailureContent(
                    state = current,
                    onRetry = vm::retry,
                    onOpenSettings = openSettings,
                    onOpenBilling = { context.openUrl(CONSOLE_BILLING_URL) },
                    onStartOver = vm::startOver,
                )
            }
        }
    }

    // --- dialogs ----------------------------------------------------------------------------
    val searchKey = searchItemKey
    val searchItem = (state as? AiPhotoUiState.Review)?.items?.firstOrNull { it.key == searchKey }
    if (searchKey != null && searchItem != null) {
        FoodSearchDialog(
            itemName = searchItem.ai.name,
            initialQuery = searchItem.ai.searchTerm,
            searchLocal = vm::searchLocal,
            searchRemote = vm::searchRemote,
            onSelect = { food: FoodEntity ->
                vm.selectFood(searchKey, food)
                searchItemKey = null
            },
            onDismiss = { searchItemKey = null },
        )
    }
    LaunchedEffect(searchKey, searchItem == null) {
        if (searchKey != null && searchItem == null) searchItemKey = null // item removed or analysis restarted
    }

    discardAction?.let { action ->
        ConfirmDialog(
            title = "Ergebnis verwerfen?",
            text = "Die erkannten Lebensmittel werden nicht ins Tagebuch übernommen.",
            confirmLabel = "Verwerfen",
            destructive = true,
            onConfirm = {
                when (action) {
                    DiscardAction.LEAVE -> navController.popBackStack()
                    DiscardAction.RESTART -> vm.startOver()
                }
            },
            onDismiss = { discardAction = null },
        )
    }

    if (showCameraSettings) {
        ConfirmDialog(
            title = "Kamerazugriff benötigt",
            text = "Um ein Foto aufzunehmen, braucht die App Zugriff auf die Kamera. Du kannst ihn in den " +
                "App-Einstellungen erlauben oder ein Foto aus der Galerie wählen.",
            confirmLabel = "Einstellungen öffnen",
            onConfirm = { context.openAppSettings() },
            onDismiss = { showCameraSettings = false },
        )
    }
}

// --- Start --------------------------------------------------------------------------------------

@Composable
private fun StartContent(
    hasApiKey: Boolean?,
    model: AiModel,
    hint: String,
    onHintChange: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyMissing = hasApiKey == false
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (keyMissing) {
            ApiKeyMissingCard(onOpenSettings = onOpenSettings, onOpenConsole = onOpenConsole)
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Filled.AutoAwesome, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Foto machen – die KI erledigt den Rest",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Claude erkennt Lebensmittel und Mengen auf dem Foto und ordnet sie deiner " +
                            "Lebensmitteldatenbank zu. Du prüfst alles, bevor es ins Tagebuch kommt.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedTextField(
            value = hint,
            onValueChange = onHintChange,
            label = { Text("Hinweis (optional)") },
            placeholder = { Text("z. B. ‚mit Olivenöl‘ oder ‚halbe Portion‘") },
            supportingText = { Text("Hilft der KI bei Zutaten, Zubereitung oder Menge.") },
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onTakePhoto,
                enabled = !keyMissing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Foto aufnehmen", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = onPickPhoto,
                enabled = !keyMissing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Aus Galerie wählen")
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${model.shortName} · ${model.costHint}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Das Foto wird zur Analyse an Anthropic gesendet und über deinen API-Schlüssel " +
                            "abgerechnet. In der App wird es nicht gespeichert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onOpenSettings,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("Modell in den Einstellungen ändern")
                    }
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text("Tipps für gute Ergebnisse", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            listOf(
                "Von schräg oben fotografieren, die ganze Portion im Bild.",
                "Teller, Besteck oder Glas helfen der KI, die Menge zu schätzen.",
                "Versteckte Zutaten wie Öl, Butter oder Sauce im Hinweis angeben.",
            ).forEach { tip ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("•  ", color = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ApiKeyMissingCard(onOpenSettings: () -> Unit, onOpenConsole: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.Key, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.size(14.dp))
            Text(
                text = "API-Schlüssel benötigt",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Die Foto-Erkennung nutzt Claude von Anthropic mit deinem eigenen API-Schlüssel. " +
                "Er wird nur auf diesem Gerät gespeichert. So bekommst du einen:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "Auf console.anthropic.com anmelden oder registrieren.",
            "Unter „API Keys“ einen neuen Schlüssel erstellen (beginnt mit „sk-ant-“).",
            "Unter „Billing“ etwas Guthaben aufladen – ein Foto kostet nur wenige Cent.",
            "Den Schlüssel in den Einstellungen unter „KI-Fotoerkennung“ einfügen.",
        ).forEachIndexed { index, step ->
            Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(text = step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Zu den Einstellungen")
        }
        OutlinedButton(onClick = onOpenConsole, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("console.anthropic.com öffnen")
        }
    }
}

// --- Analyzing ----------------------------------------------------------------------------------

@Composable
private fun AnalyzingContent(state: AiPhotoUiState.Analyzing, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScanningPhoto(photo = state.photo)
        Spacer(Modifier.height(28.dp))
        Text(
            text = state.step.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.step.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(50)),
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
    }
}

/** The photo with a slowly moving "scan" light while the AI works. */
@Composable
private fun ScanningPhoto(photo: Bitmap?) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanProgress",
    )
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            val image = remember(photo) { photo.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Dein Foto",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bandHeight = size.height * 0.22f
            val y = (size.height + bandHeight) * progress - bandHeight
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent),
                    startY = y,
                    endY = y + bandHeight,
                ),
                topLeft = Offset(0f, y),
                size = Size(size.width, bandHeight),
            )
            drawRect(color = accent.copy(alpha = 0.9f), topLeft = Offset(0f, y + bandHeight / 2f), size = Size(size.width, 2.dp.toPx()))
        }
    }
}

// --- Failure ------------------------------------------------------------------------------------

@Composable
private fun FailureContent(
    state: AiPhotoUiState.Failure,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBilling: () -> Unit,
    onStartOver: () -> Unit,
) {
    val (icon, title) = when (state.kind) {
        AiErrorKind.MISSING_KEY -> Icons.Filled.Key to "API-Schlüssel fehlt"
        AiErrorKind.INVALID_KEY -> Icons.Filled.Key to "API-Schlüssel ungültig"
        AiErrorKind.NO_ACCESS -> Icons.Filled.Key to "Kein Zugriff"
        AiErrorKind.BILLING -> Icons.Outlined.ErrorOutline to "Guthaben aufgebraucht"
        AiErrorKind.NETWORK, AiErrorKind.TIMEOUT -> Icons.Outlined.WifiOff to "Keine Verbindung"
        AiErrorKind.REFUSAL -> Icons.Outlined.Block to "Anfrage abgelehnt"
        AiErrorKind.BAD_IMAGE -> Icons.Outlined.BrokenImage to "Foto nicht lesbar"
        else -> Icons.Outlined.ErrorOutline to "Analyse fehlgeschlagen"
    }
    val needsSettings = state.kind == AiErrorKind.MISSING_KEY ||
        state.kind == AiErrorKind.INVALID_KEY ||
        state.kind == AiErrorKind.NO_ACCESS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.photo != null) {
            val image = remember(state.photo) { state.photo.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Dein Foto",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(20.dp))
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (needsSettings) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Zu den Einstellungen")
                }
            }
            if (state.kind == AiErrorKind.BILLING) {
                Button(onClick = onOpenBilling, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Guthaben aufladen")
                }
            }
            if (state.canRetry) {
                val retryIsPrimary = !needsSettings && state.kind != AiErrorKind.BILLING
                if (retryIsPrimary) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Erneut versuchen")
                    }
                } else {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Erneut versuchen")
                    }
                }
            }
            OutlinedButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
                Text("Anderes Foto wählen")
            }
        }
    }
}

// --- shared bits --------------------------------------------------------------------------------

@Composable
internal fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/** "Claude Opus 5.5" from "Claude Opus 5.5 – genaueste Erkennung (Standard)". */
internal val AiModel.shortName: String
    get() = label.substringBefore(" – ").trim()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Kein Browser gefunden: $url", Toast.LENGTH_LONG).show()
    }
}

private fun Context.openAppSettings() {
    try {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Die App-Einstellungen konnten nicht geöffnet werden.", Toast.LENGTH_LONG).show()
    }
}
