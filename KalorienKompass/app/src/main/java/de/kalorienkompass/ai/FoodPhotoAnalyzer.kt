package de.kalorienkompass.ai

import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [message] is German and user-facing. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class AiFoodItem(
    val name: String,
    val searchTerm: String,
    val grams: Double,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val confidence: String,
)

@Serializable
data class AiMealAnalysis(
    val mealName: String,
    val items: List<AiFoodItem>,
    val notes: String,
)

/**
 * STUB (owned by AI): analyzes a food photo with Claude (official Anthropic Java SDK).
 * TODO(AI): implement (see spec §11 AI).
 */
class FoodPhotoAnalyzer(
    private val settings: SettingsRepository,
    private val json: Json,
) {
    /** Throws [AiException]. */
    suspend fun analyze(jpegBytes: ByteArray, hint: String?): AiMealAnalysis =
        throw AiException("Die Fotoerkennung ist noch nicht verfügbar.")

    suspend fun testApiKey(): Result<Unit> =
        Result.failure(AiException("Die Fotoerkennung ist noch nicht verfügbar."))
}
