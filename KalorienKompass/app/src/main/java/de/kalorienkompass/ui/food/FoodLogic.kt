package de.kalorienkompass.ui.food

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatInput
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.parseDecimal
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/*
 * Pure (Android-free) helpers shared by the food screens. Unit-tested in FoodLogicTest.
 */

/** "g" or "ml" for a food. */
internal val FoodEntity.unitSymbol: String get() = if (unit.equals("ml", ignoreCase = true)) "ml" else "g"

/** "52 kcal / 100 g" */
internal fun per100Label(food: FoodEntity): String = "${formatKcal(food.kcal)} kcal / 100 ${food.unitSymbol}"

/** Nutrient amount for tables: null → "–", small values with up to 2 decimals ("0,11"), else max. 1 decimal. */
internal fun formatNutrient(value: Double?): String = when {
    value == null || value.isNaN() -> "–"
    value > 0.0 && value < 1.0 -> formatInput(value, maxDecimals = 2)
    else -> formatGrams(value)
}

// -------------------------------------------------------------------------------------------------
// Amount units (food detail)
// -------------------------------------------------------------------------------------------------

/** One choice of the unit dropdown on the food detail screen. */
internal data class AmountUnitOption(
    /** "base" or "portion:<index>" */
    val key: String,
    /** dropdown label, e.g. "Gramm (g)", "Scheibe (50 g)" */
    val label: String,
    /** grams (or ml) per 1 unit; 1.0 for the base unit */
    val gramsPerUnit: Double,
    /** unitLabel stored in the diary ("g", "ml" or the portion label) */
    val diaryLabel: String,
    val isBase: Boolean,
)

internal const val BASE_UNIT_KEY = "base"
private const val PORTION_KEY_PREFIX = "portion:"

private val QUANTITY_IN_NAME = Regex("""\d+(?:[.,]\d+)?\s*(g|ml|kg|l)\b""", RegexOption.IGNORE_CASE)
private val LEADING_ONE = Regex("""^1\s*(x|×)?\s+""", RegexOption.IGNORE_CASE)

/**
 * Readable label for a portion, used in the dropdown and as diary unit label:
 * "1 mittelgroßer Apfel" (150 g) → "mittelgroßer Apfel (150 g)", "Portion (30 g)" stays,
 * "1 Banane (mittel, ohne Schale)" (120 g) → "Banane (mittel, ohne Schale) · 120 g".
 */
internal fun portionLabel(portion: FoodPortion, unit: String): String {
    val unitSymbol = if (unit.equals("ml", ignoreCase = true)) "ml" else "g"
    val name = portion.name.trim().replace(LEADING_ONE, "").ifBlank { "Portion" }
    val amount = "${formatGrams(portion.grams)} $unitSymbol"
    return when {
        QUANTITY_IN_NAME.containsMatchIn(name) -> name
        name.endsWith(")") -> "$name · $amount"
        else -> "$name ($amount)"
    }
}

/** Base unit first, then every valid portion of [food]. */
internal fun amountUnitOptions(food: FoodEntity): List<AmountUnitOption> {
    val base = AmountUnitOption(
        key = BASE_UNIT_KEY,
        label = if (food.unitSymbol == "ml") "Milliliter (ml)" else "Gramm (g)",
        gramsPerUnit = 1.0,
        diaryLabel = food.unitSymbol,
        isBase = true,
    )
    val portions = food.portions.mapIndexedNotNull { index, portion ->
        if (portion.grams <= 0.0 || portion.grams.isNaN()) return@mapIndexedNotNull null
        val label = portionLabel(portion, food.unit)
        AmountUnitOption(
            key = "$PORTION_KEY_PREFIX$index",
            label = label,
            gramsPerUnit = portion.grams,
            diaryLabel = label,
            isBase = false,
        )
    }
    return listOf(base) + portions
}

/** Whole packages above this size are never the default amount (e.g. a 400 g jar of spread). */
private const val MAX_DEFAULT_PACKAGE_GRAMS = 300.0

/**
 * Default amount: 1 × the first portion if there is one (a big whole package is skipped),
 * otherwise 100 g/ml. Returns (amount text, unit key).
 */
internal fun defaultAmount(food: FoodEntity, options: List<AmountUnitOption> = amountUnitOptions(food)): Pair<String, String> {
    val firstPortion = options.firstOrNull { !it.isBase }
    val usable = firstPortion != null &&
        !(firstPortion.label.startsWith("Packung") && firstPortion.gramsPerUnit > MAX_DEFAULT_PACKAGE_GRAMS)
    return if (firstPortion != null && usable) "1" to firstPortion.key else "100" to BASE_UNIT_KEY
}

/**
 * New amount text when the unit changes: portions start at 1, the base unit takes over the
 * current total grams (rounded), or 100 if there is no valid amount yet.
 */
internal fun amountForUnitChange(currentGrams: Double?, newOption: AmountUnitOption): String = when {
    !newOption.isBase -> "1"
    currentGrams != null && currentGrams > 0.0 -> formatInput(currentGrams, maxDecimals = 0)
    else -> "100"
}

/** Valid amount (> 0, sensible maximum) or null. */
internal fun parseAmount(text: String): Double? =
    parseDecimal(text)?.takeIf { it > 0.0 && it <= 100_000.0 }

// -------------------------------------------------------------------------------------------------
// Custom food form
// -------------------------------------------------------------------------------------------------

@Serializable
internal data class PortionInput(val name: String = "", val grams: String = "")

/** All inputs of the custom food form as typed (German decimal comma allowed). */
@Serializable
internal data class CustomFoodForm(
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val unit: String = "g",
    val kcal: String = "",
    val fat: String = "",
    val carbs: String = "",
    val sugar: String = "",
    val fiber: String = "",
    val protein: String = "",
    val salt: String = "",
    val portions: List<PortionInput> = emptyList(),
)

internal enum class FormField { NAME, BRAND, BARCODE, KCAL, FAT, CARBS, SUGAR, FIBER, PROTEIN, SALT }

internal data class FormValidation(
    val fieldErrors: Map<FormField, String> = emptyMap(),
    val portionErrors: Map<Int, String> = emptyMap(),
    /** blocking error that concerns several fields (e.g. macros > 100 g) */
    val generalError: String? = null,
    /** non-blocking hint (e.g. kcal do not match the macros) */
    val warning: String? = null,
) {
    val isValid: Boolean get() = fieldErrors.isEmpty() && portionErrors.isEmpty() && generalError == null
}

internal const val MAX_KCAL_PER_100 = 950.0
private const val MAX_NAME_LENGTH = 120

/** Energy from macros (kcal): 9 × fat + 4 × carbs + 4 × protein + 2 × fiber. */
internal fun estimatedKcal(fat: Double, carbs: Double, protein: Double, fiber: Double = 0.0): Double =
    9.0 * fat + 4.0 * carbs + 4.0 * protein + 2.0 * fiber

internal fun validateCustomFood(form: CustomFoodForm): FormValidation {
    val errors = LinkedHashMap<FormField, String>()

    val name = form.name.trim()
    when {
        name.isEmpty() -> errors[FormField.NAME] = "Bitte gib einen Namen ein."
        name.length > MAX_NAME_LENGTH -> errors[FormField.NAME] = "Höchstens $MAX_NAME_LENGTH Zeichen."
    }
    if (form.brand.trim().length > MAX_NAME_LENGTH) errors[FormField.BRAND] = "Höchstens $MAX_NAME_LENGTH Zeichen."

    val barcode = form.barcode.trim()
    if (barcode.isNotEmpty() && (!barcode.all { it in '0'..'9' } || barcode.length !in 6..14)) {
        errors[FormField.BARCODE] = "Der Barcode besteht aus 6 bis 14 Ziffern."
    }

    fun number(field: FormField, text: String, required: Boolean, max: Double, unit: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            if (required) errors[field] = "Pflichtfeld"
            return null
        }
        val value = parseDecimal(trimmed)
        when {
            value == null || value < 0.0 -> errors[field] = "Ungültige Zahl"
            value > max -> errors[field] = "Höchstens ${formatGrams(max)} $unit pro 100 ${unitOf(form)}"
            else -> return value
        }
        return null
    }

    val kcal = number(FormField.KCAL, form.kcal, required = true, max = MAX_KCAL_PER_100, unit = "kcal")
    val fat = number(FormField.FAT, form.fat, required = true, max = 100.0, unit = "g")
    val carbs = number(FormField.CARBS, form.carbs, required = true, max = 100.0, unit = "g")
    val sugar = number(FormField.SUGAR, form.sugar, required = false, max = 100.0, unit = "g")
    val fiber = number(FormField.FIBER, form.fiber, required = false, max = 100.0, unit = "g")
    val protein = number(FormField.PROTEIN, form.protein, required = true, max = 100.0, unit = "g")
    number(FormField.SALT, form.salt, required = false, max = 100.0, unit = "g")

    if (sugar != null && carbs != null && sugar > carbs + 0.05 && FormField.SUGAR !in errors) {
        errors[FormField.SUGAR] = "Zucker ist Teil der Kohlenhydrate und kann nicht mehr sein."
    }

    var generalError: String? = null
    if (fat != null && carbs != null && protein != null) {
        val sum = fat + carbs + protein + (fiber ?: 0.0)
        if (sum > 102.0) {
            generalError = "Fett, Kohlenhydrate, Ballaststoffe und Eiweiß ergeben zusammen mehr als 100 g pro 100 ${unitOf(form)}. " +
                "Bitte prüfe die Werte."
        }
    }

    var warning: String? = null
    if (kcal != null && fat != null && carbs != null && protein != null && generalError == null) {
        val estimate = estimatedKcal(fat, carbs, protein, fiber ?: 0.0)
        if (abs(kcal - estimate) > max(40.0, 0.2 * max(kcal, estimate))) {
            warning = "Die Kalorien passen nicht ganz zu den Nährwerten (berechnet: ca. ${formatKcal(estimate)} kcal). " +
                "Bitte prüfe die Angaben – bei alkoholischen Getränken ist ein Unterschied normal."
        }
    }

    val portionErrors = LinkedHashMap<Int, String>()
    form.portions.forEachIndexed { index, portion ->
        val portionName = portion.name.trim()
        val gramsText = portion.grams.trim()
        if (portionName.isEmpty() && gramsText.isEmpty()) return@forEachIndexed
        val grams = parseDecimal(gramsText)
        when {
            portionName.isEmpty() -> portionErrors[index] = "Bitte gib der Portion einen Namen."
            gramsText.isEmpty() -> portionErrors[index] = "Bitte gib die Menge der Portion an."
            grams == null || grams <= 0.0 || grams > 10_000.0 -> portionErrors[index] = "Ungültige Menge."
        }
    }

    return FormValidation(errors, portionErrors, generalError, warning)
}

private fun unitOf(form: CustomFoodForm): String = if (form.unit.equals("ml", ignoreCase = true)) "ml" else "g"

/**
 * Builds the entity to save from a VALID form. [original] (when editing) keeps id, source,
 * favorite flag, usage statistics, image etc.; new foods get source CUSTOM.
 */
internal fun CustomFoodForm.toFoodEntity(original: FoodEntity?): FoodEntity {
    fun req(text: String): Double = parseDecimal(text.trim()) ?: 0.0
    fun opt(text: String): Double? = text.trim().takeIf { it.isNotEmpty() }?.let { parseDecimal(it) }

    val portions = portions.mapNotNull { input ->
        val name = input.name.trim()
        val grams = parseDecimal(input.grams.trim())
        if (name.isEmpty() || grams == null || grams <= 0.0) null else FoodPortion(name, grams)
    }
    val base = original ?: FoodEntity(
        name = "",
        kcal = 0.0,
        protein = 0.0,
        carbs = 0.0,
        fat = 0.0,
        source = FoodSource.CUSTOM,
    )
    return base.copy(
        name = name.trim(),
        brand = brand.trim().takeIf { it.isNotEmpty() },
        barcode = barcode.trim().takeIf { it.isNotEmpty() },
        kcal = req(kcal),
        fat = req(fat),
        carbs = req(carbs),
        sugar = opt(sugar),
        fiber = opt(fiber),
        protein = req(protein),
        salt = opt(salt),
        unit = unitOf(this),
        portions = portions,
    )
}

/** Form values of an existing food (numbers formatted for editing, e.g. "12,5"). */
internal fun FoodEntity.toForm(): CustomFoodForm = CustomFoodForm(
    name = name,
    brand = brand.orEmpty(),
    barcode = barcode.orEmpty(),
    unit = unitSymbol,
    kcal = formatInput(kcal, maxDecimals = 1),
    fat = formatInput(fat, maxDecimals = 2),
    carbs = formatInput(carbs, maxDecimals = 2),
    sugar = sugar?.let { formatInput(it, maxDecimals = 2) }.orEmpty(),
    fiber = fiber?.let { formatInput(it, maxDecimals = 2) }.orEmpty(),
    protein = formatInput(protein, maxDecimals = 2),
    salt = salt?.let { formatInput(it, maxDecimals = 3) }.orEmpty(),
    portions = portions.map { PortionInput(it.name, formatInput(it.grams, maxDecimals = 1)) },
)

/**
 * Form with values found elsewhere (photo of the nutrition table, web research; per 100 g/ml).
 * Only non-null (and valid) values replace the current input. A serving becomes the first portion.
 */
internal fun CustomFoodForm.withPrefill(
    name: String? = null,
    brand: String? = null,
    unit: String? = null,
    kcal: Double? = null,
    fat: Double? = null,
    carbs: Double? = null,
    sugar: Double? = null,
    fiber: Double? = null,
    protein: Double? = null,
    salt: Double? = null,
    servingName: String? = null,
    servingGrams: Double? = null,
): CustomFoodForm {
    fun num(value: Double?, decimals: Int): String? =
        value?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 }?.let { formatInput(it, maxDecimals = decimals) }

    return copy(
        name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH) ?: this.name,
        brand = brand?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH) ?: this.brand,
        unit = normalizeUnit(unit) ?: this.unit,
        kcal = num(kcal, 1) ?: this.kcal,
        fat = num(fat, 2) ?: this.fat,
        carbs = num(carbs, 2) ?: this.carbs,
        sugar = num(sugar, 2) ?: this.sugar,
        fiber = num(fiber, 2) ?: this.fiber,
        protein = num(protein, 2) ?: this.protein,
        salt = num(salt, 3) ?: this.salt,
        portions = prefilledPortions(portions, servingName, servingGrams),
    )
}

private fun prefilledPortions(current: List<PortionInput>, servingName: String?, servingGrams: Double?): List<PortionInput> {
    val grams = servingGrams?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 && it <= 10_000.0 }
    val name = servingName?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
    val filled = current.filter { it.name.isNotBlank() || it.grams.isNotBlank() }
    if (grams == null) {
        // name only: rename the first portion (or start one)
        val portionName = name ?: return current
        return if (filled.isEmpty()) {
            listOf(PortionInput(name = portionName))
        } else {
            listOf(filled.first().copy(name = portionName)) + filled.drop(1)
        }
    }
    val portion = PortionInput(name = name ?: "Portion", grams = formatInput(grams, maxDecimals = 1))
    return listOf(portion) + filled.filterNot { parseDecimal(it.grams) == grams }
}

/** "g"/"ml" from free text ("ml", "Milliliter", "l" → "ml"; everything else → "g"), null if blank. */
internal fun normalizeUnit(unit: String?): String? {
    val u = unit?.trim()?.lowercase() ?: return null
    if (u.isEmpty()) return null
    return when (u) {
        "ml", "milliliter", "millilitre", "l", "liter", "litre", "cl", "dl" -> "ml"
        else -> "g"
    }
}
