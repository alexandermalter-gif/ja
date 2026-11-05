package de.kalorienkompass.data.remote.off

import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffParserTest {

    // ---------------------------------------------------------------------------------------------
    // Fixtures (shapes as returned by world.openfoodfacts.org, trimmed)
    // ---------------------------------------------------------------------------------------------

    /** Real-world shape: energy-kcal_100g present, numbers as JSON numbers, no serving. */
    private val nutella = """
        {
          "code": "3017624010701",
          "product": {
            "brands": "Ferrero",
            "code": "3017624010701",
            "generic_name_de": "",
            "image_front_small_url": "https://images.openfoodfacts.org/images/products/301/762/401/0701/front_en.100.200.jpg",
            "nutriments": {
              "carbohydrates": 57.5, "carbohydrates_100g": 57.5, "carbohydrates_unit": "g",
              "energy": 2228, "energy-kcal": 539, "energy-kcal_100g": 539, "energy-kcal_unit": "kcal",
              "energy-kj": 2228, "energy-kj_100g": 2228, "energy_100g": 2228, "energy_unit": "kJ",
              "fat": 30.9, "fat_100g": 30.9,
              "proteins": 6.3, "proteins_100g": 6.3,
              "salt": 0.1075, "salt_100g": 0.1075,
              "saturated-fat_100g": 10.6,
              "sodium_100g": 0.043,
              "sugars": 56.3, "sugars_100g": 56.3
            },
            "nutrition_data_prepared_per": "100g",
            "product_name": "Nutella",
            "product_name_de": "Nutella",
            "product_quantity": 400,
            "product_quantity_unit": "g",
            "quantity": "400.0 g"
          },
          "status": 1,
          "status_verbose": "product found"
        }
    """.trimIndent()

    /** Only kJ, German name missing → product_name, brands with several entries, serving as text. */
    private val onlyKj = """
        {
          "code": "4001234567892",
          "product": {
            "brands": "Gut & Günstig, Edeka",
            "code": "4001234567892",
            "product_name": "Haferflocken zart",
            "product_name_de": "   ",
            "nutriments": {
              "energy-kj_100g": 1046,
              "fat_100g": 7,
              "carbohydrates_100g": 58.7,
              "sugars_100g": 0.7,
              "fiber_100g": 10,
              "proteins_100g": 13.5,
              "salt_100g": 0.01
            },
            "serving_size": "40 g",
            "serving_quantity": 40,
            "product_quantity": 500,
            "quantity": "500 g"
          },
          "status": 1
        }
    """.trimIndent()

    /** Everything as strings (with German decimal comma and "<"), brands as array, volume quantity. */
    private val stringNumbers = """
        {
          "status": "1",
          "product": {
            "code": "4056489123456",
            "product_name_de": "Fettarme Milch 1,5 %",
            "brands": ["Milbona", "Lidl"],
            "nutriments": {
              "energy-kcal_100g": "47",
              "fat_100g": "1,5",
              "carbohydrates_100g": "4.9",
              "sugars_100g": "4,9",
              "proteins_100g": "3.4",
              "salt_100g": "<0,13",
              "fiber_100g": ""
            },
            "serving_size": "250 ml",
            "serving_quantity": "250",
            "product_quantity": "1000",
            "quantity": "1 l ℮"
          }
        }
    """.trimIndent()

    /** No usable name at all. */
    private val missingName = """
        {
          "status": 1,
          "product": {
            "code": "1234567890128",
            "product_name": "",
            "generic_name_de": null,
            "brands": "Irgendwas",
            "nutriments": { "energy-kcal_100g": 100 }
          }
        }
    """.trimIndent()

    private val statusZero = """{"code":"4001234567892","status":0,"status_verbose":"product not found"}"""

    private val searchResponse = """
        {
          "count": 534, "page": 1, "page_count": 5, "page_size": 5, "skip": 0,
          "products": [
            {
              "code": "3329770077003", "brands": "Yoplait", "product_name": "Skyr nature 0%",
              "nutriments": { "energy-kcal_100g": 57, "energy-kj_100g": 241, "fat_100g": 0.2, "carbohydrates_100g": 4.1, "proteins_100g": 10 },
              "product_quantity": 800, "product_quantity_unit": "g", "quantity": "800 gram",
              "serving_quantity": 100, "serving_size": "100 gram"
            },
            {
              "code": "6111242100985", "brands": "Jaouda", "product_name": "",
              "nutriments": { "energy-kcal_100g": 101 }
            },
            {
              "code": "4000000000001", "product_name": "Ohne Nährwerte",
              "nutriments": { "fat_100g": 3 }
            },
            {
              "code": "4000000000002", "product_name_de": "Skyr Natur", "brands": "Milbona (Lidl)",
              "nutriments": { "energy_100g": 263, "fat_100g": 0.2, "carbohydrates_100g": 4, "proteins_100g": 11 },
              "quantity": "500g"
            },
            {
              "code": "3329770077003", "brands": "Yoplait", "product_name": "Skyr nature 0% (Duplikat)",
              "nutriments": { "energy-kcal_100g": 57 }
            },
            "kein Objekt"
          ]
        }
    """.trimIndent()

    // ---------------------------------------------------------------------------------------------
    // Product response
    // ---------------------------------------------------------------------------------------------

    @Test
    fun productWithKcal_isMappedCompletely() {
        val food = OffParser.parseProductResponse(nutella, requestedBarcode = "3017624010701")
        assertNotNull(food)
        food!!
        assertEquals(0L, food.id)
        assertEquals("Nutella", food.name)
        assertEquals("Ferrero", food.brand)
        assertEquals("3017624010701", food.barcode)
        assertEquals(539.0, food.kcal, 1e-9)
        assertEquals(6.3, food.protein, 1e-9)
        assertEquals(57.5, food.carbs, 1e-9)
        assertEquals(30.9, food.fat, 1e-9)
        assertEquals(56.3, food.sugar!!, 1e-9)
        assertNull(food.fiber)
        assertEquals(0.1075, food.salt!!, 1e-9)
        assertEquals("g", food.unit)
        assertEquals(FoodSource.OPEN_FOOD_FACTS, food.source)
        assertEquals(listOf(FoodPortion("Packung (400 g)", 400.0)), food.portions)
        assertTrue(food.imageUrl!!.startsWith("https://images.openfoodfacts.org/"))
    }

    @Test
    fun productWithOnlyKj_convertsToKcal() {
        val food = OffParser.parseProductResponse(onlyKj)!!
        assertEquals(250.0, food.kcal, 0.05) // 1046 kJ / 4.184
        assertEquals("Haferflocken zart", food.name) // blank product_name_de is ignored
        assertEquals("Gut & Günstig", food.brand)   // first of comma-separated brands
        assertEquals(10.0, food.fiber!!, 1e-9)
        assertEquals(
            listOf(FoodPortion("Portion (40 g)", 40.0), FoodPortion("Packung (500 g)", 500.0)),
            food.portions,
        )
    }

    @Test
    fun productWithOnlyEnergy100g_isTreatedAsKj() {
        val food = OffParser.parseSearchResponse(searchResponse).first { it.barcode == "4000000000002" }
        assertEquals(62.9, food.kcal, 0.05) // 263 kJ / 4.184
        assertEquals("Skyr Natur", food.name)
    }

    @Test
    fun numbersAsStrings_andBrandsArray_areParsedLeniently() {
        val food = OffParser.parseProductResponse(stringNumbers)!!
        assertEquals("Fettarme Milch 1,5 %", food.name)
        assertEquals("Milbona", food.brand)
        assertEquals(47.0, food.kcal, 1e-9)
        assertEquals(1.5, food.fat, 1e-9)
        assertEquals(4.9, food.carbs, 1e-9)
        assertEquals(4.9, food.sugar!!, 1e-9)
        assertEquals(3.4, food.protein, 1e-9)
        assertEquals(0.13, food.salt!!, 1e-9)
        assertNull(food.fiber) // "" → missing
        assertEquals("ml", food.unit) // "1 l ℮"
        assertEquals(
            listOf(FoodPortion("Portion (250 ml)", 250.0), FoodPortion("Packung (1000 ml)", 1000.0)),
            food.portions,
        )
    }

    @Test
    fun missingName_returnsNull() {
        assertNull(OffParser.parseProductResponse(missingName))
    }

    @Test
    fun statusZero_returnsNull() {
        assertNull(OffParser.parseProductResponse(statusZero, requestedBarcode = "4001234567892"))
        assertNull(OffParser.parseProductResponse("""{"status":"failure","product":{"product_name":"X","nutriments":{"energy-kcal_100g":1}}}"""))
    }

    @Test
    fun productWithoutEnergy_returnsNull() {
        val json = """{"status":1,"product":{"product_name":"Wasser?","nutriments":{"fat_100g":0}}}"""
        assertNull(OffParser.parseProductResponse(json))
        val noNutriments = """{"status":1,"product":{"product_name":"Wasser?"}}"""
        assertNull(OffParser.parseProductResponse(noNutriments))
    }

    @Test
    fun zeroKcal_isAValidEnergyValue() {
        val json = """{"status":1,"product":{"product_name_de":"Mineralwasser","quantity":"0,75 l","nutriments":{"energy-kcal_100g":0}}}"""
        val food = OffParser.parseProductResponse(json, requestedBarcode = "4008501011009")!!
        assertEquals(0.0, food.kcal, 1e-9)
        assertEquals("ml", food.unit)
        assertEquals("4008501011009", food.barcode) // falls back to the requested code
    }

    @Test
    fun implausibleKcal_isTreatedAsKj() {
        // someone entered the kJ value into the kcal field
        val withKj = """{"status":1,"product":{"product_name":"Kekse","nutriments":{"energy-kcal_100g":2050,"energy-kj_100g":2050}}}"""
        assertEquals(490.0, OffParser.parseProductResponse(withKj)!!.kcal, 0.05)
        val withoutKj = """{"status":1,"product":{"product_name":"Kekse","nutriments":{"energy-kcal_100g":2050}}}"""
        assertEquals(490.0, OffParser.parseProductResponse(withoutKj)!!.kcal, 0.05)
    }

    @Test
    fun sodium_isConvertedToSaltWhenSaltIsMissing() {
        val json = """{"status":1,"product":{"product_name":"Brühe","nutriments":{"energy-kcal_100g":5,"sodium_100g":0.4}}}"""
        assertEquals(1.0, OffParser.parseProductResponse(json)!!.salt!!, 1e-9)
    }

    @Test
    fun servingEqualToPackage_isNotRepeated() {
        val cola = """
            {"status":1,"product":{"code":"5449000000996","product_name_de":"Coca-Cola","brands":"COCA-COLA SERVICES SA/NV",
             "nutriments":{"energy-kcal_100g":42.4,"carbohydrates_100g":10.6,"sugars_100g":10.6,"fat_100g":0,"proteins_100g":0},
             "product_quantity":330,"product_quantity_unit":"ml","quantity":"330 ml",
             "serving_quantity":330,"serving_size":"1 portion (330 ml)"}}
        """.trimIndent()
        val food = OffParser.parseProductResponse(cola)!!
        assertEquals("ml", food.unit)
        assertEquals(listOf(FoodPortion("Portion (330 ml)", 330.0)), food.portions)
        assertEquals(42.4, food.kcal, 1e-9)
    }

    @Test(expected = SerializationException::class)
    fun invalidJson_throws() {
        OffParser.parseProductResponse("<html><body>502 Bad Gateway</body></html>")
    }

    @Test(expected = SerializationException::class)
    fun unquotedGarbage_throws() {
        // kotlinx.serialization accepts this as an unquoted literal – the parser must still reject it
        OffParser.parseProductResponse("<html><body>Wartungsarbeiten</body></html>")
    }

    @Test(expected = SerializationException::class)
    fun searchResponseThatIsNoObject_throws() {
        OffParser.parseSearchResponse("[]")
    }

    // ---------------------------------------------------------------------------------------------
    // Search response
    // ---------------------------------------------------------------------------------------------

    @Test
    fun searchResponse_skipsUnusableAndDuplicateProducts() {
        val foods = OffParser.parseSearchResponse(searchResponse)
        assertEquals(listOf("3329770077003", "4000000000002"), foods.map { it.barcode })
        val skyr = foods.first()
        assertEquals("Skyr nature 0%", skyr.name)
        assertEquals("Yoplait", skyr.brand)
        assertEquals(57.0, skyr.kcal, 1e-9)
        assertEquals("g", skyr.unit) // "800 gram"
        assertEquals(
            listOf(FoodPortion("Portion (100 g)", 100.0), FoodPortion("Packung (800 g)", 800.0)),
            skyr.portions,
        )
        assertEquals("Milbona (Lidl)", foods[1].brand)
    }

    @Test
    fun searchResponse_withoutProducts_isEmpty() {
        assertTrue(OffParser.parseSearchResponse("""{"count":0,"products":[]}""").isEmpty())
        assertTrue(OffParser.parseSearchResponse("""{"count":0}""").isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun unitOf_detectsVolumes() {
        assertEquals("ml", OffParser.unitOf("330 ml"))
        assertEquals("ml", OffParser.unitOf("1,5 l"))
        assertEquals("ml", OffParser.unitOf("1,5 L"))
        assertEquals("ml", OffParser.unitOf("75 cl"))
        assertEquals("ml", OffParser.unitOf("6 x 0,33 l"))
        assertEquals("ml", OffParser.unitOf("250ml (2x125)"))
        assertEquals("ml", OffParser.unitOf("1l e"))
        assertEquals("g", OffParser.unitOf("500g"))
        assertEquals("g", OffParser.unitOf("1 kg"))
        assertEquals("g", OffParser.unitOf("2 x 140 g"))
        assertEquals("g", OffParser.unitOf("800 gram"))
        assertEquals("g", OffParser.unitOf(null))
        assertEquals("g", OffParser.unitOf("4 Stück"))
        assertEquals("ml", OffParser.unitOf("4 Stück", productQuantityUnit = "ml"))
        assertEquals("ml", OffParser.unitOf(null, productQuantityUnit = "ml"))
    }

    @Test
    fun servingName_isReadable() {
        assertEquals("Portion (30 g)", OffParser.servingName(null, 30.0, "g"))
        assertEquals("Portion (30 g)", OffParser.servingName("  ", 30.0, "g"))
        assertEquals("Portion (20 g)", OffParser.servingName("20.0g", 20.0, "g"))
        assertEquals("Portion (330 ml)", OffParser.servingName("1 portion (330 ml)", 330.0, "ml"))
        assertEquals("Portion (30 g)", OffParser.servingName("1 serving (30g)", 30.0, "g"))
        assertEquals("1 Cube (6,52 g)", OffParser.servingName("1 Cube (6.52 g)", 6.52, "g"))
        assertEquals("2 Scheiben (50 g)", OffParser.servingName("2 Scheiben", 50.0, "g"))
        assertEquals("1 Riegel (22,5 g)", OffParser.servingName("1 Riegel", 22.5, "g"))
    }

    @Test
    fun brandOf_handlesStringsArraysAndGarbage() {
        val json = kotlinx.serialization.json.Json
        assertEquals("Ferrero", OffParser.brandOf(json.parseToJsonElement("\"Ferrero, Nutella\"")))
        assertEquals("Milbona", OffParser.brandOf(json.parseToJsonElement("[\"\", \"Milbona\"]")))
        assertNull(OffParser.brandOf(json.parseToJsonElement("\" , \"")))
        assertNull(OffParser.brandOf(json.parseToJsonElement("42")))
        assertNull(OffParser.brandOf(null))
    }

    @Test
    fun parseNumber_isLenient() {
        val json = kotlinx.serialization.json.Json
        assertEquals(12.5, OffParser.parseNumber(json.parseToJsonElement("\"12,5\""))!!, 1e-9)
        assertEquals(0.5, OffParser.parseNumber(json.parseToJsonElement("\"<0.5\""))!!, 1e-9)
        assertEquals(250.0, OffParser.parseNumber(json.parseToJsonElement("\"250 g\""))!!, 1e-9)
        assertEquals(3.0, OffParser.parseNumber(json.parseToJsonElement("3"))!!, 1e-9)
        assertNull(OffParser.parseNumber(json.parseToJsonElement("\"\"")))
        assertNull(OffParser.parseNumber(json.parseToJsonElement("\"abc\"")))
        assertNull(OffParser.parseNumber(json.parseToJsonElement("-1")))
        assertNull(OffParser.parseNumber(json.parseToJsonElement("null")))
        assertNull(OffParser.parseNumber(json.parseToJsonElement("true")))
    }
}
