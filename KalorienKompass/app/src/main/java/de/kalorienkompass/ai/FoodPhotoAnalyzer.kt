package de.kalorienkompass.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** Why an AI call failed – lets the UI offer the right next step (settings, retry, other photo …). */
enum class AiErrorKind {
    /** No API key stored yet. */
    MISSING_KEY,

    /** The API key was rejected (HTTP 401). */
    INVALID_KEY,

    /** The key is valid but may not use this model/feature (HTTP 403/404). */
    NO_ACCESS,

    /** Credit balance too low / billing problem. */
    BILLING,
    RATE_LIMIT,
    OVERLOADED,
    SERVER,
    NETWORK,
    TIMEOUT,

    /** The request was declined by the model's safeguards. */
    REFUSAL,

    /** The answer could not be read. */
    BAD_RESPONSE,

    /** The photo could not be read or prepared. */
    BAD_IMAGE,

    /** HTTP 400 for another reason. */
    BAD_REQUEST,
    OTHER,
}

/** [message] is German and user-facing. */
class AiException(
    message: String,
    cause: Throwable? = null,
    val kind: AiErrorKind = AiErrorKind.OTHER,
) : Exception(message, cause) {

    /** True if the user should open the settings (enter/replace the key or choose another model). */
    val opensSettings: Boolean
        get() = kind == AiErrorKind.MISSING_KEY || kind == AiErrorKind.INVALID_KEY || kind == AiErrorKind.NO_ACCESS

    /** True if simply trying again (later) may help. */
    val isRetryable: Boolean
        get() = when (kind) {
            AiErrorKind.MISSING_KEY, AiErrorKind.INVALID_KEY, AiErrorKind.NO_ACCESS,
            AiErrorKind.BILLING, AiErrorKind.BAD_IMAGE, AiErrorKind.BAD_REQUEST,
            -> false
            else -> true
        }
}

/** One food recognized on the photo. Nutrition values are per 100 g of the food as served. */
@Serializable
data class AiFoodItem(
    val name: String,
    val searchTerm: String,
    val grams: Double,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    /** "hoch", "mittel" or "niedrig" */
    val confidence: String,
)

@Serializable
data class AiMealAnalysis(
    val mealName: String,
    val items: List<AiFoodItem>,
    val notes: String,
)

/** API key and model for one call. */
internal data class AiCallSettings(val apiKey: String?, val model: AiModel)

/**
 * Analyzes a food photo with Claude (official Anthropic Java SDK, the user's own API key).
 * All network work runs off the main thread; every failure surfaces as [AiException].
 */
class FoodPhotoAnalyzer internal constructor(
    json: Json,
    private val loadSettings: suspend () -> AiCallSettings,
    private val clients: AnthropicClientProvider,
) {

    constructor(settings: SettingsRepository, json: Json) : this(
        json = json,
        loadSettings = { AiCallSettings(settings.aiApiKey.first(), settings.settings.first().aiModel) },
        clients = AnthropicClientProvider(),
    )

    /** Test seam: fixed key/model and a custom base URL (e.g. a MockWebServer). */
    internal constructor(
        json: Json,
        apiKey: String?,
        model: AiModel,
        baseUrl: String,
        maxRetries: Int = AnthropicClientProvider.DEFAULT_MAX_RETRIES,
    ) : this(
        json = json,
        loadSettings = { AiCallSettings(apiKey, model) },
        clients = AnthropicClientProvider(baseUrl = baseUrl, maxRetries = maxRetries),
    )

    private val responseJson: Json = Json(from = json) {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Set when the API rejected the server-side fallback parameters (e.g. the beta was retired). */
    @Volatile
    private var fallbacksRejected: Boolean = false

    /**
     * Sends the JPEG photo (already scaled, see [ImagePreprocessor]) plus an optional user [hint]
     * and returns the recognized foods. Throws [AiException] (German message).
     */
    suspend fun analyze(jpegBytes: ByteArray, hint: String?): AiMealAnalysis {
        if (jpegBytes.isEmpty()) {
            throw AiException("Das Foto ist leer. Bitte nimm ein neues Foto auf.", kind = AiErrorKind.BAD_IMAGE)
        }
        val call = loadCallSettings()
        val imageBase64 = withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(jpegBytes) }
        val message = send(call, allowFallbacks = true) { withFallbacks ->
            FoodPhotoRequests.analyzeParams(call.model, imageBase64, hint, withFallbacks)
        }
        return withContext(Dispatchers.Default) {
            FoodPhotoResponseParser.parseMealAnalysis(message, responseJson)
        }
    }

    /** Tiny request with the stored key and model. Failure carries an [AiException] with a German message. */
    suspend fun testApiKey(): Result<Unit> = try {
        val call = loadCallSettings()
        send(call, allowFallbacks = false) { FoodPhotoRequests.testParams(call.model) }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AiErrors.from(e))
    }

    private suspend fun loadCallSettings(): AiCallSettings {
        val call = try {
            loadSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiException("Die Einstellungen konnten nicht gelesen werden.", e, AiErrorKind.OTHER)
        }
        val key = call.apiKey?.trim().orEmpty()
        if (key.isEmpty()) throw AiErrors.missingKey()
        return call.copy(apiKey = key)
    }

    /**
     * Executes the request built by [build]. If the API answers HTTP 400 while the server-side
     * fallback parameters were sent, the request is repeated once without them (and they stay off
     * for this process) so a retired beta can never break the feature.
     */
    private suspend fun send(
        call: AiCallSettings,
        allowFallbacks: Boolean,
        build: (withFallbacks: Boolean) -> MessageCreateParams,
    ): Message {
        val apiKey = call.apiKey ?: throw AiErrors.missingKey()
        val client = try {
            withContext(Dispatchers.IO) { clients.clientFor(apiKey) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiException("Die Verbindung zur KI konnte nicht vorbereitet werden: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val withFallbacks = allowFallbacks && !fallbacksRejected && FoodPhotoRequests.supportsFallbacks(call.model)
        return try {
            execute(client, build(withFallbacks))
        } catch (e: AiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val cause = AiErrors.unwrap(e)
            if (!withFallbacks || cause !is BadRequestException) throw AiErrors.from(cause)
            val retried = try {
                execute(client, build(false))
            } catch (e2: AiException) {
                throw e2
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Throwable) {
                throw AiErrors.from(e2)
            }
            fallbacksRejected = true
            retried
        }
    }

    /**
     * Non-blocking call (cancelling the coroutine cancels the HTTP request). SDK exceptions are
     * passed through unchanged so [send] can inspect them.
     */
    private suspend fun execute(client: AnthropicClient, params: MessageCreateParams): Message = try {
        client.async().messages().create(params).await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // our own cancellation → propagate it
        throw AiException("Die Anfrage wurde unterbrochen. Bitte versuche es erneut.", e, AiErrorKind.NETWORK)
    }
}
