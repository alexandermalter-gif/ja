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

/**
 * Number field that accepts digits plus one "," or "." (max. [MAX_INPUT_DECIMALS] decimals, pasted
 * spaces are removed). Parse the value with [de.kalorienkompass.util.parseDecimal].
 */
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
            sanitizeDecimalInput(old = value, new = input)?.let(onValueChange)
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

/** Max. number of decimals that can be typed into a decimal field. */
internal const val MAX_INPUT_DECIMALS = 3

/** Max. length of a decimal field's text. */
internal const val MAX_DECIMAL_INPUT_LENGTH = 10

/** Max. length of an integer field's text (fits into Int). */
internal const val MAX_INTEGER_INPUT_LENGTH = 9

/**
 * Digits plus at most one decimal separator ("," or "."), at most [maxDecimals] decimals (unlimited
 * by default, also used directly by feature screens) and [maxLength] characters. "" and a lone
 * separator are acceptable while typing (they parse to null).
 */
internal fun isAcceptableDecimalInput(
    input: String,
    maxDecimals: Int = Int.MAX_VALUE,
    maxLength: Int = MAX_DECIMAL_INPUT_LENGTH,
): Boolean {
    if (input.length > maxLength) return false
    var separators = 0
    var decimals = 0
    for (c in input) {
        when {
            c in '0'..'9' -> if (separators > 0) decimals++
            c == ',' || c == '.' -> separators++
            else -> return false
        }
    }
    return separators <= 1 && decimals <= maxDecimals
}

/** Digits only; max. [MAX_INTEGER_INPUT_LENGTH] characters. */
internal fun isAcceptableIntegerInput(input: String): Boolean =
    input.length <= MAX_INTEGER_INPUT_LENGTH && input.all { it in '0'..'9' }

/**
 * Filters an edit of a decimal field from [old] to [new]: whitespace (e.g. from pasted text) is
 * removed, then [isAcceptableDecimalInput] with at most [MAX_INPUT_DECIMALS] decimals must hold.
 * Returns the text to use, or null to reject the edit (the field keeps [old]). An edit that only
 * contains valid characters and does not make the text longer is always accepted, so a pre-filled
 * value with more decimals or characters can still be corrected.
 */
internal fun sanitizeDecimalInput(old: String, new: String): String? {
    val text = new.filterNot { it.isWhitespace() }
    if (isAcceptableDecimalInput(text, maxDecimals = MAX_INPUT_DECIMALS)) return text
    val onlyValidChars = isAcceptableDecimalInput(text, maxLength = Int.MAX_VALUE)
    return if (onlyValidChars && text.length <= old.length) text else null
}

/** Like [sanitizeDecimalInput] for integer fields (digits only). */
internal fun sanitizeIntegerInput(old: String, new: String): String? {
    val text = new.filterNot { it.isWhitespace() }
    if (isAcceptableIntegerInput(text)) return text
    return if (text.all { it in '0'..'9' } && text.length <= old.length) text else null
}
