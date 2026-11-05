package de.kalorienkompass.ai.lookup

import de.kalorienkompass.ai.AiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.fail
import java.util.concurrent.TimeUnit

/** MockWebServer helpers and canned Messages API responses for the lookup tests. */
internal object LookupTestSupport {

    const val API_KEY = "sk-ant-api03-test"

    val appJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    val NUMERIC_KEYWORDS = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")

    val PRODUCT_FIELDS = setOf(
        "name", "brand", "unit", "energy_kcal", "energy_kj", "fat", "carbohydrates", "sugars", "fiber",
        "protein", "salt", "serving_name", "serving_grams", "notes", "confident",
    )

    fun MockWebServer.enqueueJson(body: String, code: Int = 200) {
        enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setHeader("request-id", "req_test")
                .setBody(body),
        )
    }

    fun MockWebServer.takeRequestOrFail(): RecordedRequest =
        takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("no request received")

    fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    /** The SDK sends the key as `x-api-key` or (3.5.0) as `Authorization: Bearer <key>`. */
    fun assertApiKeySent(request: RecordedRequest) {
        val apiKeyHeader = request.getHeader("x-api-key")
        val authorization = request.getHeader("Authorization")
        if (apiKeyHeader != API_KEY && authorization != "Bearer $API_KEY") {
            fail("API key not sent (x-api-key=${apiKeyHeader != null}, Authorization=${authorization?.substringBefore(' ')})")
        }
    }

    fun RecordedRequest.betaHeader(): String = headers.values("anthropic-beta").joinToString(",")

    fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    fun JsonElement.stringSet(): Set<String> = jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** Name of the first key in [keys] that occurs anywhere in [element], or null. */
    fun findKey(element: JsonElement, keys: Set<String>): String? = when (element) {
        is JsonObject -> element.keys.firstOrNull { it in keys }
            ?: element.values.firstNotNullOfOrNull { findKey(it, keys) }
        is JsonArray -> element.firstNotNullOfOrNull { findKey(it, keys) }
        else -> null
    }

    inline fun expectAiException(block: () -> Unit): AiException {
        try {
            block()
        } catch (e: AiException) {
            return e
        }
        fail("AiException expected")
        throw IllegalStateException()
    }

    fun message(
        content: String,
        stopReason: String = "end_turn",
        model: String = "claude-opus-5-5",
        id: String = "msg_01Lookup",
    ): String = """
        {
          "id": "$id",
          "type": "message",
          "role": "assistant",
          "model": "$model",
          "content": [ $content ],
          "stop_reason": "$stopReason",
          "stop_sequence": null,
          "usage": {"input_tokens": 1500, "output_tokens": 300}
        }
    """.trimIndent()

    fun textMessage(text: String, stopReason: String = "end_turn"): String = message(
        content = """
            {"type": "thinking", "thinking": "", "signature": "c2lnbmF0dXJl"},
            {"type": "text", "text": ${JsonPrimitive(text)}}
        """.trimIndent(),
        stopReason = stopReason,
    )

    fun errorBody(type: String, message: String): String =
        """{"type": "error", "error": {"type": "$type", "message": ${JsonPrimitive(message)}}, "request_id": "req_test"}"""

    val REFUSAL_RESPONSE = """
        {"id": "msg_refusal", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
         "content": [], "stop_reason": "refusal", "stop_sequence": null,
         "stop_details": {"type": "refusal", "category": null, "explanation": null},
         "usage": {"input_tokens": 10, "output_tokens": 0}}
    """.trimIndent()

    // --- web search blocks ---------------------------------------------------------------------

    const val SEARCH_ID = "srvtoolu_01Search"

    val SERVER_TOOL_USE = """
        {"type": "server_tool_use", "id": "$SEARCH_ID", "name": "web_search", "input": {"query": "4001234567890 Nährwerte"}}
    """.trimIndent()

    val SEARCH_RESULT = """
        {"type": "web_search_tool_result", "tool_use_id": "$SEARCH_ID", "content": [
          {"type": "web_search_result", "url": "https://www.beispiel-shop.de/muesli-4001234567890",
           "title": "Knusper Müsli 500 g", "encrypted_content": "ZW5jcnlwdGVk", "page_age": "2026-09-01"}
        ]}
    """.trimIndent()

    fun reportToolUse(input: String): String =
        """{"type": "tool_use", "id": "toolu_01Report", "name": "report_product", "input": $input}"""

    val FOUND_INPUT = """
        {
          "found": true,
          "name": "Knusper Müsli Schoko",
          "brand": "Beispielmarke",
          "unit": "g",
          "energy_kcal": 452,
          "energy_kj": 1893,
          "fat": 17.5,
          "carbohydrates": 61.2,
          "sugars": 21.3,
          "fiber": 6.8,
          "protein": 8.9,
          "salt": 0.12,
          "serving_name": "1 Portion",
          "serving_grams": 45,
          "notes": "Werte von der Herstellerseite.",
          "confident": true,
          "sources": [
            "https://www.beispielmarke.de/produkte/knusper-muesli-schoko",
            "https://www.beispiel-shop.de/muesli-4001234567890",
            "https://www.beispielmarke.de/produkte/knusper-muesli-schoko",
            "keine-url"
          ]
        }
    """.trimIndent()

    val NOT_FOUND_INPUT = """
        {
          "found": false, "name": "", "brand": "", "unit": "g",
          "energy_kcal": null, "energy_kj": null, "fat": null, "carbohydrates": null, "sugars": null,
          "fiber": null, "protein": null, "salt": null, "serving_name": "", "serving_grams": null,
          "notes": "Zum Barcode wurde nichts Verlässliches gefunden.", "confident": false, "sources": []
        }
    """.trimIndent()

    fun foundResponse(): String = message(
        content = listOf(
            """{"type": "thinking", "thinking": "", "signature": "c2lnbmF0dXJl"}""",
            SERVER_TOOL_USE,
            SEARCH_RESULT,
            """{"type": "text", "text": "Ich habe das Produkt gefunden."}""",
            reportToolUse(FOUND_INPUT),
        ).joinToString(",\n"),
        stopReason = "tool_use",
    )

    fun pausedResponse(extraBlocks: String = ""): String = message(
        content = listOf(
            extraBlocks.takeIf { it.isNotBlank() },
            """{"type": "thinking", "thinking": "", "signature": "c2lnbmF0dXJl"}""",
            SERVER_TOOL_USE,
            SEARCH_RESULT,
        ).filterNotNull().joinToString(",\n"),
        stopReason = "pause_turn",
        id = "msg_01Paused",
    )
}
