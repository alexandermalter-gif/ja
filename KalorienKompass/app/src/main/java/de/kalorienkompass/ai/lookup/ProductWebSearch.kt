package de.kalorienkompass.ai.lookup

import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiException
import de.kalorienkompass.ai.AnthropicClientProvider
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.serialization.json.Json

/**
 * Researches a product on the web (Claude with the server-side web search tool) by barcode and/or
 * name and returns its nutrition values per 100 g/ml – or null if no reliable values were found.
 * Claude reports the result through the strict client tool `report_product`.
 * Every failure surfaces as [AiException] with a German message.
 */
class ProductWebSearch internal constructor(
    json: Json,
    private val caller: LookupCaller,
) {

    constructor(settings: SettingsRepository, json: Json) : this(json, LookupCaller.fromSettings(settings))

    /** Test seam: fixed key/model and a custom base URL (e.g. a MockWebServer). */
    internal constructor(
        json: Json,
        apiKey: String?,
        model: AiModel,
        baseUrl: String,
        maxRetries: Int = AnthropicClientProvider.DEFAULT_MAX_RETRIES,
    ) : this(json, LookupCaller.fixed(apiKey, model, baseUrl, maxRetries))

    private val responseJson: Json = Json(from = json) {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Searches by [barcode] (EAN/GTIN) and/or [nameHint]; at least one of them is required.
     * Returns null when Claude reports `found = false` (or no nutrition values at all).
     *
     * A turn paused by the server-side tool loop (`stop_reason: pause_turn`) is continued by sending
     * the assistant content back (at most [MAX_CONTINUATIONS] times). If Claude ends its turn without
     * calling `report_product`, it is reminded once (counts as a continuation).
     */
    suspend fun search(barcode: String?, nameHint: String?): NutritionLookupResult? {
        val code = LookupRequests.cleanBarcode(barcode)
        val name = LookupRequests.cleanHint(nameHint)
        if (code == null && name == null) {
            throw AiException(
                "Bitte gib zuerst einen Barcode oder einen Produktnamen ein.",
                kind = AiErrorKind.BAD_REQUEST,
            )
        }
        val call = caller.callSettings()
        val history = mutableListOf<MessageParam>()
        var continuations = 0
        var reminded = false
        while (true) {
            val turns = history.toList()
            val message = caller.send(call) { withFallbacks ->
                LookupRequests.webSearchParams(call.model, code, name, withFallbacks, turns)
            }
            LookupResponseParser.throwIfRefused(message)

            val report = LookupResponseParser.findReport(message)
            if (report != null) return LookupResponseParser.parseReport(report, responseJson)

            val stopReason = LookupResponseParser.stopReason(message)
            if (stopReason == StopReason.MAX_TOKENS) {
                throw AiException(LookupResponseParser.INCOMPLETE_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
            }
            if (continuations >= MAX_CONTINUATIONS) {
                throw AiException(TOO_LONG_MESSAGE, kind = AiErrorKind.TIMEOUT)
            }
            val assistant = LookupResponseParser.assistantParam(message)
            when {
                stopReason == StopReason.PAUSE_TURN -> {
                    // The server resumes on its own when the paused assistant turn is sent back.
                    if (assistant != null) history += assistant
                }
                !reminded && assistant != null -> {
                    reminded = true
                    history += assistant
                    history += MessageParam.ofUser(LookupRequests.WEB_REPORT_REMINDER)
                }
                else -> throw AiException(LookupResponseParser.EMPTY_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
            }
            continuations++
        }
    }

    companion object {
        /** Follow-up requests after the first one (pause_turn continuations / one reminder). */
        const val MAX_CONTINUATIONS: Int = 3

        const val TOO_LONG_MESSAGE: String =
            "Die Websuche hat zu lange gedauert. Bitte versuche es erneut oder gib die Werte manuell ein."
    }
}
