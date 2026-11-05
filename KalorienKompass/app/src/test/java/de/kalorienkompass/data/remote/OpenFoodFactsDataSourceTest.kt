package de.kalorienkompass.data.remote

import de.kalorienkompass.data.remote.off.OffApiException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenFoodFactsDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var dataSource: OpenFoodFactsDataSource

    private val product = """
        {"code":"4000417025005","status":1,"status_verbose":"product found","product":{
          "brands":"Ritter Sport","code":"4000417025005","product_name":"RITTER SPORT marsepein","product_name_de":"Marzipan",
          "nutriments":{"energy-kcal_100g":496,"energy-kj_100g":2069,"fat_100g":27,"carbohydrates_100g":52,"sugars_100g":48,
            "fiber_100g":6.2,"proteins_100g":6.5,"salt_100g":0.02},
          "product_quantity":100,"product_quantity_unit":"g","quantity":"100g",
          "serving_quantity":6.52,"serving_size":"1 Cube (6.52 g)"}}
    """.trimIndent()

    private fun searchBody(vararg names: String): String = names.mapIndexed { i, name ->
        """{"code":"40000000000${10 + i}","product_name":"$name","nutriments":{"energy-kcal_100g":${100 + i}}}"""
    }.joinToString(prefix = """{"count":${names.size},"page":1,"products":[""", postfix = "]}")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().build()
        dataSource = OpenFoodFactsDataSource(client, Json { ignoreUnknownKeys = true }, server.url("/").toString())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun findByBarcode_requestsProductWithFieldsAndUserAgent() = runBlocking {
        server.enqueue(MockResponse().setBody(product))

        val food = dataSource.findByBarcode("4000417025005")

        assertNotNull(food)
        assertEquals("Marzipan", food!!.name)
        assertEquals("Ritter Sport", food.brand)
        assertEquals(496.0, food.kcal, 1e-9)
        assertEquals("4000417025005", food.barcode)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/v2/product/4000417025005", request.requestUrl!!.encodedPath)
        assertEquals(OpenFoodFactsDataSource.FIELDS, request.requestUrl!!.queryParameter("fields"))
        assertEquals("KalorienKompass/1.0 (Android; privat)", request.getHeader("User-Agent"))
    }

    @Test
    fun findByBarcode_http404_isNull() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"code":"4001234567892","status":0,"status_verbose":"product not found"}"""),
        )
        assertNull(dataSource.findByBarcode("4001234567892"))
    }

    @Test
    fun findByBarcode_statusZero_isNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"00000000","status":0,"status_verbose":"no code or invalid code"}"""))
        assertNull(dataSource.findByBarcode("00000000"))
    }

    @Test
    fun findByBarcode_serverError_throwsGermanIOException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        try {
            dataSource.findByBarcode("4000417025005")
            fail("expected IOException")
        } catch (e: OffApiException) {
            assertTrue(e.message!!.contains("Open Food Facts"))
        }
    }

    @Test
    fun findByBarcode_htmlInsteadOfJson_throwsIOException() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>Wartungsarbeiten</body></html>"))
        try {
            dataSource.findByBarcode("4000417025005")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e is OffApiException)
        }
    }

    @Test
    fun findByBarcode_blankCode_doesNotCallTheServer() = runBlocking {
        assertNull(dataSource.findByBarcode("   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun search_buildsGermanSearchRequest() = runBlocking {
        server.enqueue(MockResponse().setBody(searchBody("Skyr Natur", "Skyr Vanille")))

        val foods = dataSource.search("skyr natur", page = 2, pageSize = 20)

        assertEquals(listOf("Skyr Natur", "Skyr Vanille"), foods.map { it.name })
        val url = server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("/cgi/search.pl", url.encodedPath)
        assertEquals("skyr natur", url.queryParameter("search_terms"))
        assertEquals("1", url.queryParameter("search_simple"))
        assertEquals("process", url.queryParameter("action"))
        assertEquals("1", url.queryParameter("json"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("page_size"))
        assertEquals("unique_scans_n", url.queryParameter("sort_by"))
        assertEquals("de", url.queryParameter("lc"))
        assertEquals("de", url.queryParameter("cc"))
        assertEquals(OpenFoodFactsDataSource.FIELDS, url.queryParameter("fields"))
    }

    @Test
    fun search_withoutGermanResults_fallsBackToWorldwide() = runBlocking {
        server.enqueue(MockResponse().setBody(searchBody()))
        server.enqueue(MockResponse().setBody(searchBody("Skyr Islandic")))
        server.enqueue(MockResponse().setBody(searchBody("Skyr Islandic 2")))

        val firstPage = dataSource.search("skyr islandic", page = 1)
        assertEquals(listOf("Skyr Islandic"), firstPage.map { it.name })
        assertEquals("de", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("cc"))
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("cc"))

        // further pages of the same query stay worldwide
        val secondPage = dataSource.search("Skyr Islandic", page = 2)
        assertEquals(listOf("Skyr Islandic 2"), secondPage.map { it.name })
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("cc"))
    }

    @Test
    fun search_emptyQuery_returnsEmptyWithoutRequest() = runBlocking {
        assertTrue(dataSource.search("  ").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun search_rateLimited_throwsOffApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            dataSource.search("brot")
            fail("expected OffApiException")
        } catch (e: OffApiException) {
            assertTrue(e.message!!.startsWith("Zu viele Anfragen"))
        }
    }

    @Test
    fun networkFailure_throwsIOException() = runBlocking {
        server.shutdown()
        try {
            dataSource.search("brot")
            fail("expected IOException")
        } catch (e: IOException) {
            // connection refused – reported as plain IOException
        }
    }
}
