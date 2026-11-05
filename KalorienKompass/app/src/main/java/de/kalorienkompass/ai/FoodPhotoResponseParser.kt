package de.kalorienkompass.ai

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.StopReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.min

/** Turns the Messages API response of the photo recognition into an [AiMealAnalysis] (pure, testable). */
internal object FoodPhotoResponseParser {

    const val REFUSAL_MESSAGE =
        "Die KI hat die Anfrage abgelehnt. Bitte versuche es mit einem anderen Foto oder füge die Lebensmittel manuell hinzu."
    const val EMPTY_MESSAGE = "Die KI hat keine verwertbare Antwort geliefert. Bitte versuche es erneut."
    const val INCOMPLETE_MESSAGE = "Die Antwort der KI war unvollständig. Bitte versuche es erneut."
    const val UNREADABLE_MESSAGE = "Die Antwort der KI konnte nicht gelesen werden. Bitte versuche es erneut."

    private const val MAX_GRAMS = 5_000.0
    private const val MAX_KCAL_PER_100G = 900.0
    private const val MAX_MACRO_PER_100G = 100.0
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_NOTES_LENGTH = 600

    fun parseMealAnalysis(message: Message, json: Json): AiMealAnalysis {
        val stopReason = runCatching { message.stopReason().orElse(null) }.getOrNull()
        if (stopReason == StopReason.REFUSAL) {
            throw AiException(REFUSAL_MESSAGE, kind = AiErrorKind.REFUSAL)
        }
        val truncated = stopReason == StopReason.MAX_TOKENS
        val text = firstText(message)
        if (text.isNullOrBlank()) {
            throw AiException(if (truncated) INCOMPLETE_MESSAGE else EMPTY_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
        }
        return try {
            parseMealJson(text, json)
        } catch (e: AiException) {
            if (truncated) throw AiException(INCOMPLETE_MESSAGE, e, AiErrorKind.BAD_RESPONSE) else throw e
        }
    }

    /**
     * First non-blank text block. Other blocks (thinking, a server-side `fallback` marker, …)
     * are skipped.
     */
    fun firstText(message: Message): String? = try {
        message.content().firstNotNullOfOrNull { block ->
            block.text().orElse(null)?.text()?.takeIf { it.isNotBlank() }
        }
    } catch (e: RuntimeException) {
        null // content missing or malformed
    }

    /** Parses the JSON object of the structured output (tolerates code fences or surrounding text). */
    fun parseMealJson(text: String, json: Json): AiMealAnalysis {
        val payload = extractJsonObject(text)
            ?: throw AiException(UNREADABLE_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
        val dto = try {
            json.decodeFromString(MealAnalysisDto.serializer(), payload)
        } catch (e: SerializationException) {
            throw AiException(UNREADABLE_MESSAGE, e, AiErrorKind.BAD_RESPONSE)
        } catch (e: IllegalArgumentException) {
            throw AiException(UNREADABLE_MESSAGE, e, AiErrorKind.BAD_RESPONSE)
        }
        return dto.toAnalysis()
    }

    internal fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /** Maps "hoch"/"mittel"/"niedrig" (and English variants) to the German values; default "mittel". */
    fun normalizeConfidence(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.GERMAN).orEmpty()
        return when {
            v.startsWith("hoch") || v == "high" || v == "sicher" -> FoodPhotoRequests.CONFIDENCE_HIGH
            v.startsWith("niedrig") || v == "low" || v == "gering" || v == "unsicher" -> FoodPhotoRequests.CONFIDENCE_LOW
            else -> FoodPhotoRequests.CONFIDENCE_MEDIUM
        }
    }

    private fun MealAnalysisDto.toAnalysis(): AiMealAnalysis {
        val parsedItems = items.mapNotNull { it.toItem() }
        val name = mealName.trim().ifEmpty {
            when (parsedItems.size) {
                0 -> "Mahlzeit"
                1 -> parsedItems.first().name
                else -> parsedItems.joinToString(", ") { it.name }.take(MAX_NAME_LENGTH)
            }
        }
        return AiMealAnalysis(
            mealName = name.take(MAX_NAME_LENGTH),
            items = parsedItems,
            notes = notes.trim().take(MAX_NOTES_LENGTH),
        )
    }

    private fun FoodItemDto.toItem(): AiFoodItem? {
        val cleanName = name.trim().ifEmpty { searchTerm.trim() }
        if (cleanName.isEmpty()) return null
        return AiFoodItem(
            name = cleanName.take(MAX_NAME_LENGTH),
            searchTerm = searchTerm.trim().ifEmpty { cleanName }.take(MAX_NAME_LENGTH),
            grams = grams.sanitized(MAX_GRAMS),
            kcalPer100g = kcalPer100g.sanitized(MAX_KCAL_PER_100G),
            proteinPer100g = proteinPer100g.sanitized(MAX_MACRO_PER_100G),
            carbsPer100g = carbsPer100g.sanitized(MAX_MACRO_PER_100G),
            fatPer100g = fatPer100g.sanitized(MAX_MACRO_PER_100G),
            confidence = normalizeConfidence(confidence),
        )
    }

    private fun Double.sanitized(max: Double): Double =
        if (isNaN() || isInfinite() || this < 0.0) 0.0 else min(this, max)

    @Serializable
    internal data class MealAnalysisDto(
        @SerialName("meal_name") val mealName: String = "",
        @SerialName("notes") val notes: String = "",
        @SerialName("items") val items: List<FoodItemDto> = emptyList(),
    )

    @Serializable
    internal data class FoodItemDto(
        @SerialName("name") val name: String = "",
        @SerialName("search_term") val searchTerm: String = "",
        @SerialName("grams") val grams: Double = 0.0,
        @SerialName("kcal_per_100g") val kcalPer100g: Double = 0.0,
        @SerialName("protein_per_100g") val proteinPer100g: Double = 0.0,
        @SerialName("carbs_per_100g") val carbsPer100g: Double = 0.0,
        @SerialName("fat_per_100g") val fatPer100g: Double = 0.0,
        @SerialName("confidence") val confidence: String = FoodPhotoRequests.CONFIDENCE_MEDIUM,
    )
}
