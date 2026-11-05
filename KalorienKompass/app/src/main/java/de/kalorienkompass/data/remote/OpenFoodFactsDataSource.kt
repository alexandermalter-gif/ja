package de.kalorienkompass.data.remote

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.remote.off.OffParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Open Food Facts client (https://world.openfoodfacts.org, data licensed under ODbL).
 *
 * - Barcode: `GET /api/v2/product/{code}?fields=…` → `{"status":1,"product":{…}}`; status 0 or HTTP 404 → null.
 * - Search: `GET /cgi/search.pl?search_terms=…&json=1…` → `{"products":[…]}`. Products sold in Germany
 *   (`cc=de`) are searched first; if a query has no German result at all, the worldwide database is used.
 *
 * All parsing is done by the pure [OffParser]. Network calls are cancellable (a cancelled coroutine
 * cancels the HTTP call); I/O problems are reported as [IOException] with a German message.
 */
class OpenFoodFactsDataSource(
    client: OkHttpClient,
    /** Kept for the container signature; [OffParser] uses its own lenient configuration. */
    private val json: Json,
    baseUrl: String = DEFAULT_BASE_URL,
) : FoodRemoteDataSource {

    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** Queries (lower case) for which the German search had no result → searched worldwide. */
    private val worldwideQueries = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 50
    }

    override suspend fun findByBarcode(barcode: String): FoodEntity? {
        val code = barcode.trim()
        if (code.isEmpty() || code.any { it.isWhitespace() || it == '/' }) return null

        val url = base.newBuilder()
            .addPathSegments("api/v2/product")
            .addPathSegment(code)
            .addQueryParameter("fields", FIELDS)
            .build()

        val result = get(url)
        if (result.code == 404) return null
        if (!result.isSuccessful) throw httpError(result.code)
        return parse { OffParser.parseProductResponse(result.body, requestedBarcode = code) }
    }

    override suspend fun search(query: String, page: Int, pageSize: Int): List<FoodEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 100)
        val key = q.lowercase()

        val worldwide = synchronized(worldwideQueries) { worldwideQueries[key] == true }
        if (!worldwide) {
            val german = searchPage(q, safePage, safePageSize, country = COUNTRY_GERMANY)
            if (german.isNotEmpty() || safePage > 1) return german
            synchronized(worldwideQueries) { worldwideQueries[key] = true }
        }
        return searchPage(q, safePage, safePageSize, country = null)
    }

    private suspend fun searchPage(query: String, page: Int, pageSize: Int, country: String?): List<FoodEntity> {
        val url = base.newBuilder()
            .addPathSegments("cgi/search.pl")
            .addQueryParameter("search_terms", query)
            .addQueryParameter("search_simple", "1")
            .addQueryParameter("action", "process")
            .addQueryParameter("json", "1")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .addQueryParameter("sort_by", "unique_scans_n")
            .addQueryParameter("lc", "de")
            .apply { if (country != null) addQueryParameter("cc", country) }
            .addQueryParameter("fields", FIELDS)
            .build()

        val result = get(url)
        if (!result.isSuccessful) throw httpError(result.code)
        return parse { OffParser.parseSearchResponse(result.body) }
    }

    private suspend fun <T> parse(block: () -> T): T = withContext(Dispatchers.Default) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // includes kotlinx.serialization.SerializationException (e.g. an HTML error page instead of JSON)
            throw IOException("Open Food Facts hat eine ungültige Antwort geliefert. Bitte versuche es später erneut.", e)
        }
    }

    private fun httpError(code: Int): IOException = IOException(
        when (code) {
            429 -> "Zu viele Anfragen an Open Food Facts. Bitte warte einen Moment und versuche es dann erneut."
            in 500..599 -> "Open Food Facts ist gerade überlastet oder nicht erreichbar (HTTP $code). Bitte versuche es später erneut."
            else -> "Open Food Facts hat die Anfrage abgelehnt (HTTP $code)."
        },
    )

    private class HttpResult(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /** Executes a GET request on OkHttp's thread pool; the call is cancelled when the coroutine is cancelled. */
    private suspend fun get(url: HttpUrl): HttpResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        val call = http.newCall(request)
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use { HttpResult(it.code, it.body?.string().orEmpty()) }
                        } catch (e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                            return
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                })
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://world.openfoodfacts.org/"
        const val USER_AGENT = "KalorienKompass/1.0 (Android; privat)"
        const val COUNTRY_GERMANY = "de"

        /** Fields requested from the API (everything [OffParser] reads). */
        const val FIELDS = "code,product_name,product_name_de,generic_name_de,brands,nutriments," +
            "serving_size,serving_quantity,product_quantity,quantity,image_front_small_url"

        private const val TIMEOUT_SECONDS = 15L
        private const val CALL_TIMEOUT_SECONDS = 30L
    }
}
