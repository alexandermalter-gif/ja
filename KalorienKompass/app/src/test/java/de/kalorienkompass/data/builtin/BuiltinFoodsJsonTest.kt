package de.kalorienkompass.data.builtin

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Validates the built-in food database `app/src/main/assets/foods_de.json`
 * (values per 100 g / 100 ml, EU convention: carbs exclude fiber).
 */
class BuiltinFoodsJsonTest {

    @Test
    fun fileParsesWithVersionAndEnoughFoods() {
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        assertEquals("\"version\" muss 1 sein", 1, version)
        assertTrue("\"foods\" muss ein Array sein", root["foods"] is JsonArray)
        assertTrue("Mindestens $MIN_FOODS Lebensmittel erwartet, gefunden: ${foods.size}", foods.size >= MIN_FOODS)
    }

    @Test
    fun onlyKnownFieldsAreUsed() {
        val problems = mutableListOf<String>()
        foods.forEachIndexed { index, food ->
            val unknown = food.keys - ALLOWED_KEYS
            if (unknown.isNotEmpty()) problems += "${label(index, food)}: unbekannte Felder $unknown"
        }
        assertNoProblems("Unbekannte Felder", problems)
    }

    @Test
    fun namesArePresentAndUnique() {
        val problems = mutableListOf<String>()
        val seen = HashMap<String, Int>()
        foods.forEachIndexed { index, food ->
            val name = food.text("name")
            if (name == null || name.isBlank()) {
                problems += "Eintrag #$index: Name fehlt"
                return@forEachIndexed
            }
            if (name != name.trim()) problems += "\"$name\": Leerzeichen am Anfang/Ende"
            val key = name.trim().lowercase()
            val previous = seen.put(key, index)
            if (previous != null) problems += "\"$name\" doppelt (Einträge #$previous und #$index)"
        }
        assertNoProblems("Namen", problems)
    }

    @Test
    fun categoriesAreKnownAndAllUsed() {
        val problems = mutableListOf<String>()
        val used = HashSet<String>()
        foods.forEachIndexed { index, food ->
            val category = food.text("category")
            if (category == null || category.isBlank()) {
                problems += "${label(index, food)}: Kategorie fehlt"
            } else if (category !in EXPECTED_CATEGORIES) {
                problems += "${label(index, food)}: unbekannte Kategorie \"$category\""
            } else {
                used += category
            }
        }
        for (category in EXPECTED_CATEGORIES) {
            if (category !in used) problems += "Kategorie \"$category\" ist leer"
        }
        assertNoProblems("Kategorien", problems)
    }

    @Test
    fun nutritionValuesArePresentAndNonNegative() {
        val problems = mutableListOf<String>()
        foods.forEachIndexed { index, food ->
            for (key in REQUIRED_NUMBERS) {
                val value = food.number(key)
                if (value == null) {
                    problems += "${label(index, food)}: \"$key\" fehlt oder ist keine Zahl"
                } else if (value.isNaN() || value < 0.0) {
                    problems += "${label(index, food)}: \"$key\" = $value"
                }
            }
            for (key in OPTIONAL_NUMBERS) {
                if (!food.containsKey(key)) continue
                val value = food.number(key)
                if (value == null || value.isNaN() || value < 0.0) {
                    problems += "${label(index, food)}: \"$key\" ungültig (${food[key]})"
                }
            }
            val kcal = food.number("kcal") ?: 0.0
            if (kcal > 900.0) problems += "${label(index, food)}: mehr als 900 kcal pro 100 g"

            val carbs = food.number("carbs") ?: 0.0
            val sugar = food.number("sugar") ?: 0.0
            if (sugar > carbs + 0.05) problems += "${label(index, food)}: Zucker ($sugar) > Kohlenhydrate ($carbs)"

            val mass = (food.number("protein") ?: 0.0) + carbs + (food.number("fat") ?: 0.0) +
                (food.number("fiber") ?: 0.0) + (food.number("alcohol") ?: 0.0)
            if (mass > 101.0) problems += "${label(index, food)}: Nährstoffsumme ${round1(mass)} g > 100 g"

            val unit = food.text("unit")
            if (unit != "g" && unit != "ml") problems += "${label(index, food)}: Einheit muss \"g\" oder \"ml\" sein, ist \"$unit\""
        }
        assertNoProblems("Nährwerte", problems)
    }

    @Test
    fun energyMatchesMacronutrients() {
        val problems = mutableListOf<String>()
        foods.forEachIndexed { index, food ->
            val kcal = food.number("kcal") ?: return@forEachIndexed
            val protein = food.number("protein") ?: 0.0
            val carbs = food.number("carbs") ?: 0.0
            val fat = food.number("fat") ?: 0.0
            val fiber = food.number("fiber") ?: 0.0
            val alcohol = food.number("alcohol") ?: 0.0
            val computed = 4.0 * protein + 4.0 * carbs + 9.0 * fat + 2.0 * fiber + 7.0 * alcohol
            val tolerance = max(15.0, 0.15 * kcal)
            if (abs(kcal - computed) > tolerance) {
                problems += "${label(index, food)}: kcal = $kcal, berechnet = ${round1(computed)} (Toleranz ${round1(tolerance)})"
            }
        }
        assertNoProblems("Energie passt nicht zu den Makronährstoffen", problems)
    }

    @Test
    fun everyFoodHasValidPortions() {
        val problems = mutableListOf<String>()
        foods.forEachIndexed { index, food ->
            val portions = food["portions"] as? JsonArray
            if (portions == null || portions.isEmpty()) {
                problems += "${label(index, food)}: keine Portion angegeben"
                return@forEachIndexed
            }
            val portionNames = HashSet<String>()
            portions.forEachIndexed { portionIndex, element ->
                val portion = element as? JsonObject
                if (portion == null) {
                    problems += "${label(index, food)}: Portion #$portionIndex ist kein Objekt"
                    return@forEachIndexed
                }
                val name = portion.text("name")
                val grams = portion.number("grams")
                if (name == null || name.isBlank()) {
                    problems += "${label(index, food)}: Portion #$portionIndex ohne Namen"
                } else if (!portionNames.add(name)) {
                    problems += "${label(index, food)}: Portion \"$name\" doppelt"
                }
                if (grams == null || grams.isNaN() || grams <= 0.0) {
                    problems += "${label(index, food)}: Portion #$portionIndex mit ungültiger Menge (${portion["grams"]})"
                }
            }
        }
        assertNoProblems("Portionen", problems)
    }

    // ---------------------------------------------------------------- helpers

    private fun JsonObject.number(key: String): Double? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull
    }

    private fun JsonObject.text(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun label(index: Int, food: JsonObject): String {
        val name = food.text("name")
        return if (name.isNullOrBlank()) "Eintrag #$index" else "\"$name\""
    }

    private fun round1(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    private fun assertNoProblems(title: String, problems: List<String>) {
        if (problems.isNotEmpty()) {
            fail("$title – ${problems.size} Problem(e):\n" + problems.joinToString(separator = "\n"))
        }
    }

    companion object {
        private const val MIN_FOODS = 200

        private val EXPECTED_CATEGORIES = setOf(
            "Obst",
            "Gemüse",
            "Brot & Backwaren",
            "Getreide & Beilagen",
            "Milchprodukte",
            "Käse",
            "Eier",
            "Fleisch & Wurst",
            "Fisch",
            "Hülsenfrüchte",
            "Nüsse & Samen",
            "Fette & Öle",
            "Süßes & Snacks",
            "Getränke",
            "Fertiggerichte & Fast Food",
            "Saucen & Aufstriche",
        )

        private val REQUIRED_NUMBERS = listOf("kcal", "protein", "carbs", "fat")
        private val OPTIONAL_NUMBERS = listOf("sugar", "fiber", "salt", "alcohol")
        private val ALLOWED_KEYS = setOf(
            "name", "category", "kcal", "protein", "carbs", "fat",
            "sugar", "fiber", "salt", "alcohol", "unit", "portions",
        )

        private fun loadJsonText(): String {
            // Gradle runs unit tests with the module directory (app/) as working directory;
            // the second candidate allows running from the project root as well.
            val candidates = listOf(
                File("src/main/assets/foods_de.json"),
                File("app/src/main/assets/foods_de.json"),
            )
            val file = candidates.firstOrNull { it.isFile }
                ?: throw AssertionError(
                    "foods_de.json nicht gefunden (Arbeitsverzeichnis: ${File(".").absoluteFile})",
                )
            return file.readText(Charsets.UTF_8)
        }

        private val root: JsonObject by lazy {
            val element = Json.parseToJsonElement(loadJsonText())
            element as? JsonObject ?: throw AssertionError("Wurzelelement muss ein JSON-Objekt sein")
        }

        private val foods: List<JsonObject> by lazy {
            val array = root["foods"] as? JsonArray ?: throw AssertionError("\"foods\" fehlt oder ist kein Array")
            array.mapIndexed { index, element ->
                element as? JsonObject ?: throw AssertionError("Eintrag #$index ist kein JSON-Objekt")
            }
        }
    }
}
