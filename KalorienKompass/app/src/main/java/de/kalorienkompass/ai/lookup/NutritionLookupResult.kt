package de.kalorienkompass.ai.lookup

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource

/**
 * Nutrition values of one product found by the AI (read from a photo of the nutrition table or
 * researched on the web). All nutrition values are PER 100 g (or 100 ml for [unit] "ml");
 * null = not stated / not readable.
 */
data class NutritionLookupResult(
    val name: String? = null,
    val brand: String? = null,
    /** "g" or "ml" */
    val unit: String = "g",
    val kcal: Double? = null,
    val fat: Double? = null,
    val carbs: Double? = null,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val protein: Double? = null,
    val salt: Double? = null,
    /** e.g. "1 Riegel" or "1 Glas (250 ml)" */
    val servingName: String? = null,
    /** grams (or ml) of one serving */
    val servingGrams: Double? = null,
    /** URLs the values were taken from (web search only) */
    val sources: List<String> = emptyList(),
    /** short German note of the AI (assumptions, what could not be read) */
    val notes: String = "",
    /** false if the photo was blurry/partial or the web sources were not clearly about this product */
    val confident: Boolean = true,
) {
    /** True if energy and the three macros are known – the minimum for a food entry. */
    val isComplete: Boolean
        get() = kcal != null && fat != null && carbs != null && protein != null

    /**
     * A CUSTOM food built from the values (not saved), or null if the energy or one of the macros
     * (fat, carbs, protein) is missing. A known serving becomes the food's first portion.
     */
    fun toCustomFood(barcode: String?): FoodEntity? {
        val kcal = kcal ?: return null
        val fat = fat ?: return null
        val carbs = carbs ?: return null
        val protein = protein ?: return null
        val cleanBarcode = barcode?.trim()?.takeIf { it.isNotEmpty() }
        val foodName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: cleanBarcode?.let { "Produkt $it" }
            ?: "Unbenanntes Produkt"
        val unitSymbol = if (unit.trim().equals("ml", ignoreCase = true)) "ml" else "g"
        val portions = servingGrams?.takeIf { it > 0.0 }?.let { grams ->
            listOf(FoodPortion(servingName?.trim()?.takeIf { it.isNotEmpty() } ?: "Portion", grams))
        }.orEmpty()
        return FoodEntity(
            name = foodName,
            brand = brand?.trim()?.takeIf { it.isNotEmpty() },
            barcode = cleanBarcode,
            kcal = kcal,
            protein = protein,
            carbs = carbs,
            fat = fat,
            sugar = sugar,
            fiber = fiber,
            salt = salt,
            unit = unitSymbol,
            portions = portions,
            source = FoodSource.CUSTOM,
        )
    }
}
