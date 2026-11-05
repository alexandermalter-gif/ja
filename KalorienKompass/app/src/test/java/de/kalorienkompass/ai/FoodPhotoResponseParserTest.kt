package de.kalorienkompass.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FoodPhotoResponseParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun parsesPlainStructuredOutput() {
        val result = FoodPhotoResponseParser.parseMealJson(
            """{"meal_name":"Müsli mit Banane","notes":"","items":[{"name":"Haferflocken","search_term":"Haferflocken",
               "grams":60,"kcal_per_100g":372,"protein_per_100g":13.5,"carbs_per_100g":58.7,"fat_per_100g":7,"confidence":"hoch"}]}""",
            json,
        )
        assertEquals("Müsli mit Banane", result.mealName)
        assertEquals("", result.notes)
        assertEquals(1, result.items.size)
        assertEquals(60.0, result.items[0].grams, 0.0)
        assertEquals(372.0, result.items[0].kcalPer100g, 0.0)
    }

    @Test
    fun toleratesCodeFencesAndSurroundingText() {
        val text = """
            Hier ist das Ergebnis:
            ```json
            {"meal_name": "Apfel", "notes": "", "items": [{"name": "Apfel", "search_term": "Apfel", "grams": 150,
              "kcal_per_100g": 52, "protein_per_100g": 0.3, "carbs_per_100g": 11.4, "fat_per_100g": 0.2, "confidence": "hoch"}]}
            ```
        """.trimIndent()
        val result = FoodPhotoResponseParser.parseMealJson(text, json)
        assertEquals("Apfel", result.items.single().name)
    }

    @Test
    fun sanitizesImplausibleValuesAndNormalizesConfidence() {
        val result = FoodPhotoResponseParser.parseMealJson(
            """{"meal_name":"  ","notes":"  Hinweis  ","items":[
                {"name":"  Pommes ","search_term":"","grams":-20,"kcal_per_100g":5000,"protein_per_100g":-1,
                 "carbs_per_100g":150,"fat_per_100g":15,"confidence":"High"},
                {"name":"","search_term":"Ketchup","grams":30,"kcal_per_100g":110,"protein_per_100g":1.2,
                 "carbs_per_100g":24,"fat_per_100g":0.2,"confidence":"low"},
                {"name":"","search_term":"","grams":10,"kcal_per_100g":10,"protein_per_100g":0,
                 "carbs_per_100g":0,"fat_per_100g":0,"confidence":"mittel"}
            ]}""",
            json,
        )
        assertEquals("items without any name are dropped", 2, result.items.size)

        val fries = result.items[0]
        assertEquals("Pommes", fries.name)
        assertEquals("search term falls back to the name", "Pommes", fries.searchTerm)
        assertEquals(0.0, fries.grams, 0.0)
        assertEquals(900.0, fries.kcalPer100g, 0.0)
        assertEquals(0.0, fries.proteinPer100g, 0.0)
        assertEquals(100.0, fries.carbsPer100g, 0.0)
        assertEquals("hoch", fries.confidence)

        val ketchup = result.items[1]
        assertEquals("name falls back to the search term", "Ketchup", ketchup.name)
        assertEquals("niedrig", ketchup.confidence)

        assertEquals("meal name falls back to the items", "Pommes, Ketchup", result.mealName)
        assertEquals("Hinweis", result.notes)
    }

    @Test
    fun missingFieldsUseDefaults() {
        val result = FoodPhotoResponseParser.parseMealJson(
            """{"items":[{"name":"Tee","grams":250}]}""",
            json,
        )
        val tea = result.items.single()
        assertEquals("Tee", result.mealName)
        assertEquals("Tee", tea.searchTerm)
        assertEquals(0.0, tea.kcalPer100g, 0.0)
        assertEquals("mittel", tea.confidence)
    }

    @Test
    fun emptyItemsAreAllowed() {
        val result = FoodPhotoResponseParser.parseMealJson(
            """{"meal_name":"","notes":"Auf dem Foto ist kein Essen zu sehen.","items":[]}""",
            json,
        )
        assertTrue(result.items.isEmpty())
        assertEquals("Mahlzeit", result.mealName)
        assertEquals("Auf dem Foto ist kein Essen zu sehen.", result.notes)
    }

    @Test
    fun invalidJsonBecomesBadResponse() {
        for (text in listOf("Ich kann das Bild nicht erkennen.", "{\"meal_name\": ", "{\"items\": \"keine\"}")) {
            try {
                FoodPhotoResponseParser.parseMealJson(text, json)
                fail("AiException expected for: $text")
            } catch (e: AiException) {
                assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
                assertEquals(FoodPhotoResponseParser.UNREADABLE_MESSAGE, e.message)
            }
        }
    }

    @Test
    fun extractsOutermostObject() {
        assertEquals("{\"a\":{\"b\":1}}", FoodPhotoResponseParser.extractJsonObject("x {\"a\":{\"b\":1}} y"))
        assertNull(FoodPhotoResponseParser.extractJsonObject("kein JSON"))
        assertNull(FoodPhotoResponseParser.extractJsonObject("} {"))
    }

    @Test
    fun confidenceNormalization() {
        assertEquals("hoch", FoodPhotoResponseParser.normalizeConfidence("hoch"))
        assertEquals("hoch", FoodPhotoResponseParser.normalizeConfidence(" HOCH "))
        assertEquals("mittel", FoodPhotoResponseParser.normalizeConfidence("medium"))
        assertEquals("mittel", FoodPhotoResponseParser.normalizeConfidence(null))
        assertEquals("niedrig", FoodPhotoResponseParser.normalizeConfidence("Niedrig"))
        assertEquals("niedrig", FoodPhotoResponseParser.normalizeConfidence("unsicher"))
    }

    @Test
    fun userPromptIncludesTrimmedHint() {
        assertEquals(
            "Analysiere dieses Foto meiner Mahlzeit und erfasse alle sichtbaren Lebensmittel und Getränke.",
            FoodPhotoRequests.userPrompt("   "),
        )
        val withHint = FoodPhotoRequests.userPrompt("  mit\n Olivenöl gebraten ")
        assertTrue(withHint.endsWith("\n\nMein Hinweis dazu: mit Olivenöl gebraten"))
        assertTrue(FoodPhotoRequests.userPrompt("x".repeat(2000)).length < 700)
    }
}
