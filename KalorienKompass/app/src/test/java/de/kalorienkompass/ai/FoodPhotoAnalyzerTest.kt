package de.kalorienkompass.ai

import de.kalorienkompass.data.model.AiModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Request JSON and response parsing of [FoodPhotoAnalyzer] against a MockWebServer (real Anthropic SDK). */
class FoodPhotoAnalyzerTest {

    private lateinit var server: MockWebServer

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    /** A few bytes are enough: the request only carries them base64-encoded. */
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun analyzer(model: AiModel = AiModel.OPUS, apiKey: String? = API_KEY) =
        FoodPhotoAnalyzer(json, apiKey, model, server.url("/").toString(), maxRetries = 0)

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setHeader("request-id", "req_test")
                .setBody(body),
        )
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("no request received")

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    /**
     * The user's key as it went over the wire. anthropic-java 3.x puts the key given to
     * `AnthropicOkHttpClient.builder().apiKey(..)` into `Authorization: Bearer <key>` (2.x used `x-api-key`);
     * either way it must be exactly the configured key.
     */
    private fun RecordedRequest.sentApiKey(): String? =
        getHeader("x-api-key") ?: getHeader("Authorization")?.removePrefix("Bearer ")

    private fun RecordedRequest.betaHeader(): String = headers.values("anthropic-beta").joinToString(",")

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    // --- request -------------------------------------------------------------------------------

    @Test
    fun opusRequestContainsModelImageSchemaEffortAndFallbacks(): Unit = runBlocking {
        enqueue(messageResponse(MEAL_JSON))

        analyzer(AiModel.OPUS).analyze(photo, "  mit   Olivenöl  ")

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.requestUrl!!.encodedPath)
        assertEquals(API_KEY, request.sentApiKey())
        assertTrue(request.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))

        val body = request.jsonBody()
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals(16000, body.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("default", body.str("fallbacks"))
        assertFalse("no thinking parameter", body.containsKey("thinking"))
        assertFalse("no forced tool choice", body.containsKey("tool_choice"))
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("temperature"))
        assertTrue(body.str("system").contains("Kalorienzähler-App"))

        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        val user = messages[0].jsonObject
        assertEquals("user", user.str("role"))
        val content = user.getValue("content").jsonArray
        assertEquals(2, content.size)

        val image = content[0].jsonObject
        assertEquals("image", image.str("type"))
        val source = image.getValue("source").jsonObject
        assertEquals("base64", source.str("type"))
        assertEquals("image/jpeg", source.str("media_type"))
        assertEquals(Base64.getEncoder().encodeToString(photo), source.str("data"))

        val text = content[1].jsonObject
        assertEquals("text", text.str("type"))
        assertTrue(text.str("text").startsWith("Analysiere dieses Foto"))
        assertTrue("hint is included (whitespace normalized)", text.str("text").endsWith("Mein Hinweis dazu: mit Olivenöl"))

        val outputConfig = body.getValue("output_config").jsonObject
        assertEquals("medium", outputConfig.str("effort"))
        val format = outputConfig.getValue("format").jsonObject
        assertEquals("json_schema", format.str("type"))
        assertMealSchema(format.getValue("schema").jsonObject)
    }

    @Test
    fun sonnetRequestUsesSonnetModelAndFallbacks(): Unit = runBlocking {
        enqueue(messageResponse(MEAL_JSON))

        analyzer(AiModel.SONNET).analyze(photo, null)

        val request = takeRequest()
        val body = request.jsonBody()
        assertEquals("claude-sonnet-5-5", body.str("model"))
        assertEquals("default", body.str("fallbacks"))
        assertTrue(request.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))
        val text = body.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray[1].jsonObject
        assertFalse("no hint → no hint sentence", text.str("text").contains("Hinweis"))
    }

    @Test
    fun haikuRequestHasNoFallbacks(): Unit = runBlocking {
        enqueue(messageResponse(MEAL_JSON))

        analyzer(AiModel.HAIKU).analyze(photo, "")

        val request = takeRequest()
        val body = request.jsonBody()
        assertEquals("claude-haiku-5-5", body.str("model"))
        assertFalse(body.containsKey("fallbacks"))
        assertFalse(request.betaHeader().contains("server-side-fallback"))
        assertEquals("medium", body.getValue("output_config").jsonObject.str("effort"))
    }

    // --- response ------------------------------------------------------------------------------

    @Test
    fun parsesCannedResponseIntoAnalysis(): Unit = runBlocking {
        enqueue(messageResponse(MEAL_JSON))

        val result = analyzer().analyze(photo, null)

        assertEquals("Spaghetti mit Tomatensauce", result.mealName)
        assertEquals("Etwas Olivenöl in der Sauce angenommen.", result.notes)
        assertEquals(3, result.items.size)

        val spaghetti = result.items[0]
        assertEquals("Spaghetti", spaghetti.name)
        assertEquals("Spaghetti gekocht", spaghetti.searchTerm)
        assertEquals(220.0, spaghetti.grams, 0.0001)
        assertEquals(158.0, spaghetti.kcalPer100g, 0.0001)
        assertEquals(5.8, spaghetti.proteinPer100g, 0.0001)
        assertEquals(30.9, spaghetti.carbsPer100g, 0.0001)
        assertEquals(0.9, spaghetti.fatPer100g, 0.0001)
        assertEquals("hoch", spaghetti.confidence)

        assertEquals("mittel", result.items[1].confidence)
        val parmesan = result.items[2]
        assertEquals("niedrig", parmesan.confidence)
        assertEquals(0.0, parmesan.carbsPer100g, 0.0)
    }

    @Test
    fun skipsFallbackMarkerAndThinkingBlocks(): Unit = runBlocking {
        val fallbackBlock = """{"type": "fallback", "from": {"model": "claude-opus-5-5"}, "to": {"model": "claude-opus-4-8"}},"""
        enqueue(messageResponse(MEAL_JSON, model = "claude-opus-4-8", leadingBlocks = fallbackBlock))

        val result = analyzer().analyze(photo, null)

        assertEquals(3, result.items.size)
        assertEquals("Spaghetti mit Tomatensauce", result.mealName)
    }

    @Test
    fun refusalBecomesRefusalError(): Unit = runBlocking {
        enqueue(
            """
            {"id": "msg_refused", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
             "content": [], "stop_reason": "refusal", "stop_sequence": null,
             "stop_details": {"type": "refusal", "category": null, "explanation": null},
             "usage": {"input_tokens": 1200, "output_tokens": 0}}
            """.trimIndent(),
        )

        val error = expectAiException { analyzer().analyze(photo, null) }

        assertEquals(AiErrorKind.REFUSAL, error.kind)
        assertTrue(error.message!!.startsWith("Die KI hat die Anfrage abgelehnt"))
    }

    @Test
    fun truncatedAnswerBecomesIncompleteError(): Unit = runBlocking {
        enqueue(messageResponse("""{"meal_name": "Spaghetti", "notes": "", "items": [{"name": "Spa""", stopReason = "max_tokens"))

        val error = expectAiException { analyzer().analyze(photo, null) }

        assertEquals(AiErrorKind.BAD_RESPONSE, error.kind)
        assertEquals(FoodPhotoResponseParser.INCOMPLETE_MESSAGE, error.message)
    }

    // --- errors --------------------------------------------------------------------------------

    @Test
    fun missingKeySendsNothing(): Unit = runBlocking {
        val error = expectAiException { analyzer(apiKey = "   ").analyze(photo, null) }

        assertEquals(AiErrorKind.MISSING_KEY, error.kind)
        assertEquals(AiErrors.MISSING_KEY_MESSAGE, error.message)
        assertTrue(error.opensSettings)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unauthorizedBecomesInvalidKey(): Unit = runBlocking {
        enqueue(errorBody("authentication_error", "invalid x-api-key"), code = 401)

        val error = expectAiException { analyzer().analyze(photo, null) }

        assertEquals(AiErrorKind.INVALID_KEY, error.kind)
        assertTrue(error.opensSettings)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun badRequestWithFallbacksIsRetriedOnceWithoutThem(): Unit = runBlocking {
        enqueue(errorBody("invalid_request_error", "fallbacks: unknown beta"), code = 400)
        enqueue(messageResponse(MEAL_JSON))
        enqueue(messageResponse(MEAL_JSON))
        val analyzer = analyzer(AiModel.OPUS)

        val result = analyzer.analyze(photo, null)
        assertEquals(3, result.items.size)

        val first = takeRequest()
        assertTrue(first.jsonBody().containsKey("fallbacks"))
        val second = takeRequest()
        assertFalse(second.jsonBody().containsKey("fallbacks"))
        assertFalse(second.betaHeader().contains("server-side-fallback"))

        // the fallbacks stay switched off for later requests
        analyzer.analyze(photo, null)
        assertFalse(takeRequest().jsonBody().containsKey("fallbacks"))
    }

    @Test
    fun badRequestShowsApiMessage(): Unit = runBlocking {
        enqueue(errorBody("invalid_request_error", "image exceeds 5 MB maximum"), code = 400)

        val error = expectAiException { analyzer(AiModel.HAIKU).analyze(photo, null) }

        assertEquals(AiErrorKind.BAD_REQUEST, error.kind)
        assertTrue(error.message!!.contains("image exceeds 5 MB maximum"))
    }

    @Test
    fun lowCreditBalanceBecomesBillingError(): Unit = runBlocking {
        enqueue(
            errorBody("invalid_request_error", "Your credit balance is too low to access the Anthropic API."),
            code = 400,
        )

        val error = expectAiException { analyzer(AiModel.HAIKU).analyze(photo, null) }

        assertEquals(AiErrorKind.BILLING, error.kind)
        assertTrue(error.message!!.contains("Guthaben"))
    }

    @Test
    fun rateLimitAndOverloadAreMapped(): Unit = runBlocking {
        enqueue(errorBody("rate_limit_error", "Number of requests has exceeded your rate limit"), code = 429)
        assertEquals(AiErrorKind.RATE_LIMIT, expectAiException { analyzer(AiModel.HAIKU).analyze(photo, null) }.kind)

        enqueue(errorBody("overloaded_error", "Overloaded"), code = 529)
        assertEquals(AiErrorKind.OVERLOADED, expectAiException { analyzer(AiModel.HAIKU).analyze(photo, null) }.kind)
    }

    @Test
    fun unreachableServerBecomesNetworkError(): Unit = runBlocking {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()
        val analyzer = FoodPhotoAnalyzer(json, API_KEY, AiModel.OPUS, url, maxRetries = 0)

        val error = expectAiException { analyzer.analyze(photo, null) }

        assertTrue(error.kind == AiErrorKind.NETWORK || error.kind == AiErrorKind.TIMEOUT)
        assertTrue(error.isRetryable)
    }

    // --- key test ------------------------------------------------------------------------------

    @Test
    fun testApiKeySendsTinyLowEffortRequest(): Unit = runBlocking {
        enqueue(
            """
            {"id": "msg_ok", "type": "message", "role": "assistant", "model": "claude-sonnet-5-5",
             "content": [{"type": "text", "text": "OK"}], "stop_reason": "end_turn", "stop_sequence": null,
             "usage": {"input_tokens": 12, "output_tokens": 2}}
            """.trimIndent(),
        )

        val result = analyzer(AiModel.SONNET).testApiKey()

        assertTrue(result.isSuccess)
        val body = takeRequest().jsonBody()
        assertEquals("claude-sonnet-5-5", body.str("model"))
        assertEquals(16, body.getValue("max_tokens").jsonPrimitive.int)
        val outputConfig = body.getValue("output_config").jsonObject
        assertEquals("low", outputConfig.str("effort"))
        assertFalse(outputConfig.containsKey("format"))
        assertFalse(body.containsKey("thinking"))
    }

    @Test
    fun testApiKeyReportsInvalidKey(): Unit = runBlocking {
        enqueue(errorBody("authentication_error", "invalid x-api-key"), code = 401)

        val result = analyzer().testApiKey()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AiException
        assertEquals(AiErrorKind.INVALID_KEY, error.kind)
        assertTrue(error.message!!.contains("API-Schlüssel"))
    }

    @Test
    fun testApiKeyWithoutKeyFailsWithoutRequest(): Unit = runBlocking {
        val result = analyzer(apiKey = null).testApiKey()

        val error = result.exceptionOrNull() as? AiException
        assertNotNull(error)
        assertEquals(AiErrorKind.MISSING_KEY, error!!.kind)
        assertEquals(0, server.requestCount)
    }

    // --- helpers -------------------------------------------------------------------------------

    private inline fun expectAiException(block: () -> Unit): AiException {
        try {
            block()
        } catch (e: AiException) {
            return e
        }
        fail("AiException expected")
        throw IllegalStateException()
    }

    private fun assertMealSchema(schema: JsonObject) {
        assertEquals("object", schema.str("type"))
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        val properties = schema.getValue("properties").jsonObject
        assertEquals(setOf("meal_name", "notes", "items"), properties.keys)
        assertEquals(properties.keys, schema.getValue("required").stringSet())
        assertEquals("string", properties.getValue("meal_name").jsonObject.str("type"))
        assertEquals("string", properties.getValue("notes").jsonObject.str("type"))

        val items = properties.getValue("items").jsonObject
        assertEquals("array", items.str("type"))
        val item = items.getValue("items").jsonObject
        assertEquals("object", item.str("type"))
        assertFalse(item.getValue("additionalProperties").jsonPrimitive.boolean)
        val itemProperties = item.getValue("properties").jsonObject
        assertEquals(
            setOf(
                "name", "search_term", "grams", "kcal_per_100g", "protein_per_100g",
                "carbs_per_100g", "fat_per_100g", "confidence",
            ),
            itemProperties.keys,
        )
        assertEquals(itemProperties.keys, item.getValue("required").stringSet())
        for (numeric in listOf("grams", "kcal_per_100g", "protein_per_100g", "carbs_per_100g", "fat_per_100g")) {
            assertEquals("number", itemProperties.getValue(numeric).jsonObject.str("type"))
        }
        assertEquals("string", itemProperties.getValue("name").jsonObject.str("type"))
        assertEquals("string", itemProperties.getValue("search_term").jsonObject.str("type"))
        val confidence = itemProperties.getValue("confidence").jsonObject
        assertEquals(listOf("hoch", "mittel", "niedrig"), confidence.getValue("enum").jsonArray.map { it.jsonPrimitive.content })

        assertNull("no numeric constraints in the schema", findKey(schema, NUMERIC_KEYWORDS))
    }

    private fun JsonElement.stringSet(): Set<String> = jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun findKey(element: JsonElement, keys: Set<String>): String? = when (element) {
        is JsonObject -> element.keys.firstOrNull { it in keys }
            ?: element.values.firstNotNullOfOrNull { findKey(it, keys) }
        is JsonArray -> element.firstNotNullOfOrNull { findKey(it, keys) }
        else -> null
    }

    private fun messageResponse(
        text: String,
        stopReason: String = "end_turn",
        model: String = "claude-opus-5-5",
        leadingBlocks: String = "",
    ): String = """
        {
          "id": "msg_01TestPhoto",
          "type": "message",
          "role": "assistant",
          "model": "$model",
          "content": [
            $leadingBlocks
            {"type": "thinking", "thinking": "", "signature": "c2lnbmF0dXJl"},
            {"type": "text", "text": ${JsonPrimitive(text)}}
          ],
          "stop_reason": "$stopReason",
          "stop_sequence": null,
          "usage": {"input_tokens": 1834, "output_tokens": 412}
        }
    """.trimIndent()

    private fun errorBody(type: String, message: String): String =
        """{"type": "error", "error": {"type": "$type", "message": ${JsonPrimitive(message)}}, "request_id": "req_test"}"""

    private companion object {
        const val API_KEY = "sk-ant-api03-test"

        val NUMERIC_KEYWORDS = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")

        val MEAL_JSON = """
            {"meal_name": "Spaghetti mit Tomatensauce",
             "notes": "Etwas Olivenöl in der Sauce angenommen.",
             "items": [
               {"name": "Spaghetti", "search_term": "Spaghetti gekocht", "grams": 220, "kcal_per_100g": 158,
                "protein_per_100g": 5.8, "carbs_per_100g": 30.9, "fat_per_100g": 0.9, "confidence": "hoch"},
               {"name": "Tomatensauce", "search_term": "Tomatensauce", "grams": 150, "kcal_per_100g": 65,
                "protein_per_100g": 1.6, "carbs_per_100g": 7.5, "fat_per_100g": 3.2, "confidence": "mittel"},
               {"name": "Parmesan", "search_term": "Parmesan", "grams": 10, "kcal_per_100g": 392,
                "protein_per_100g": 35.8, "carbs_per_100g": 0, "fat_per_100g": 28, "confidence": "niedrig"}
             ]}
        """.trimIndent()
    }
}
