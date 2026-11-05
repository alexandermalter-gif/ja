package de.kalorienkompass.ui.scan

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Camera permission as seen by the scanner screen. */
internal class CameraPermissionState(
    val granted: Boolean,
    /** The system will not show the permission dialog any more – only the app settings help. */
    val permanentlyDenied: Boolean,
    /** True once the user was asked (at least once on this screen). */
    val asked: Boolean,
    val request: () -> Unit,
)

/**
 * Tracks the CAMERA permission: requests it once automatically when [autoRequest] is true,
 * detects "permanently denied" and re-checks when the screen resumes (e.g. after returning from
 * the app settings).
 */
@Composable
internal fun rememberCameraPermissionState(autoRequest: Boolean): CameraPermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var granted by remember { mutableStateOf(isCameraPermissionGranted(context)) }
    var requestCount by rememberSaveable { mutableIntStateOf(0) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(false) }

    fun shouldShowRationale(): Boolean = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
    } ?: false

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
        permanentlyDenied = if (isGranted) {
            false
        } else {
            // No rationale after a denial means the system will not ask again ("nicht mehr fragen",
            // or denied twice on Android 11+). Right after the very first dialog the rationale flag is
            // also false when the dialog was merely dismissed, hence the request counter.
            !shouldShowRationale() && (rationaleBeforeRequest || requestCount >= 2)
        }
    }

    val request: () -> Unit = {
        rationaleBeforeRequest = shouldShowRationale()
        requestCount++
        try {
            launcher.launch(Manifest.permission.CAMERA)
        } catch (e: ActivityNotFoundException) {
            Log.w("CameraPermission", "No activity to request the camera permission", e)
            permanentlyDenied = true
        }
    }

    LaunchedEffect(autoRequest) {
        if (autoRequest && !granted && requestCount == 0) request()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nowGranted = isCameraPermissionGranted(context)
        if (nowGranted != granted) granted = nowGranted
        if (nowGranted) permanentlyDenied = false
    }

    return CameraPermissionState(
        granted = granted,
        permanentlyDenied = permanentlyDenied,
        asked = requestCount > 0,
        request = request,
    )
}

internal fun isCameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens this app's page in the system settings (permissions can be granted there). */
internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: ActivityNotFoundException) {
            Log.w("CameraPermission", "No settings app found", e2)
        }
    }
}

/** Explains why the camera is needed; offers the permission dialog, the app settings or manual entry. */
@Composable
internal fun CameraPermissionContent(
    permission: CameraPermissionState,
    onOpenSettings: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title: String
    val text: String
    when {
        permission.permanentlyDenied -> {
            title = "Kamera in den Einstellungen erlauben"
            text = "Du hast den Kamerazugriff abgelehnt. Öffne die App-Einstellungen und erlaube unter " +
                "„Berechtigungen“ → „Kamera“ den Zugriff, um Barcodes zu scannen."
        }
        permission.asked -> {
            title = "Kamerazugriff benötigt"
            text = "Ohne Kamera kann KalorienKompass keine Barcodes scannen. Die Bilder werden nur auf " +
                "deinem Gerät ausgewertet – nichts wird gespeichert oder hochgeladen."
        }
        else -> {
            title = "Kamerazugriff erlauben"
            text = "Damit du Barcodes scannen kannst, braucht KalorienKompass Zugriff auf die Kamera. Die " +
                "Bilder werden nur auf deinem Gerät ausgewertet – nichts wird gespeichert oder hochgeladen."
        }
    }

    ScanInfoContent(
        icon = if (permission.permanentlyDenied) Icons.Filled.NoPhotography else Icons.Filled.PhotoCamera,
        title = title,
        text = text,
        modifier = modifier,
    ) {
        if (permission.permanentlyDenied) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Einstellungen öffnen")
            }
        } else {
            Button(onClick = permission.request, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Kamera erlauben")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Barcode eintippen")
        }
        if (permission.asked && !permission.permanentlyDenied) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenSettings) {
                Text("App-Einstellungen öffnen")
            }
        }
    }
}

/** Shown on devices without a usable camera. */
@Composable
internal fun NoCameraContent(onManualEntry: () -> Unit, modifier: Modifier = Modifier) {
    ScanInfoContent(
        icon = Icons.Filled.VideocamOff,
        title = "Keine Kamera gefunden",
        text = "Dieses Gerät hat keine nutzbare Kamera. Du kannst die Nummer unter dem Barcode aber " +
            "einfach eintippen.",
        modifier = modifier,
    ) {
        Button(onClick = onManualEntry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Barcode eintippen")
        }
    }
}

/** Centered icon, title, text and actions on the themed background (scrollable on small screens). */
@Composable
internal fun ScanInfoContent(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                actions()
            }
        }
    }
}
