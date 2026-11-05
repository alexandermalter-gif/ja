package de.kalorienkompass.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Number field that accepts digits plus one "," or "." (parse with [de.kalorienkompass.util.parseDecimal]). */
@Composable
fun DecimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (isAcceptableDecimalInput(input)) onValueChange(input)
        },
        label = { Text(label) },
        suffix = if (suffix != null) {
            { Text(suffix) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = {
                onImeAction()
                defaultKeyboardAction(ImeAction.Done)
            },
            onNext = {
                onImeAction()
                defaultKeyboardAction(ImeAction.Next)
            },
            onGo = { onImeAction() },
            onSearch = { onImeAction() },
            onSend = { onImeAction() },
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    )
}

/** Digits plus at most one decimal separator ("," or "."); max. 10 characters. */
internal fun isAcceptableDecimalInput(input: String): Boolean {
    if (input.length > 10) return false
    var separators = 0
    for (c in input) {
        when {
            c in '0'..'9' -> Unit
            c == ',' || c == '.' -> separators++
            else -> return false
        }
    }
    return separators <= 1
}

/** Digits only; max. 9 characters. */
internal fun isAcceptableIntegerInput(input: String): Boolean =
    input.length <= 9 && input.all { it in '0'..'9' }
