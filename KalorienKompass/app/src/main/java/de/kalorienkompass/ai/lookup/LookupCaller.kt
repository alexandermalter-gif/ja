package de.kalorienkompass.ai.lookup

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import de.kalorienkompass.ai.AiCallSettings
import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiErrors
import de.kalorienkompass.ai.AiException
import de.kalorienkompass.ai.AnthropicClientProvider
import de.kalorienkompass.ai.FoodPhotoRequests
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/**
 * Executes the Messages API requests of the product lookup with the user's key and model
 * (same client handling, error mapping and fallback behaviour as [de.kalorienkompass.ai.FoodPhotoAnalyzer]).
 */
internal class LookupCaller(
    private val loadSettings: suspend () -> AiCallSettings,
    private val clients: AnthropicClientProvider,
) {

    /** Set when the API rejected the server-side fallback parameters (e.g. the beta was retired). */
    @Volatile
    private var fallbacksRejected: Boolean = false

    /** Key (trimmed, non-empty) and model. Throws [AiException] (missing key → [AiErrorKind.MISSING_KEY]). */
    suspend fun callSettings(): AiCallSettings {
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
     * fallback parameters were sent, the request is repeated once without them (and they stay off).
     */
    suspend fun send(
        call: AiCallSettings,
        build: (withFallbacks: Boolean) -> MessageCreateParams,
    ): Message = withContext(Dispatchers.IO) {
        val apiKey = call.apiKey ?: throw AiErrors.missingKey()
        val client = try {
            clients.clientFor(apiKey)
        } catch (e: Exception) {
            throw AiException(
                "Die Verbindung zur KI konnte nicht vorbereitet werden: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        val withFallbacks = !fallbacksRejected && FoodPhotoRequests.supportsFallbacks(call.model)
        try {
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

    /** Non-blocking call (cancelling the coroutine cancels the HTTP request). */
    private suspend fun execute(client: AnthropicClient, params: MessageCreateParams): Message = try {
        client.async().messages().create(params).await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // our own cancellation → propagate it
        throw AiException("Die Anfrage wurde unterbrochen. Bitte versuche es erneut.", e, AiErrorKind.NETWORK)
    }

    companion object {
        fun fromSettings(settings: SettingsRepository): LookupCaller = LookupCaller(
            loadSettings = { AiCallSettings(settings.aiApiKey.first(), settings.settings.first().aiModel) },
            clients = AnthropicClientProvider(),
        )

        /** Test seam: fixed key/model and a custom base URL (e.g. a MockWebServer). */
        fun fixed(apiKey: String?, model: AiModel, baseUrl: String, maxRetries: Int): LookupCaller = LookupCaller(
            loadSettings = { AiCallSettings(apiKey, model) },
            clients = AnthropicClientProvider(baseUrl = baseUrl, maxRetries = maxRetries),
        )
    }
}
