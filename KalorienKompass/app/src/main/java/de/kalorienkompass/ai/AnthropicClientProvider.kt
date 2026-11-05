package de.kalorienkompass.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import java.time.Duration

/**
 * Creates the Anthropic SDK client and caches it per API key (a new key → a new client).
 * [clientFor] initializes OkHttp and Jackson: call it off the main thread.
 */
internal class AnthropicClientProvider(
    private val baseUrl: String? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    @Synchronized
    fun clientFor(apiKey: String): AnthropicClient {
        val cached = cachedClient
        if (cached != null && cachedKey == apiKey) return cached

        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(timeout)
            .maxRetries(maxRetries)
        if (baseUrl != null) builder.baseUrl(baseUrl)
        val client = builder.build()

        // The previous client (old key) is simply dropped: requests that still use it may finish,
        // its idle OkHttp threads end on their own.
        cachedKey = apiKey
        cachedClient = client
        return client
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(120)
        const val DEFAULT_MAX_RETRIES: Int = 2
    }
}
