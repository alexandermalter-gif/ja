package de.kalorienkompass.ui.scan

/**
 * Pure helpers for retail barcodes (EAN-13, EAN-8, UPC-A, UPC-E – all GTIN based).
 * No Android dependencies, unit-tested on the JVM.
 */
internal object BarcodeUtils {

    /** Lengths of GTIN codes that end with a mod-10 check digit (EAN-8, UPC-A, EAN-13, GTIN-14). */
    private val GTIN_LENGTHS: Set<Int> = setOf(8, 12, 13, 14)

    private const val CHECKSUM_ERROR = "Die Prüfziffer passt nicht – bitte kontrolliere die Eingabe."

    /** Keeps only the digits of [input] ("4 006381 333931" → "4006381333931"). */
    fun digitsOnly(input: String): String = input.filter { it in '0'..'9' }

    /** GTIN mod-10 check digit for [body] (all digits of the code except the check digit). */
    fun checkDigit(body: String): Int {
        var sum = 0
        body.forEachIndexed { index, c ->
            // Weights alternate 3, 1, 3, … starting with the digit right next to the check digit.
            val positionFromRight = body.length - index
            sum += (c - '0') * if (positionFromRight % 2 == 1) 3 else 1
        }
        return (10 - sum % 10) % 10
    }

    /** True if [code] is an 8/12/13/14-digit GTIN with a correct check digit. */
    fun hasValidCheckDigit(code: String): Boolean =
        code.length in GTIN_LENGTHS &&
            code.all { it in '0'..'9' } &&
            checkDigit(code.dropLast(1)) == code.last() - '0'

    /**
     * Expands a UPC-E code to the equivalent 12-digit UPC-A.
     * Accepts 6 digits (number system 0 implied), 7 digits (number system + 6 digits) or
     * 8 digits (number system + 6 digits + check digit). Returns null if [code] is not a valid UPC-E.
     */
    fun expandUpcE(code: String): String? {
        if (code.isEmpty() || !code.all { it in '0'..'9' }) return null
        val numberSystem: Char
        val data: String
        val check: Char?
        when (code.length) {
            6 -> {
                numberSystem = '0'
                data = code
                check = null
            }
            7 -> {
                numberSystem = code[0]
                data = code.substring(1)
                check = null
            }
            8 -> {
                numberSystem = code[0]
                data = code.substring(1, 7)
                check = code[7]
            }
            else -> return null
        }
        if (numberSystem != '0' && numberSystem != '1') return null

        val d = data
        val body = when (d[5]) {
            '0', '1', '2' -> "$numberSystem${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "$numberSystem${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "$numberSystem${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "$numberSystem${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        val computed = checkDigit(body)
        if (check != null && check - '0' != computed) return null
        return body + computed
    }

    /**
     * Canonical lookup code for a barcode detected by the camera, or null if the value is not a
     * plausible retail code (the frame is then simply ignored). UPC-E is expanded to UPC-A.
     */
    fun normalizeScanned(rawValue: String?, isUpcE: Boolean): String? {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isEmpty() || !raw.all { it in '0'..'9' }) return null
        if (isUpcE && raw.length <= 8) return expandUpcE(raw)
        return raw.takeIf { hasValidCheckDigit(it) }?.let(::canonicalGtin)
    }

    /** Result of checking a manually typed barcode. */
    sealed interface ManualInput {
        data class Valid(val code: String) : ManualInput

        /** [message] is German and user-facing. */
        data class Invalid(val message: String) : ManualInput
    }

    /** Validates a manually typed barcode (spaces and dashes are ignored). */
    fun validateManual(input: String): ManualInput {
        val digits = digitsOnly(input)
        return when {
            digits.isEmpty() -> ManualInput.Invalid("Bitte gib die Ziffern unter dem Barcode ein.")
            digits.length !in GTIN_LENGTHS -> ManualInput.Invalid(
                "Ein Barcode hat 8, 12 oder 13 Ziffern (eingegeben: ${digits.length}).",
            )
            hasValidCheckDigit(digits) -> ManualInput.Valid(canonicalGtin(digits))
            // 8 digits that are not a valid EAN-8 may still be a UPC-E code.
            digits.length == 8 -> expandUpcE(digits)?.let { ManualInput.Valid(it) }
                ?: ManualInput.Invalid(CHECKSUM_ERROR)
            else -> ManualInput.Invalid(CHECKSUM_ERROR)
        }
    }

    /**
     * Groups the digits like they are printed under the bars: "4 006381 333931" (EAN-13),
     * "9638 5074" (EAN-8), "0 36000 29145 2" (UPC-A). Uses no-break spaces so the code never wraps.
     */
    fun formatForDisplay(code: String): String {
        if (code.isEmpty() || !code.all { it in '0'..'9' }) return code
        val s = NBSP
        return when (code.length) {
            13 -> "${code[0]}$s${code.substring(1, 7)}$s${code.substring(7)}"
            8 -> "${code.substring(0, 4)}$s${code.substring(4)}"
            12 -> "${code[0]}$s${code.substring(1, 6)}$s${code.substring(6, 11)}$s${code[11]}"
            else -> code
        }
    }

    /** No-break space used by [formatForDisplay]. */
    const val NBSP: Char = ' '

    /** GTIN-14 with a leading zero is the same product as the EAN-13 behind it. */
    private fun canonicalGtin(code: String): String =
        if (code.length == 14 && code.startsWith("0")) code.substring(1) else code
}
