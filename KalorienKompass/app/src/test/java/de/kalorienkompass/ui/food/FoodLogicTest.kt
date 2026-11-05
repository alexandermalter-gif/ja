package de.kalorienkompass.ui.food

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLogicTest {

    private fun food(
        portions: List<FoodPortion> = emptyList(),
        unit: String = "g",
        source: FoodSource = FoodSource.BUILTIN,
    ) = FoodEntity(
        id = 7,
        name = "Apfel",
        kcal = 52.0,
        protein = 0.3,
        carbs = 11.4,
        fat = 0.2,
        unit = unit,
        portions = portions,
        source = source,
    )

    private val validForm = CustomFoodForm(
        name = "Vollkornbrötchen",
        brand = "Bäckerei Müller",
        barcode = "",
        unit = "g",
        kcal = "250",
        fat = "3,5",
        carbs = "41",
        sugar = "2,1",
        fiber = "6",
        protein = "9,5",
        salt = "1,1",
        portions = listOf(PortionInput("1 Brötchen", "70")),
    )

    // ---- portions / units ----

    @Test
    fun portionLabel_addsQuantityAndDropsLeadingOne() {
        assertEquals("mittelgroßer Apfel (150 g)", portionLabel(FoodPortion("1 mittelgroßer Apfel", 150.0), "g"))
        assertEquals("Portion (30 g)", portionLabel(FoodPortion("Portion (30 g)", 30.0), "g"))
        assertEquals(
            "Banane (mittel, ohne Schale) · 120 g",
            portionLabel(FoodPortion("1 Banane (mittel, ohne Schale)", 120.0), "g"),
        )
        assertEquals("Glas (200 ml)", portionLabel(FoodPortion("1 Glas", 200.0), "ml"))
        assertEquals("2 Scheiben (50 g)", portionLabel(FoodPortion("2 Scheiben", 50.0), "g"))
        assertEquals("Portion (12,5 g)", portionLabel(FoodPortion(" ", 12.5), "g"))
    }

    @Test
    fun amountUnitOptions_baseFirstThenValidPortions() {
        val options = amountUnitOptions(
            food(portions = listOf(FoodPortion("Scheibe", 50.0), FoodPortion("kaputt", 0.0), FoodPortion("Packung (400 g)", 400.0))),
        )
        assertEquals(listOf(BASE_UNIT_KEY, "portion:0", "portion:2"), options.map { it.key })
        assertEquals("Gramm (g)", options[0].label)
        assertEquals("g", options[0].diaryLabel)
        assertEquals("Scheibe (50 g)", options[1].diaryLabel)
        assertEquals(50.0, options[1].gramsPerUnit, 1e-9)

        val drink = amountUnitOptions(food(unit = "ml"))
        assertEquals("Milliliter (ml)", drink.single().label)
        assertEquals("ml", drink.single().diaryLabel)
    }

    @Test
    fun defaultAmount_prefersFirstPortion_butNotABigPackage() {
        assertEquals("1" to "portion:0", defaultAmount(food(portions = listOf(FoodPortion("1 Apfel", 150.0)))))
        assertEquals("100" to BASE_UNIT_KEY, defaultAmount(food()))
        // Nutella: only the 400 g jar → 100 g
        assertEquals("100" to BASE_UNIT_KEY, defaultAmount(food(portions = listOf(FoodPortion("Packung (400 g)", 400.0)))))
        // a 125 g yoghurt cup is a fine default
        assertEquals("1" to "portion:0", defaultAmount(food(portions = listOf(FoodPortion("Packung (125 g)", 125.0)))))
    }

    @Test
    fun amountForUnitChange_keepsGramsWhenSwitchingToBase() {
        val options = amountUnitOptions(food(portions = listOf(FoodPortion("Scheibe", 50.0))))
        assertEquals("1", amountForUnitChange(150.0, options[1]))
        assertEquals("150", amountForUnitChange(150.0, options[0]))
        assertEquals("100", amountForUnitChange(null, options[0]))
    }

    @Test
    fun parseAmount_acceptsGermanDecimals() {
        assertEquals(1.5, parseAmount("1,5")!!, 1e-9)
        assertNull(parseAmount("0"))
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
    }

    @Test
    fun formatNutrient_usesTwoDecimalsForSmallValues() {
        assertEquals("–", formatNutrient(null))
        assertEquals("0,11", formatNutrient(0.1075))
        assertEquals("12,5", formatNutrient(12.46))
        assertEquals("0", formatNutrient(0.0))
    }

    // ---- validation ----

    @Test
    fun validForm_hasNoErrors() {
        val result = validateCustomFood(validForm)
        assertTrue(result.isValid)
        assertNull(result.warning)
    }

    @Test
    fun requiredFields_areReported() {
        val result = validateCustomFood(CustomFoodForm())
        assertFalse(result.isValid)
        assertEquals(
            setOf(FormField.NAME, FormField.KCAL, FormField.FAT, FormField.CARBS, FormField.PROTEIN),
            result.fieldErrors.keys,
        )
    }

    @Test
    fun implausibleValues_areRejected() {
        val result = validateCustomFood(
            validForm.copy(kcal = "1200", sugar = "50", barcode = "12ab", fat = "x"),
        )
        assertTrue(FormField.KCAL in result.fieldErrors)
        assertTrue(FormField.SUGAR in result.fieldErrors) // sugar > carbs
        assertTrue(FormField.BARCODE in result.fieldErrors)
        assertEquals("Ungültige Zahl", result.fieldErrors[FormField.FAT])
    }

    @Test
    fun macrosAboveHundredGrams_isAGeneralError() {
        val result = validateCustomFood(validForm.copy(fat = "60", carbs = "40", protein = "10", sugar = ""))
        assertFalse(result.isValid)
        assertNotNull(result.generalError)
    }

    @Test
    fun kcalNotMatchingMacros_isOnlyAWarning() {
        val result = validateCustomFood(validForm.copy(kcal = "600"))
        assertTrue(result.isValid)
        assertNotNull(result.warning)
    }

    @Test
    fun incompletePortion_isReported_emptyPortionIgnored() {
        val result = validateCustomFood(
            validForm.copy(portions = listOf(PortionInput("", ""), PortionInput("Scheibe", ""), PortionInput("", "30"))),
        )
        assertEquals(setOf(1, 2), result.portionErrors.keys)
    }

    // ---- entity mapping ----

    @Test
    fun toFoodEntity_parsesValuesAndKeepsOriginalData() {
        val original = food(source = FoodSource.AI).copy(isFavorite = true, useCount = 3)
        val entity = validForm.copy(barcode = " 4001234567890 ", unit = "ml").toFoodEntity(original)
        assertEquals(7L, entity.id)
        assertEquals(FoodSource.AI, entity.source)
        assertTrue(entity.isFavorite)
        assertEquals(3, entity.useCount)
        assertEquals("Vollkornbrötchen", entity.name)
        assertEquals("Bäckerei Müller", entity.brand)
        assertEquals("4001234567890", entity.barcode)
        assertEquals("ml", entity.unit)
        assertEquals(250.0, entity.kcal, 1e-9)
        assertEquals(3.5, entity.fat, 1e-9)
        assertEquals(2.1, entity.sugar!!, 1e-9)
        assertEquals(listOf(FoodPortion("1 Brötchen", 70.0)), entity.portions)

        val created = validForm.copy(brand = " ", sugar = "", portions = listOf(PortionInput())).toFoodEntity(null)
        assertEquals(0L, created.id)
        assertEquals(FoodSource.CUSTOM, created.source)
        assertNull(created.brand)
        assertNull(created.sugar)
        assertTrue(created.portions.isEmpty())
    }

    @Test
    fun toForm_roundTrips() {
        val entity = validForm.toFoodEntity(null)
        val form = entity.toForm()
        assertEquals(validForm.copy(barcode = ""), form)
    }

    // ---- prefill (spec §13) ----

    @Test
    fun withPrefill_fillsOnlyGivenValues() {
        val start = CustomFoodForm(name = "Mein Name", barcode = "4001234567890", portions = listOf(PortionInput()))
        val filled = start.withPrefill(
            brand = "Marke",
            unit = "Milliliter",
            kcal = 42.37,
            fat = 0.0,
            carbs = 10.6,
            sugar = 10.6,
            protein = null,
            salt = 0.0254,
            servingName = "1 Dose",
            servingGrams = 330.0,
        )
        assertEquals("Mein Name", filled.name)
        assertEquals("Marke", filled.brand)
        assertEquals("4001234567890", filled.barcode)
        assertEquals("ml", filled.unit)
        assertEquals("42,4", filled.kcal)
        assertEquals("0", filled.fat)
        assertEquals("10,6", filled.carbs)
        assertEquals("", filled.protein)
        assertEquals("0,025", filled.salt)
        assertEquals(listOf(PortionInput("1 Dose", "330")), filled.portions)
    }

    @Test
    fun withPrefill_ignoresInvalidNumbersAndKeepsOtherPortions() {
        val start = validForm
        val filled = start.withPrefill(kcal = Double.NaN, fat = -1.0, servingGrams = 25.0)
        assertEquals(start.kcal, filled.kcal)
        assertEquals(start.fat, filled.fat)
        assertEquals(listOf(PortionInput("Portion", "25"), PortionInput("1 Brötchen", "70")), filled.portions)
    }

    @Test
    fun normalizeUnit_mapsFreeText() {
        assertEquals("ml", normalizeUnit("ML"))
        assertEquals("ml", normalizeUnit("l"))
        assertEquals("g", normalizeUnit("Gramm"))
        assertNull(normalizeUnit(" "))
        assertNull(normalizeUnit(null))
    }
}
