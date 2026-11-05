package de.kalorienkompass.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.kalorienkompass.util.formatInput
import de.kalorienkompass.util.parseDecimal
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * Dialog with a single number field (pre-selected initial value, keyboard opens automatically).
 * Confirming calls [onConfirm] with the parsed value (≥ 0) and then [onDismiss].
 */
@Composable
fun NumberInputDialog(
    title: String,
    initial: Double?,
    suffix: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    decimals: Boolean = true,
) {
    val initialText = remember(initial, decimals) {
        when {
            initial == null -> ""
            decimals -> formatInput(initial, maxDecimals = 2)
            else -> initial.roundToLong().toString()
        }
    }
    var field by remember {
        mutableStateOf(TextFieldValue(text = initialText, selection = TextRange(0, initialText.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val parsed = parseDecimal(field.text)
    val valid = parsed != null && parsed >= 0.0
    val confirm = {
        if (parsed != null && parsed >= 0.0) {
            onConfirm(if (decimals) parsed else parsed.roundToLong().toDouble())
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { input ->
                    val ok = if (decimals) isAcceptableDecimalInput(input.text) else isAcceptableIntegerInput(input.text)
                    if (ok) field = input
                },
                singleLine = true,
                suffix = { Text(suffix) },
                isError = field.text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            LaunchedEffect(Unit) {
                delay(120)
                runCatching { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = valid) {
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
