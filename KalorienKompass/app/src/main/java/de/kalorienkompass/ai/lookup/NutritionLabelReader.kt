package de.kalorienkompass.ai.lookup

import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiException
import de.kalorienkompass.ai.AnthropicClientProvider
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Reads the nutrition table (EU nutrition declaration) on a photo of a package with Claude
 * (official Anthropic Java SDK, the user's own API key). Values are returned per 100 g/ml.
 * Every failure surfaces as [AiException] with a German message.
 */
class NutritionLabelReader internal constructor(
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
     * Sends the JPEG photo (already scaled, see [de.kalorienkompass.ai.ImagePreprocessor]) and returns
     * the values read. Throws [AiException] – also when no nutrition table could be read at all.
     */
    suspend fun read(jpegBytes: ByteArray): NutritionLookupResult {
        if (jpegBytes.isEmpty()) {
            throw AiException("Das Foto ist leer. Bitte nimm ein neues Foto auf.", kind = AiErrorKind.BAD_IMAGE)
        }
        val call = caller.callSettings()
        val imageBase64 = withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(jpegBytes) }
        val message = caller.send(call) { withFallbacks ->
            LookupRequests.labelParams(call.model, imageBase64, withFallbacks)
        }
        return withContext(Dispatchers.Default) { LookupResponseParser.parseLabel(message, responseJson) }
    }
}
