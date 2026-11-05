package de.kalorienkompass.data.repo

import de.kalorienkompass.data.db.FoodEntity

sealed interface BarcodeLookupResult {
    /** [food] is persisted (id != 0). */
    data class Found(val food: FoodEntity) : BarcodeLookupResult

    data object NotFound : BarcodeLookupResult

    /** [message] is German and user-facing. */
    data class Error(val message: String) : BarcodeLookupResult
}
