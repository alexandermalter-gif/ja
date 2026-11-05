package de.kalorienkompass.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val MAX_BARCODE_DIGITS = 14

/**
 * Dialog for typing the number printed under a barcode. Validates length and check digit;
 * [onConfirm] receives the canonical code (see [BarcodeUtils.validateManual]) and is followed by
 * [onDismiss].
 */
@Composable
internal fun ManualBarcodeDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val check = BarcodeUtils.validateManual(text)
    val errorMessage = (check as? BarcodeUtils.ManualInput.Invalid)?.message?.takeIf { attempted }

    val submit: () -> Unit = {
        when (val result = BarcodeUtils.validateManual(text)) {
            is BarcodeUtils.ManualInput.Valid -> {
                onConfirm(result.code)
                onDismiss()
            }
            is BarcodeUtils.ManualInput.Invalid -> attempted = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
        title = { Text("Barcode eingeben") },
        text = {
            Column {
                Text(
                    text = "Tippe die Ziffern unter den Strichen ab – meist 13 Stellen (EAN), manchmal 8 oder 12.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        val digits = BarcodeUtils.digitsOnly(input).take(MAX_BARCODE_DIGITS)
                        if (digits != text) {
                            text = digits
                            attempted = false
                        }
                    },
                    label = { Text("Barcode-Nummer") },
                    placeholder = { Text("z. B. 4006381333931") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = {
                        Text(
                            errorMessage ?: when (text.length) {
                                0 -> "8, 12 oder 13 Ziffern"
                                1 -> "1 Ziffer"
                                else -> "${text.length} Ziffern"
                            },
                        )
                    },
                    trailingIcon = if (text.isNotEmpty()) {
                        {
                            IconButton(onClick = { text = ""; attempted = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Eingabe löschen")
                            }
                        }
                    } else {
                        null
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, letterSpacing = 1.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            LaunchedEffect(Unit) {
                delay(150)
                runCatching { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = text.isNotEmpty()) {
                Text("Suchen")
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
