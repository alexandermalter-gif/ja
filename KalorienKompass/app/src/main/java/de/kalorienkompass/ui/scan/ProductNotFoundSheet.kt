package de.kalorienkompass.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Bottom sheet "Produkt nicht gefunden" (spec §11 SCAN + addendum §13): three clearly separated
 * ways to create the product – photo of the nutrition table (AI), web research (AI) or manual entry –
 * plus "Erneut scannen". Each option has its own callback.
 *
 * The sheet animates away before a callback runs; swiping it away or pressing back counts as
 * [onRescan]. At most one callback is invoked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductNotFoundSheet(
    code: String,
    aiKeyAvailable: Boolean,
    rescanLabel: String,
    onPhotoNutritionLabel: () -> Unit,
    onWebSearch: () -> Unit,
    onManualEntry: () -> Unit,
    onRescan: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var actionTaken by remember { mutableStateOf(false) }

    // Hides the sheet with its animation, then runs the action (once, and only if not cancelled).
    val closeThen: (() -> Unit) -> Unit = { action ->
        if (!actionTaken) {
            actionTaken = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                if (cause == null) action()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!actionTaken) {
                actionTaken = true
                onRescan()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Produkt nicht gefunden",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BarcodeChip(code = code)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Diesen Barcode kennen weder deine Lebensmittel noch Open Food Facts. " +
                    "Wie möchtest du das Produkt anlegen?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            val keyHint = if (aiKeyAvailable) null else "Benötigt deinen API-Schlüssel (Einstellungen)"

            NotFoundOption(
                icon = Icons.Filled.DocumentScanner,
                title = "Nährwerttabelle fotografieren",
                text = "Die KI liest die Nährwerte direkt von der Verpackung ab.",
                accent = MaterialTheme.colorScheme.tertiary,
                badge = "KI",
                hint = keyHint,
                onClick = { closeThen(onPhotoNutritionLabel) },
            )
            Spacer(Modifier.height(10.dp))
            NotFoundOption(
                icon = Icons.Filled.TravelExplore,
                title = "Im Web suchen (KI)",
                text = "Die KI recherchiert Name und Nährwerte zum Barcode im Internet.",
                accent = MaterialTheme.colorScheme.tertiary,
                hint = keyHint,
                onClick = { closeThen(onWebSearch) },
            )
            Spacer(Modifier.height(10.dp))
            NotFoundOption(
                icon = Icons.Filled.EditNote,
                title = "Manuell eingeben",
                text = "Nährwerte selbst von der Packung abtippen.",
                accent = MaterialTheme.colorScheme.primary,
                onClick = { closeThen(onManualEntry) },
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Von der KI gelesene oder recherchierte Werte landen zuerst im Formular – " +
                        "du prüfst sie, bevor etwas gespeichert wird.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = { closeThen(onRescan) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(rescanLabel)
            }
        }
    }
}

/** One clearly separated, tappable option of the "not found" sheet. */
@Composable
private fun NotFoundOption(
    icon: ImageVector,
    title: String,
    text: String,
    accent: Color,
    onClick: () -> Unit,
    badge: String? = null,
    hint: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The barcode in print grouping ("4 006381 333931") on a small monospace chip. */
@Composable
internal fun BarcodeChip(
    code: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = BarcodeUtils.formatForDisplay(code),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = contentColor,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
