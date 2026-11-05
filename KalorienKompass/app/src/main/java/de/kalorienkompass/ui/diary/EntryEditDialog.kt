package de.kalorienkompass.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.DecimalTextField
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatInput
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.parseDecimal

/**
 * Edit dialog for a diary entry: amount (or kcal for quick entries) with live preview,
 * meal chips to move the entry and a delete action (the caller asks for confirmation).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryEditDialog(
    entry: DiaryEntryEntity,
    onDismiss: () -> Unit,
    onSaveAmount: (amount: Double, meal: MealType) -> Unit,
    onSaveQuickKcal: (kcal: Double, meal: MealType) -> Unit,
    onDelete: () -> Unit,
) {
    val quick = entry.isQuickEntry()
    val weightUnit = entry.isWeightUnit()
    var text by rememberSaveable(entry.id) {
        mutableStateOf(formatInput(if (quick) entry.kcal else entry.amount, maxDecimals = 2))
    }
    var meal by rememberSaveable(entry.id) { mutableStateOf(entry.meal) }

    val parsed = parseDecimal(text)
    val valid = parsed != null && if (quick) parsed >= 0.0 else parsed > 0.0
    val preview = if (!quick && parsed != null && parsed > 0.0) scaledEntry(entry, parsed) else entry

    val save = {
        val value = parsed
        if (value != null && valid) {
            if (quick) onSaveQuickKcal(value, meal) else onSaveAmount(value, meal)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                    Text(
                        text = brand,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (quick) {
                    DecimalTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = "Kalorien",
                        suffix = "kcal",
                        onImeAction = save,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Schnelleintrag – die Makronährstoffe bleiben unverändert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DecimalTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = if (weightUnit) "Menge" else "Anzahl",
                        suffix = if (weightUnit) entry.unitLabel.trim() else "×",
                        onImeAction = save,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!weightUnit) {
                        Text(
                            text = "Einheit: ${entry.unitLabel.trim()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${formatKcal(preview.kcal)} kcal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(12.dp))
                        MacroLine(carbs = preview.carbs, protein = preview.protein, fat = preview.fat)
                    }
                }

                Text(text = "Mahlzeit", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealType.entries.forEach { option ->
                        FilterChip(
                            selected = meal == option,
                            onClick = { meal = option },
                            label = { Text("${option.emoji} ${option.label}") },
                        )
                    }
                }

                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Eintrag löschen")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = valid) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** "KH 30 g · E 8 g · F 9 g" with the macro colors. */
@Composable
internal fun MacroLine(
    carbs: Double,
    protein: Double,
    fat: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = AppColors.carbs, fontWeight = FontWeight.SemiBold)) { append("KH ") }
        append("${formatGrams(carbs)} g")
        append("  ·  ")
        withStyle(SpanStyle(color = AppColors.protein, fontWeight = FontWeight.SemiBold)) { append("E ") }
        append("${formatGrams(protein)} g")
        append("  ·  ")
        withStyle(SpanStyle(color = AppColors.fat, fontWeight = FontWeight.SemiBold)) { append("F ") }
        append("${formatGrams(fat)} g")
    }
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
