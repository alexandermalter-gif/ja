package de.kalorienkompass.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.remote.FoodRemoteDataSource
import java.io.IOException

/**
 * Helpers for the Robolectric database tests. Test classes use
 * `@Config(sdk = [35], application = android.app.Application::class)` so that [de.kalorienkompass.KalorienApp]
 * (real database, seeding, WorkManager) is not started.
 */
internal object DbTestSupport {

    val context: Context get() = ApplicationProvider.getApplicationContext()

    fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun food(
        name: String,
        kcal: Double = 100.0,
        protein: Double = 10.0,
        carbs: Double = 20.0,
        fat: Double = 5.0,
        brand: String? = null,
        barcode: String? = null,
        source: FoodSource = FoodSource.CUSTOM,
        useCount: Int = 0,
        portions: List<FoodPortion> = emptyList(),
        id: Long = 0,
    ): FoodEntity = FoodEntity(
        id = id,
        name = name,
        brand = brand,
        barcode = barcode,
        kcal = kcal,
        protein = protein,
        carbs = carbs,
        fat = fat,
        source = source,
        useCount = useCount,
        portions = portions,
    )
}

/** Configurable [FoodRemoteDataSource] for repository tests. */
internal class FakeRemoteDataSource : FoodRemoteDataSource {
    val barcodeRequests = mutableListOf<String>()
    var products: Map<String, FoodEntity> = emptyMap()
    var failWith: Exception? = null

    override suspend fun findByBarcode(barcode: String): FoodEntity? {
        barcodeRequests += barcode
        failWith?.let { throw it }
        return products[barcode]
    }

    override suspend fun search(query: String, page: Int, pageSize: Int): List<FoodEntity> {
        failWith?.let { throw it }
        return products.values.filter { it.name.contains(query, ignoreCase = true) }
    }

    companion object {
        fun offline() = FakeRemoteDataSource().apply { failWith = IOException("offline") }
    }
}
