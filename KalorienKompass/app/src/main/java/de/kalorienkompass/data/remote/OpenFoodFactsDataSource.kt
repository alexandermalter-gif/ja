package de.kalorienkompass.data.remote

import de.kalorienkompass.data.db.FoodEntity
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * STUB (owned by FOOD): Open Food Facts client.
 * TODO(FOOD): implement barcode lookup and search as described in the spec (§11 FOOD).
 */
class OpenFoodFactsDataSource(
    private val client: OkHttpClient,
    private val json: Json,
) : FoodRemoteDataSource {

    override suspend fun findByBarcode(barcode: String): FoodEntity? {
        // TODO(FOOD): GET https://world.openfoodfacts.org/api/v2/product/{code}
        return null
    }

    override suspend fun search(query: String, page: Int, pageSize: Int): List<FoodEntity> {
        // TODO(FOOD): GET https://world.openfoodfacts.org/cgi/search.pl
        return emptyList()
    }
}
