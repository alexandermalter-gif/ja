package de.kalorienkompass.data.remote

import de.kalorienkompass.data.db.FoodEntity

/** Remote food database (Open Food Facts). */
interface FoodRemoteDataSource {
    /** Returns the product with id = 0 and source = OPEN_FOOD_FACTS, or null if unknown.
     *  Throws [java.io.IOException] on network problems. */
    suspend fun findByBarcode(barcode: String): FoodEntity?

    /** Returns products with id = 0. Throws [java.io.IOException] on network problems. */
    suspend fun search(query: String, page: Int = 1, pageSize: Int = 30): List<FoodEntity>
}
