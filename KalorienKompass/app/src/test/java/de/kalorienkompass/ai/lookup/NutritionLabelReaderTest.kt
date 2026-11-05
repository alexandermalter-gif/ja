package de.kalorienkompass.ai.lookup

import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.FoodPhotoRequests
import de.kalorienkompass.ai.lookup.LookupTestSupport.API_KEY
import de.kalorienkompass.ai.lookup.LookupTestSupport.NUMERIC_KEYWORDS
import de.kalorienkompass.ai.lookup.LookupTestSupport.PRODUCT_FIELDS
import de.kalorienkompass.ai.lookup.LookupTestSupport.REFUSAL_RESPONSE
import de.kalorienkompass.ai.lookup.LookupTestSupport.appJson
import de.kalorienkompass.ai.lookup.LookupTestSupport.assertApiKeySent
import de.kalorienkompass.ai.lookup.LookupTestSupport.betaHeader
import de.kalorienkompass.ai.lookup.LookupTestSupport.enqueueJson
import de.kalorienkompass.ai.lookup.LookupTestSupport.errorBody
import de.kalorienkompass.ai.lookup.LookupTestSupport.expectAiException
import de.kalorienkompass.ai.lookup.LookupTestSupport.findKey
import de.kalorienkompass.ai.lookup.LookupTestSupport.jsonBody
import de.kalorienkompass.ai.lookup.LookupTestSupport.str
import de.kalorienkompass.ai.lookup.LookupTestSupport.stringSet
import de.kalorienkompass.ai.lookup.LookupTestSupport.takeRequestOrFail
import de.kalorienkompass.ai.lookup.LookupTestSupport.textMessage
import de.kalorienkompass.data.model.AiModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** Request JSON and response parsing of [NutritionLabelReader] against a MockWebServer (real Anthropic SDK). */
class NutritionLabelReaderTest {

    private lateinit var server: MockWebServer

    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7, 8, 9, 0xFF.toByte(), 0xD9.toByte())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reader(model: AiModel = AiModel.OPUS, apiKey: String? = API_KEY) =
        NutritionLabelReader(appJson, apiKey, model, server.url("/").toString(), maxRetries = 0)

    // --- request -------------------------------------------------------------------------------

    @Test
    fun opusRequestContainsModelImageSchemaEffortAndFallbacks(): Unit = runBlocking {
        server.enqueueJson(textMessage(LABEL_JSON))

        reader(AiModel.OPUS).read(photo)

        val request = server.takeRequestOrFail()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.requestUrl!!.encodedPath)
        assertApiKeySent(request)
        assertTrue(request.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))

        val body = request.jsonBody()
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals(16000, body.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("default", body.str("fallbacks"))
        assertFalse("no thinking parameter", body.containsKey("thinking"))
        assertFalse("no tool choice", body.containsKey("tool_choice"))
        assertFalse(body.containsKey("tools"))
        assertTrue(body.str("system").contains("Nährwerttabelle"))
        assertTrue(body.str("system").contains("4,184"))

        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject.getValue("content").jsonArray
        assertEquals(2, content.size)
        val image = content[0].jsonObject
        assertEquals("image", image.str("type"))
        val source = image.getValue("source").jsonObject
        assertEquals("base64", source.str("type"))
        assertEquals("image/jpeg", source.str("media_type"))
        assertEquals(Base64.getEncoder().encodeToString(photo), source.str("data"))
        assertEquals("text", content[1].jsonObject.str("type"))
        assertTrue(content[1].jsonObject.str("text").contains("100 g"))

        val outputConfig = body.getValue("output_config").jsonObject
        assertEquals("medium", outputConfig.str("effort"))
        val format = outputConfig.getValue("format").jsonObject
        assertEquals("json_schema", format.str("type"))
        assertProductSchema(format.getValue("schema").jsonObject)
    }

    @Test
    fun sonnetUsesFallbacksHaikuDoesNot(): Unit = runBlocking {
        server.enqueueJson(textMessage(LABEL_JSON))
        reader(AiModel.SONNET).read(photo)
        val sonnet = server.takeRequestOrFail()
        val sonnetBody = sonnet.jsonBody()
        assertEquals("claude-sonnet-5-5", sonnetBody.str("model"))
        assertEquals("default", sonnetBody.str("fallbacks"))
        assertTrue(sonnet.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))

        server.enqueueJson(textMessage(LABEL_JSON))
        reader(AiModel.HAIKU).read(photo)
        val haiku = server.takeRequestOrFail()
        val body = haiku.jsonBody()
        assertEquals("claude-haiku-5-5", body.str("model"))
        assertFalse(body.containsKey("fallbacks"))
        assertFalse(haiku.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))
    }

    // --- response ------------------------------------------------------------------------------

    @Test
    fun parsesCannedResponse(): Unit = runBlocking {
        server.enqueueJson(textMessage(LABEL_JSON))

        val result = reader().read(photo)

        assertEquals("Haferkekse", result.name)
        assertNull("empty brand → null", result.brand)
        assertEquals("g", result.unit)
        assertEquals(468.0, result.kcal!!, 0.001)
        assertEquals(19.0, result.fat!!, 0.001)
        assertEquals(63.0, result.carbs!!, 0.001)
        assertEquals(24.0, result.sugar!!, 0.001)
        assertNull("unreadable fiber stays null", result.fiber)
        assertEquals(7.1, result.protein!!, 0.001)
        assertEquals(0.53, result.salt!!, 0.001)
        assertEquals("1 Keks", result.servingName)
        assertEquals(12.5, result.servingGrams!!, 0.001)
        assertTrue(result.sources.isEmpty())
        assertFalse(result.confident)
        assertEquals("Ballaststoffe nicht lesbar.", result.notes)
        assertTrue(result.isComplete)
    }

    @Test
    fun kilojouleOnlyIsConvertedToKcal(): Unit = runBlocking {
        val json = LABEL_JSON
            .replace("\"energy_kcal\": 468", "\"energy_kcal\": null")
            .replace("\"energy_kj\": 1960", "\"energy_kj\": 1000")
        server.enqueueJson(textMessage(json))

        val result = reader().read(photo)

        assertEquals(239.0, result.kcal!!, 0.05)
    }

    @Test
    fun implausibleValuesBecomeNull(): Unit = runBlocking {
        val json = LABEL_JSON
            .replace("\"fat\": 19", "\"fat\": -3")
            .replace("\"salt\": 0.53", "\"salt\": 530")
            .replace("\"unit\": \"g\"", "\"unit\": \"ml\"")
        server.enqueueJson(textMessage(json))

        val result = reader().read(photo)

        assertNull(result.fat)
        assertNull(result.salt)
        assertEquals("ml", result.unit)
        assertFalse(result.isComplete)
        assertNull(result.toCustomFood("4001234567890"))
    }

    @Test
    fun noNutritionTableBecomesBadImageError(): Unit = runBlocking {
        server.enqueueJson(textMessage(EMPTY_LABEL_JSON))

        val error = expectAiException { reader().read(photo) }

        assertEquals(AiErrorKind.BAD_IMAGE, error.kind)
        assertTrue(error.message!!.contains("Nährwerttabelle"))
        assertTrue("model notes are shown", error.message!!.contains("Kein Etikett zu sehen."))
    }

    @Test
    fun refusalBecomesGermanRefusalError(): Unit = runBlocking {
        server.enqueueJson(REFUSAL_RESPONSE)

        val error = expectAiException { reader().read(photo) }

        assertEquals(AiErrorKind.REFUSAL, error.kind)
        assertTrue(error.message!!.contains("abgelehnt"))
    }

    @Test
    fun unauthorizedBecomesInvalidKey(): Unit = runBlocking {
        server.enqueueJson(errorBody("authentication_error", "invalid x-api-key"), code = 401)

        val error = expectAiException { reader().read(photo) }

        assertEquals(AiErrorKind.INVALID_KEY, error.kind)
        assertTrue(error.opensSettings)
        assertTrue(error.message!!.contains("API-Schlüssel"))
    }

    @Test
    fun badRequestWithFallbacksIsRetriedOnceWithoutThem(): Unit = runBlocking {
        server.enqueueJson(errorBody("invalid_request_error", "fallbacks: unknown beta"), code = 400)
        server.enqueueJson(textMessage(LABEL_JSON))

        val result = reader().read(photo)

        assertEquals(468.0, result.kcal!!, 0.001)
        assertEquals("default", server.takeRequestOrFail().jsonBody().str("fallbacks"))
        val retry = server.takeRequestOrFail()
        assertFalse(retry.jsonBody().containsKey("fallbacks"))
        assertFalse(retry.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))
    }

    @Test
    fun missingKeySendsNothing(): Unit = runBlocking {
        val error = expectAiException { reader(apiKey = "  ").read(photo) }

        assertEquals(AiErrorKind.MISSING_KEY, error.kind)
        assertEquals(0, server.requestCount)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun assertProductSchema(schema: JsonObject) {
        assertEquals("object", schema.str("type"))
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        val properties = schema.getValue("properties").jsonObject
        assertEquals(PRODUCT_FIELDS, properties.keys)
        assertEquals(properties.keys, schema.getValue("required").stringSet())
        assertEquals(setOf("g", "ml"), properties.getValue("unit").jsonObject.getValue("enum").stringSet())
        val kcal = properties.getValue("energy_kcal").jsonObject
        assertEquals(
            setOf("number", "null"),
            kcal.getValue("anyOf").jsonArray.map { it.jsonObject.str("type") }.toSet(),
        )
        assertEquals("boolean", properties.getValue("confident").jsonObject.str("type"))
        assertNull("no numeric min/max keywords", findKey(schema, NUMERIC_KEYWORDS))
    }

    private companion object {
        val LABEL_JSON = """
            {
              "name": "Haferkekse",
              "brand": "",
              "unit": "g",
              "energy_kcal": 468,
              "energy_kj": 1960,
              "fat": 19,
              "carbohydrates": 63,
              "sugars": 24,
              "fiber": null,
              "protein": 7.1,
              "salt": 0.53,
              "serving_name": "1 Keks",
              "serving_grams": 12.5,
              "notes": "Ballaststoffe nicht lesbar.",
              "confident": false
            }
        """.trimIndent()

        val EMPTY_LABEL_JSON = """
            {"name": "", "brand": "", "unit": "g", "energy_kcal": null, "energy_kj": null, "fat": null,
             "carbohydrates": null, "sugars": null, "fiber": null, "protein": null, "salt": null,
             "serving_name": "", "serving_grams": null, "notes": "Kein Etikett zu sehen.", "confident": false}
        """.trimIndent()
    }
}
