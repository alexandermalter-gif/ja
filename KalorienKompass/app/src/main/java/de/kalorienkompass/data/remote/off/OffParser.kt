package de.kalorienkompass.data.remote.off

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure, lenient parser for Open Food Facts API responses (no Android, no I/O).
 *
 * Open Food Facts data is crowd-sourced, so every field may be missing, empty, a string instead of
 * a number ("12,5", "<0.5") or an array instead of a string. All nutrition values are per 100 g
 * (or 100 ml). Products without a name or without any energy value are skipped (null).
 */
object OffParser {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** kJ per kcal. */
    private const val KJ_PER_KCAL = 4.184

    /** Nothing has more than ~900 kcal per 100 g (pure fat); larger values are kJ entered as kcal. */
    private const val MAX_PLAUSIBLE_KCAL = 950.0

    private const val MAX_SERVING_GRAMS = 5_000.0
    private const val MAX_PACKAGE_GRAMS = 25_000.0
    private const val MAX_PORTION_NAME_LENGTH = 48

    private val LEADING_NUMBER = Regex("""^[<>~≈≤≥]?\s*(\d+(?:[.,]\d+)?)""")
    private val QUANTITY_UNIT = Regex(
        """(\d+(?:[.,]\d+)?)\s*(kilogramm|kilogram|kg|gramm|grams|gram|gr|g|mg|milliliter|millilitre|ml|cl|dl|liter|litre|litres|liters|ltr|lt|l)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PLAIN_QUANTITY = Regex(
        """^\d+(?:[.,]\d+)?\s*(g|gr|gram|grams|gramm|ml|cl|l)\.?$""",
        RegexOption.IGNORE_CASE,
    )
    private val GENERIC_PORTION = Regex(
        """^(1\s*)?(portion|portions|serving|servings|portion\(s\)|serving\(s\))\s*\(?\s*\d+(?:[.,]\d+)?\s*(g|gr|gram|grams|gramm|ml|cl|l)\s*\)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val CONTAINS_QUANTITY = Regex("""\d+(?:[.,]\d+)?\s*(g|gr|gram|grams|gramm|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val DECIMAL_POINT = Regex("""(\d)\.(\d)""")
    private val WHITESPACE = Regex("""\s+""")

    // ---------------------------------------------------------------------------------------------
    // Responses
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses the response of `GET /api/v2/product/{code}` (`{"status":1,"product":{...}}`).
     * Returns null for "product not found" (status 0) and for unusable products.
     * [requestedBarcode] is used when the product itself has no `code`.
     * Throws [kotlinx.serialization.SerializationException] (an [IllegalArgumentException]) if [body] is not JSON.
     */
    fun parseProductResponse(body: String, requestedBarcode: String? = null): FoodEntity? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        if (!isFoundStatus(root["status"])) return null
        val product = root["product"] as? JsonObject ?: return null
        return parseProduct(product, fallbackBarcode = root.string("code") ?: requestedBarcode)
    }

    /**
     * Parses the response of `GET /cgi/search.pl?...&json=1` (`{"products":[...]}`).
     * Unusable products are skipped; duplicates (same barcode) are removed.
     * Throws [kotlinx.serialization.SerializationException] if [body] is not JSON.
     */
    fun parseSearchResponse(body: String): List<FoodEntity> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val products = root["products"] as? JsonArray ?: return emptyList()
        val seenCodes = HashSet<String>()
        return products.mapNotNull { element ->
            val food = (element as? JsonObject)?.let { parseProduct(it) } ?: return@mapNotNull null
            val code = food.barcode
            if (code != null && !seenCodes.add(code)) null else food
        }
    }

    /** status 1 / "1" / "success" / "product found" (or no status at all) means "found". */
    private fun isFoundStatus(status: JsonElement?): Boolean {
        if (status == null || status is JsonNull) return true
        val primitive = status as? JsonPrimitive ?: return false
        return when (primitive.content.trim().lowercase(Locale.ROOT)) {
            "1", "1.0", "success", "product found" -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Product
    // ---------------------------------------------------------------------------------------------

    /**
     * Maps one Open Food Facts product object to a [FoodEntity] (id = 0, source = OPEN_FOOD_FACTS),
     * or null if it has no usable name or no energy value.
     */
    fun parseProduct(product: JsonObject, fallbackBarcode: String? = null): FoodEntity? {
        val name = productName(product) ?: return null
        val nutriments = product["nutriments"] as? JsonObject ?: return null
        val kcal = energyKcal(nutriments) ?: return null
        val unit = unitOf(
            quantity = product.string("quantity"),
            productQuantityUnit = product.string("product_quantity_unit"),
        )
        val salt = nutriments.number("salt_100g")
            ?: nutriments.number("sodium_100g")?.let { round(it * 2.5, 3) }

        return FoodEntity(
            id = 0,
            name = name,
            brand = brandOf(product["brands"]),
            barcode = (product.string("code") ?: fallbackBarcode)?.trim()?.takeIf { it.isNotEmpty() },
            kcal = kcal,
            protein = nutriments.number("proteins_100g") ?: 0.0,
            carbs = nutriments.number("carbohydrates_100g") ?: 0.0,
            fat = nutriments.number("fat_100g") ?: 0.0,
            sugar = nutriments.number("sugars_100g"),
            fiber = nutriments.number("fiber_100g"),
            salt = salt,
            unit = unit,
            portions = portionsOf(product, unit),
            source = FoodSource.OPEN_FOOD_FACTS,
            imageUrl = product.string("image_front_small_url")?.trim()?.takeIf { it.startsWith("http") },
        )
    }

    /** product_name_de ?: product_name ?: generic_name_de (blank values are ignored). */
    internal fun productName(product: JsonObject): String? =
        sequenceOf("product_name_de", "product_name", "generic_name_de")
            .mapNotNull { key -> product.string(key)?.let(::cleanText) }
            .firstOrNull { it.isNotEmpty() }

    /**
     * kcal per 100 g: `energy-kcal_100g`, else `energy-kj_100g` / 4.184, else `energy_100g` (kJ) / 4.184.
     * Values above ~950 kcal are physically impossible and are treated as kJ. Rounded to 0.1 kcal.
     */
    internal fun energyKcal(nutriments: JsonObject): Double? {
        val kcal = nutriments.number("energy-kcal_100g")
        val kj = nutriments.number("energy-kj_100g") ?: nutriments.number("energy_100g")
        val value = when {
            kcal != null && kcal <= MAX_PLAUSIBLE_KCAL -> kcal
            kj != null -> kj / KJ_PER_KCAL
            kcal != null -> kcal / KJ_PER_KCAL
            else -> return null
        }
        return round(value, 1)
    }

    /** First brand of a comma-separated string ("Ferrero, Nutella") or of an array. */
    internal fun brandOf(element: JsonElement?): String? {
        val raw: String? = when (element) {
            null, is JsonNull -> null
            is JsonPrimitive -> element.content.takeIf { element.isString }
            is JsonArray -> element.firstNotNullOfOrNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
            }
            is JsonObject -> null
        }
        return raw
            ?.split(',')
            ?.map(::cleanText)
            ?.firstOrNull { it.isNotEmpty() }
    }

    /**
     * "ml" if the package quantity is a volume ("330 ml", "1,5 l", "75 cl", "6 x 0,33 l"), otherwise "g".
     * Falls back to `product_quantity_unit` when the quantity text has no unit.
     */
    internal fun unitOf(quantity: String?, productQuantityUnit: String? = null): String {
        val text = quantity.orEmpty().replace('℮', ' ').lowercase(Locale.ROOT)
        val match = QUANTITY_UNIT.find(text)
        if (match != null) return if (isVolumeUnit(match.groupValues[2])) "ml" else "g"
        return if (productQuantityUnit != null && isVolumeUnit(productQuantityUnit.trim())) "ml" else "g"
    }

    private fun isVolumeUnit(unit: String): Boolean = when (unit.lowercase(Locale.ROOT)) {
        "ml", "cl", "dl", "l", "lt", "ltr", "liter", "litre", "liters", "litres", "milliliter", "millilitre" -> true
        else -> false
    }

    /**
     * Portions: serving (serving_quantity > 0) → "Portion (30 g)" or the serving description,
     * package (product_quantity > 0) → "Packung (400 g)". A package equal to the serving is not repeated.
     */
    internal fun portionsOf(product: JsonObject, unit: String): List<FoodPortion> {
        val portions = mutableListOf<FoodPortion>()

        product.number("serving_quantity")
            ?.takeIf { it > 0.0 && it <= MAX_SERVING_GRAMS }
            ?.let { round(it, 2) }
            ?.takeIf { it > 0.0 }
            ?.let { grams -> portions += FoodPortion(servingName(product.string("serving_size"), grams, unit), grams) }

        product.number("product_quantity")
            ?.takeIf { it > 0.0 && it <= MAX_PACKAGE_GRAMS }
            ?.let { round(it, 2) }
            ?.takeIf { grams -> grams > 0.0 && portions.none { abs(it.grams - grams) < 0.05 } }
            ?.let { grams -> portions += FoodPortion("Packung (${formatAmount(grams)} $unit)", grams) }

        return portions
    }

    /**
     * Readable serving name: "Portion (30 g)" for plain quantities and generic descriptions
     * ("30g", "1 portion (330 ml)"), otherwise the description itself ("1 Riegel (25 g)"),
     * with the quantity appended when it is missing ("2 Scheiben" → "2 Scheiben (50 g)").
     */
    internal fun servingName(servingSize: String?, grams: Double, unit: String): String {
        val generic = "Portion (${formatAmount(grams)} $unit)"
        val description = servingSize?.let(::cleanText).orEmpty()
        if (description.isEmpty() ||
            description.length > MAX_PORTION_NAME_LENGTH ||
            PLAIN_QUANTITY.matches(description) ||
            GENERIC_PORTION.matches(description)
        ) {
            return generic
        }
        val german = DECIMAL_POINT.replace(description) { "${it.groupValues[1]},${it.groupValues[2]}" }
        return if (CONTAINS_QUANTITY.containsMatchIn(german)) german else "$german (${formatAmount(grams)} $unit)"
    }

    // ---------------------------------------------------------------------------------------------
    // Lenient primitives
    // ---------------------------------------------------------------------------------------------

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.number(key: String): Double? = parseNumber(this[key])

    /** Number from a JSON number or a string like "12,5", "<0.5" or "250 g". Negative/NaN/missing → null. */
    internal fun parseNumber(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content.trim()
        if (content.isEmpty()) return null
        val value = if (primitive.isString) {
            content.toDoubleOrNull()
                ?: LEADING_NUMBER.find(content)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        } else {
            content.toDoubleOrNull()
        }
        return value?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 }
    }

    private fun cleanText(s: String): String = s.replace(WHITESPACE, " ").trim()

    private fun round(value: Double, decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (value * factor).roundToLong() / factor
    }

    /** 400.0 → "400", 6.52 → "6,5" (German decimal comma, max. 1 decimal, no grouping). */
    private fun formatAmount(value: Double): String {
        val rounded = round(value, 1)
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString().replace('.', ',')
        }
    }
}
