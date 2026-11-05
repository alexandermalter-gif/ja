package de.kalorienkompass.ai.lookup

import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.FoodPhotoRequests
import de.kalorienkompass.ai.lookup.LookupTestSupport.API_KEY
import de.kalorienkompass.ai.lookup.LookupTestSupport.NOT_FOUND_INPUT
import de.kalorienkompass.ai.lookup.LookupTestSupport.NUMERIC_KEYWORDS
import de.kalorienkompass.ai.lookup.LookupTestSupport.PRODUCT_FIELDS
import de.kalorienkompass.ai.lookup.LookupTestSupport.REFUSAL_RESPONSE
import de.kalorienkompass.ai.lookup.LookupTestSupport.SEARCH_ID
import de.kalorienkompass.ai.lookup.LookupTestSupport.appJson
import de.kalorienkompass.ai.lookup.LookupTestSupport.assertApiKeySent
import de.kalorienkompass.ai.lookup.LookupTestSupport.betaHeader
import de.kalorienkompass.ai.lookup.LookupTestSupport.enqueueJson
import de.kalorienkompass.ai.lookup.LookupTestSupport.errorBody
import de.kalorienkompass.ai.lookup.LookupTestSupport.expectAiException
import de.kalorienkompass.ai.lookup.LookupTestSupport.findKey
import de.kalorienkompass.ai.lookup.LookupTestSupport.foundResponse
import de.kalorienkompass.ai.lookup.LookupTestSupport.jsonBody
import de.kalorienkompass.ai.lookup.LookupTestSupport.message
import de.kalorienkompass.ai.lookup.LookupTestSupport.pausedResponse
import de.kalorienkompass.ai.lookup.LookupTestSupport.reportToolUse
import de.kalorienkompass.ai.lookup.LookupTestSupport.str
import de.kalorienkompass.ai.lookup.LookupTestSupport.stringSet
import de.kalorienkompass.ai.lookup.LookupTestSupport.takeRequestOrFail
import de.kalorienkompass.ai.lookup.LookupTestSupport.textMessage
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Request JSON, tool loop and parsing of [ProductWebSearch] against a MockWebServer (real Anthropic SDK). */
class ProductWebSearchTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun search(model: AiModel = AiModel.OPUS, apiKey: String? = API_KEY) =
        ProductWebSearch(appJson, apiKey, model, server.url("/").toString(), maxRetries = 0)

    // --- request -------------------------------------------------------------------------------

    @Test
    fun requestContainsWebSearchToolStrictReportToolAndNoForcedToolChoice(): Unit = runBlocking {
        server.enqueueJson(foundResponse())

        search(AiModel.OPUS).search(" 4001234567890 ", "  Knusper   Müsli ")

        val request = server.takeRequestOrFail()
        assertEquals("/v1/messages", request.requestUrl!!.encodedPath)
        assertApiKeySent(request)
        assertTrue(request.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))

        val body = request.jsonBody()
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals(16000, body.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("default", body.str("fallbacks"))
        assertFalse("no thinking parameter", body.containsKey("thinking"))
        assertEquals("medium", body.getValue("output_config").jsonObject.str("effort"))
        assertFalse("tools report the result, no output format", body.getValue("output_config").jsonObject.containsKey("format"))

        // tool_choice may only be "auto" (forced tool use returns 400 on these models)
        body["tool_choice"]?.let { assertEquals("auto", it.jsonObject.str("type")) }

        val system = body.str("system")
        assertTrue(system.contains("report_product"))
        assertTrue(system.contains("Rate niemals"))

        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(2, tools.size)
        val webSearch = tools.single { it["type"]?.jsonPrimitive?.content == "web_search_20260209" }
        assertEquals("web_search", webSearch.str("name"))
        assertEquals(5, webSearch.getValue("max_uses").jsonPrimitive.int)
        assertEquals("DE", webSearch.getValue("user_location").jsonObject.str("country"))

        val report = tools.single { it["name"]?.jsonPrimitive?.content == "report_product" }
        assertTrue("strict tool", report.getValue("strict").jsonPrimitive.boolean)
        assertFalse("client tool has no server type", report["type"]?.jsonPrimitive?.content == "web_search_20260209")
        assertReportSchema(report.getValue("input_schema").jsonObject)

        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        val user = messages[0].jsonObject
        assertEquals("user", user.str("role"))
        val text = user.getValue("content").let { content ->
            if (content is JsonPrimitive) content.content
            else content.jsonArray[0].jsonObject.str("text")
        }
        assertTrue(text.contains("Barcode (EAN/GTIN): 4001234567890"))
        assertTrue("name hint is normalized", text.contains("Produktname (vom Nutzer eingegeben): Knusper Müsli"))
    }

    @Test
    fun haikuHasNoFallbacksAndSonnetHas(): Unit = runBlocking {
        server.enqueueJson(foundResponse())
        search(AiModel.HAIKU).search("4001234567890", null)
        val haiku = server.takeRequestOrFail()
        val haikuBody = haiku.jsonBody()
        assertEquals("claude-haiku-5-5", haikuBody.str("model"))
        assertFalse(haikuBody.containsKey("fallbacks"))
        assertFalse(haiku.betaHeader().contains(FoodPhotoRequests.FALLBACK_BETA))

        server.enqueueJson(foundResponse())
        search(AiModel.SONNET).search(null, "Knusper Müsli")
        val sonnetBody = server.takeRequestOrFail().jsonBody()
        assertEquals("claude-sonnet-5-5", sonnetBody.str("model"))
        assertEquals("default", sonnetBody.str("fallbacks"))
    }

    // --- response ------------------------------------------------------------------------------

    @Test
    fun parsesReportProductCall(): Unit = runBlocking {
        server.enqueueJson(foundResponse())

        val result = search().search("4001234567890", null)

        assertNotNull(result)
        result!!
        assertEquals("Knusper Müsli Schoko", result.name)
        assertEquals("Beispielmarke", result.brand)
        assertEquals("g", result.unit)
        assertEquals(452.0, result.kcal!!, 0.001)
        assertEquals(17.5, result.fat!!, 0.001)
        assertEquals(61.2, result.carbs!!, 0.001)
        assertEquals(21.3, result.sugar!!, 0.001)
        assertEquals(6.8, result.fiber!!, 0.001)
        assertEquals(8.9, result.protein!!, 0.001)
        assertEquals(0.12, result.salt!!, 0.001)
        assertEquals("1 Portion", result.servingName)
        assertEquals(45.0, result.servingGrams!!, 0.001)
        assertTrue(result.confident)
        assertEquals("Werte von der Herstellerseite.", result.notes)
        assertEquals(
            "duplicates and non-URLs are removed",
            listOf(
                "https://www.beispielmarke.de/produkte/knusper-muesli-schoko",
                "https://www.beispiel-shop.de/muesli-4001234567890",
            ),
            result.sources,
        )
        assertEquals("only one request (no tool_result round trip)", 1, server.requestCount)

        val food = result.toCustomFood("4001234567890")!!
        assertEquals(FoodSource.CUSTOM, food.source)
        assertEquals("4001234567890", food.barcode)
        assertEquals(listOf(FoodPortion("1 Portion", 45.0)), food.portions)
    }

    @Test
    fun foundFalseReturnsNull(): Unit = runBlocking {
        server.enqueueJson(message(content = reportToolUse(NOT_FOUND_INPUT), stopReason = "tool_use"))

        assertNull(search().search("4001234567890", null))
    }

    @Test
    fun pauseTurnIsContinuedWithAssistantContent(): Unit = runBlocking {
        val fallbackMarker = """{"type": "fallback", "from": {"model": "claude-opus-5-5"}, "to": {"model": "claude-opus-5"}}"""
        server.enqueueJson(pausedResponse(extraBlocks = fallbackMarker))
        server.enqueueJson(foundResponse())

        val result = search().search("4001234567890", null)

        assertEquals(452.0, result!!.kcal!!, 0.001)
        assertEquals(2, server.requestCount)
        val first = server.takeRequestOrFail().jsonBody()
        val second = server.takeRequestOrFail().jsonBody()
        assertEquals(1, first.getValue("messages").jsonArray.size)

        val messages = second.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("user + paused assistant turn, no extra user message", 2, messages.size)
        assertEquals("user", messages[0].str("role"))
        assertEquals("assistant", messages[1].str("role"))
        val blocks = messages[1].getValue("content").jsonArray.map { it.jsonObject }
        val types = blocks.map { it.str("type") }
        assertTrue(types.contains("server_tool_use"))
        assertTrue(types.contains("web_search_tool_result"))
        assertTrue("thinking blocks are passed back", types.contains("thinking"))
        assertTrue("blocks unknown to the SDK are passed back as raw JSON", types.contains("fallback"))
        assertEquals(SEARCH_ID, blocks.first { it.str("type") == "server_tool_use" }.str("id"))
        // same tools and model on the continuation
        assertEquals(first.getValue("tools"), second.getValue("tools"))
        assertEquals("claude-opus-5-5", second.str("model"))
    }

    @Test
    fun pauseTurnStopsAfterThreeContinuations(): Unit = runBlocking {
        repeat(4) { server.enqueueJson(pausedResponse()) }

        val error = expectAiException { search().search("4001234567890", null) }

        assertEquals(AiErrorKind.TIMEOUT, error.kind)
        assertEquals("first request + 3 continuations", 4, server.requestCount)
    }

    @Test
    fun endTurnWithoutReportGetsOneReminder(): Unit = runBlocking {
        server.enqueueJson(textMessage("Ich habe einige Seiten gefunden."))
        server.enqueueJson(foundResponse())

        val result = search().search("4001234567890", null)

        assertNotNull(result)
        server.takeRequestOrFail()
        val messages = server.takeRequestOrFail().jsonBody().getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.str("role") })
        assertTrue(messages[2].toString().contains("report_product"))
    }

    @Test
    fun refusalBecomesGermanRefusalError(): Unit = runBlocking {
        server.enqueueJson(REFUSAL_RESPONSE)

        val error = expectAiException { search().search("4001234567890", null) }

        assertEquals(AiErrorKind.REFUSAL, error.kind)
        assertTrue(error.message!!.contains("abgelehnt"))
    }

    @Test
    fun unauthorizedBecomesInvalidKey(): Unit = runBlocking {
        server.enqueueJson(errorBody("authentication_error", "invalid x-api-key"), code = 401)

        val error = expectAiException { search().search("4001234567890", null) }

        assertEquals(AiErrorKind.INVALID_KEY, error.kind)
        assertTrue(error.message!!.contains("API-Schlüssel"))
    }

    @Test
    fun noBarcodeAndNoNameSendsNothing(): Unit = runBlocking {
        val error = expectAiException { search().search("  ", " ") }

        assertEquals(AiErrorKind.BAD_REQUEST, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingKeySendsNothing(): Unit = runBlocking {
        val error = expectAiException { search(apiKey = null).search("4001234567890", null) }

        assertEquals(AiErrorKind.MISSING_KEY, error.kind)
        assertEquals(0, server.requestCount)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun assertReportSchema(schema: JsonObject) {
        assertEquals("object", schema.str("type"))
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        val properties = schema.getValue("properties").jsonObject
        assertEquals(PRODUCT_FIELDS + setOf("found", "sources"), properties.keys)
        assertEquals(properties.keys, schema.getValue("required").stringSet())
        assertEquals("boolean", properties.getValue("found").jsonObject.str("type"))
        val sources = properties.getValue("sources").jsonObject
        assertEquals("array", sources.str("type"))
        assertEquals("string", sources.getValue("items").jsonObject.str("type"))
        assertNull("no numeric min/max keywords", findKey(schema, NUMERIC_KEYWORDS))
    }
}
