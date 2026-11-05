package de.kalorienkompass.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.ui.components.isAcceptableDecimalInput
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDate
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatInput
import de.kalorienkompass.util.parseDecimal
import de.kalorienkompass.util.weekdayLong
import kotlinx.coroutines.delay
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/** Material3 date pickers work with UTC midnight millis. */
internal fun LocalDate.toUtcMillis(): Long = toEpochDay() * MILLIS_PER_DAY

internal fun utcMillisToLocalDate(millis: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(millis, MILLIS_PER_DAY))

/** Only today and past days can be picked (no future measurements). */
@OptIn(ExperimentalMaterial3Api::class)
private class UpToTodaySelectableDates(today: LocalDate) : SelectableDates {
    private val lastMillis = today.toUtcMillis()
    private val lastYear = today.year

    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= lastMillis

    override fun isSelectableYear(year: Int): Boolean = year <= lastYear
}

/**
 * Add / edit dialog for a body value: optional type chips, date (Material3 DatePickerDialog) and
 * value with plausibility check. Confirming calls [onSave] and then [onDismiss].
 *
 * @param prefillFor value to prefill when the type is switched (add mode, before the user typed)
 * @param existingManualValue manual value already stored for type + day → shown as "wird ersetzt"
 * @param readOnlyHint if set, the entry can't be edited (e.g. imported from Health Connect)
 * @param onDelete if set, a delete icon is shown (edit mode)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MeasurementDialog(
    title: String,
    initialType: MeasurementType,
    typeSelectable: Boolean,
    initialDate: LocalDate,
    initialValue: Double?,
    onSave: (type: MeasurementType, date: LocalDate, value: Double) -> Unit,
    onDismiss: () -> Unit,
    isEdit: Boolean = false,
    prefillFor: (MeasurementType) -> Double? = { null },
    existingManualValue: (MeasurementType, LocalDate) -> Double? = { _, _ -> null },
    readOnlyHint: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    val today = remember { LocalDate.now() }
    var type by remember { mutableStateOf(initialType) }
    var date by remember { mutableStateOf(if (initialDate > today) today else initialDate) }
    var field by remember { mutableStateOf(fieldValueFor(initialValue)) }
    var edited by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val readOnly = readOnlyHint != null

    val parsed = parseDecimal(field.text)
    val allowed = type.plausibleRange()
    val error: String? = when {
        field.text.isBlank() -> null
        parsed == null -> "Bitte eine gültige Zahl eingeben"
        parsed !in allowed ->
            "Bitte einen Wert zwischen ${formatGrams(allowed.start)} und ${formatGrams(allowed.endInclusive)} ${type.unit} eingeben"
        else -> null
    }
    val valid = !readOnly && parsed != null && parsed in allowed
    val replaced: Double? = if (!isEdit && !readOnly) existingManualValue(type, date) else null
    val save: () -> Unit = {
        if (!readOnly && parsed != null && parsed in allowed) {
            onSave(type, date, parsed)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Eintrag löschen",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (typeSelectable) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeasurementType.entries.forEach { option ->
                            FilterChip(
                                selected = option == type,
                                onClick = {
                                    if (option != type) {
                                        type = option
                                        if (!edited) field = fieldValueFor(prefillFor(option))
                                    }
                                },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
                DateSelectorField(
                    date = date,
                    today = today,
                    enabled = !readOnly,
                    onClick = { showDatePicker = true },
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { input ->
                        if (isAcceptableDecimalInput(input.text)) {
                            field = input
                            edited = true
                        }
                    },
                    label = { Text(type.label) },
                    suffix = { Text(type.unit) },
                    singleLine = true,
                    enabled = !readOnly,
                    isError = error != null,
                    supportingText = when {
                        error != null -> {
                            { Text(error) }
                        }
                        replaced != null -> {
                            { Text("Ersetzt den Eintrag dieses Tages (${formatMeasurement(replaced, type)}).") }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (readOnlyHint != null) {
                    HintBox(readOnlyHint)
                } else {
                    LaunchedEffect(Unit) {
                        delay(150)
                        runCatching { focusRequester.requestFocus() }
                    }
                }
            }
        },
        confirmButton = {
            if (readOnly) {
                TextButton(onClick = onDismiss) { Text("Schließen") }
            } else {
                TextButton(onClick = save, enabled = valid) { Text("Speichern") }
            }
        },
        dismissButton = if (readOnly) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMillis(),
            yearRange = (today.year - 50)..today.year,
            selectableDates = remember(today) { UpToTodaySelectableDates(today) },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { date = utcMillisToLocalDate(it) }
                        showDatePicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) {
                    Text("Übernehmen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        text = "Datum wählen",
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                    )
                },
            )
        }
    }
}

/** Looks like an outlined text field; tap opens the date picker. */
@Composable
private fun DateSelectorField(date: LocalDate, today: LocalDate, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.outline, shape)
            .clickable(enabled = enabled, onClickLabel = "Datum wählen", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint = if (enabled) colors.primary else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Datum", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(dialogDateText(date, today), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        }
        if (enabled) {
            Text("Ändern", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

@Composable
private fun HintBox(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.tertiary.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
    }
}

/** "Heute, 05.11.2026" / "Montag, 03.11.2026" */
internal fun dialogDateText(date: LocalDate, today: LocalDate): String {
    val relative = dayLabel(date, today)
    val prefix = if (relative == "Heute" || relative == "Gestern") relative else weekdayLong(date)
    return "$prefix, ${formatDate(date)}"
}

private fun fieldValueFor(value: Double?): TextFieldValue {
    val text = value?.let { formatInput(it, maxDecimals = 2) }.orEmpty()
    return TextFieldValue(text = text, selection = TextRange(0, text.length))
}
