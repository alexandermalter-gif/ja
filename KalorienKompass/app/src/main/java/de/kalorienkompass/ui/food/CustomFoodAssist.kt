package de.kalorienkompass.ui.food

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.kalorienkompass.ui.ai.AiPhotoFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Costs per lookup with the default model (spec §13). */
internal const val LABEL_COST_HINT = "ca. 1–4 ct pro Foto"
internal const val WEB_COST_HINT = "ca. 2–6 ct pro Suche"
private const val CONSOLE_KEYS_URL = "https://console.anthropic.com/settings/keys"

/** Camera (with CAMERA permission first) and gallery for the nutrition table photo. */
internal class LabelPhotoLauncher(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

/**
 * Camera via `TakePicture` into the FileProvider cache path `ai_photos/` (the CAMERA permission is
 * requested first because the manifest declares it) and gallery via `PickVisualMedia`.
 * [onMessage] shows a German message, optionally with an action ("Einstellungen").
 */
@Composable
internal fun rememberLabelPhotoLauncher(
    onCaptured: (File) -> Unit,
    onPicked: (Uri) -> Unit,
    onMessage: (text: String, actionLabel: String?, action: (() -> Unit)?) -> Unit,
): LabelPhotoLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCapturePath by rememberSaveable { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingCapturePath
        pendingCapturePath = null
        if (path != null) {
            val file = File(path)
            if (success) {
                onCaptured(file)
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
                onMessage("Das Foto kann nicht gespeichert werden (kein Speicherplatz?).", null, null)
                return@launch
            }
            try {
                val uri = AiPhotoFiles.contentUri(context, file)
                pendingCapturePath = file.absolutePath
                takePicture.launch(uri)
            } catch (e: ActivityNotFoundException) {
                pendingCapturePath = null
                withContext(Dispatchers.IO) { file.delete() }
                onMessage("Keine Kamera-App gefunden. Bitte wähle ein Foto aus der Galerie.", null, null)
            } catch (e: IllegalArgumentException) {
                pendingCapturePath = null
                withContext(Dispatchers.IO) { file.delete() }
                onMessage("Die Kamera konnte nicht gestartet werden.", null, null)
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
                onMessage(
                    "Der Kamerazugriff ist ausgeschaltet. Erlaube ihn in den App-Einstellungen oder wähle ein Foto aus der Galerie.",
                    "Einstellungen",
                ) { context.openAppSettings() }
            } else {
                onMessage("Ohne Kamerazugriff kannst du ein Foto aus der Galerie wählen.", null, null)
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }

    return LabelPhotoLauncher(
        takePhoto = {
            // The manifest declares CAMERA, so the camera app may only be started once it is granted.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
        pickPhoto = {
            try {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: ActivityNotFoundException) {
                onMessage("Keine Galerie-App gefunden.", null, null)
            }
        },
    )
}

/**
 * "Werte automatisch ermitteln" (spec §13): photo of the nutrition table, gallery, web search,
 * cost hint and the state of a running/failed lookup. Emitted into the top section's column.
 */
@Composable
internal fun CustomFoodAssistActions(
    assist: AssistState,
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onWebSearch: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val running = assist is AssistState.Running
    val buttonsEnabled = enabled && !running

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Spacer(Modifier.height(2.dp))
    Text("Werte automatisch ermitteln", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

    FilledTonalButton(
        onClick = onTakePhoto,
        enabled = buttonsEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Nährwerttabelle fotografieren")
    }
    FilledTonalButton(
        onClick = onWebSearch,
        enabled = buttonsEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Im Web suchen (KI)")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onPickPhoto, enabled = buttonsEnabled) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Aus Galerie")
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Tabellenfoto aus deinen Bildern wählen",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        text = "KI (Claude): $LABEL_COST_HINT · $WEB_COST_HINT. Dafür werden das Foto bzw. Barcode und Name " +
            "mit deinem API-Schlüssel an Anthropic gesendet. Gespeichert wird erst, wenn du auf „Speichern“ tippst.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (assist) {
        AssistState.Idle -> Unit
        is AssistState.Running -> RunningBox(assist, onCancel)
        AssistState.MissingKey -> MissingKeyBox(
            onOpenSettings = onOpenSettings,
            onOpenConsole = { context.openUrl(CONSOLE_KEYS_URL) },
            onDismiss = onDismiss,
        )
        is AssistState.Failed -> FailedBox(
            state = assist,
            onRetry = onRetry,
            onNewPhoto = onTakePhoto,
            onOpenSettings = onOpenSettings,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun RunningBox(state: AssistState.Running, onCancel: () -> Unit) {
    val (title, text) = when (state.kind) {
        AssistKind.LABEL -> "KI liest die Nährwerttabelle …" to "Das dauert meist nur einige Sekunden."
        AssistKind.WEB -> "KI sucht im Web nach dem Produkt …" to "Die Recherche kann bis zu einer Minute dauern."
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
    ) {
        val photo = state.photo
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Foto der Nährwerttabelle",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onCancel) { Text("Abbrechen") }
    }
}

@Composable
private fun FailedBox(
    state: AssistState.Failed,
    onRetry: () -> Unit,
    onNewPhoto: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
            .padding(start = 12.dp, top = 8.dp, bottom = 6.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fehlermeldung ausblenden", modifier = Modifier.size(16.dp))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.opensSettings) {
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Einstellungen")
                }
            }
            if (state.kind == AssistKind.LABEL) {
                TextButton(onClick = onNewPhoto) { Text("Neues Foto") }
            }
            if (state.canRetry) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Erneut versuchen")
                }
            }
        }
    }
}

/** Same guidance as the photo AI: how to get a key, button to the settings. */
@Composable
private fun MissingKeyBox(onOpenSettings: () -> Unit, onOpenConsole: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
            .padding(start = 12.dp, top = 8.dp, bottom = 12.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("API-Schlüssel benötigt", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Hinweis ausblenden", modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = "Das automatische Ermitteln nutzt Claude von Anthropic mit deinem eigenen API-Schlüssel. " +
                    "Er wird nur auf diesem Gerät gespeichert. So bekommst du einen:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            listOf(
                "Auf console.anthropic.com anmelden oder registrieren.",
                "Unter „API Keys“ einen neuen Schlüssel erstellen (beginnt mit „sk-ant-“).",
                "Unter „Billing“ etwas Guthaben aufladen – ein Foto oder eine Suche kostet nur wenige Cent.",
                "Den Schlüssel in den Einstellungen unter „KI-Fotoerkennung“ einfügen.",
            ).forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Zu den Einstellungen")
            }
            OutlinedButton(onClick = onOpenConsole, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("console.anthropic.com öffnen")
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        android.widget.Toast.makeText(this, "Kein Browser gefunden: $url", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun Context.openAppSettings() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    } catch (e: ActivityNotFoundException) {
        android.widget.Toast.makeText(this, "Die App-Einstellungen konnten nicht geöffnet werden.", android.widget.Toast.LENGTH_LONG).show()
    }
}
