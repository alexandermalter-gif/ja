package de.kalorienkompass.ai.lookup

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolUseBlock
import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiException
import de.kalorienkompass.ai.FoodPhotoResponseParser
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.round

/** Turns Messages API responses of the product lookup into [NutritionLookupResult]s (pure, testable). */
internal object LookupResponseParser {

    const val REFUSAL_MESSAGE =
        "Die KI hat die Anfrage abgelehnt. Bitte gib die Nährwerte manuell ein."
    const val EMPTY_MESSAGE = "Die KI hat keine verwertbare Antwort geliefert. Bitte versuche es erneut."
    const val INCOMPLETE_MESSAGE = "Die Antwort der KI war unvollständig. Bitte versuche es erneut."
    const val UNREADABLE_MESSAGE = "Die Antwort der KI konnte nicht gelesen werden. Bitte versuche es erneut."
    const val NO_TABLE_MESSAGE =
        "Auf dem Foto konnte keine Nährwerttabelle gelesen werden. Bitte fotografiere die Tabelle gerade, " +
            "scharf und vollständig – oder gib die Werte manuell ein."

    private const val KJ_PER_KCAL = 4.184
    private const val MAX_KCAL = 950.0
    private const val MAX_GRAMS_PER_100 = 100.0
    private const val MAX_SERVING = 5_000.0
    private const val MAX_TEXT = 120
    private const val MAX_NOTES = 600
    private const val MAX_SOURCES = 8

    // --- shared ----------------------------------------------------------------------------------

    fun stopReason(message: Message): StopReason? = runCatching { message.stopReason().orElse(null) }.getOrNull()

    fun throwIfRefused(message: Message) {
        if (stopReason(message) == StopReason.REFUSAL) {
            throw AiException(REFUSAL_MESSAGE, kind = AiErrorKind.REFUSAL)
        }
    }

    // --- nutrition label (structured output) -----------------------------------------------------

    /** Parses the structured output of [LookupRequests.labelParams]. Throws [AiException]. */
    fun parseLabel(message: Message, json: Json): NutritionLookupResult {
        throwIfRefused(message)
        val truncated = stopReason(message) == StopReason.MAX_TOKENS
        val text = FoodPhotoResponseParser.firstText(message)
        if (text.isNullOrBlank()) {
            throw AiException(if (truncated) INCOMPLETE_MESSAGE else EMPTY_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
        }
        val result = try {
            parseLabelJson(text, json)
        } catch (e: AiException) {
            if (truncated) throw AiException(INCOMPLETE_MESSAGE, e, AiErrorKind.BAD_RESPONSE) else throw e
        }
        if (!result.hasAnyNutrition()) {
            val detail = result.notes.takeIf { it.isNotBlank() }?.let { " (KI: $it)" }.orEmpty()
            throw AiException(NO_TABLE_MESSAGE + detail, kind = AiErrorKind.BAD_IMAGE)
        }
        return result
    }

    fun parseLabelJson(text: String, json: Json): NutritionLookupResult {
        val payload = FoodPhotoResponseParser.extractJsonObject(text)
            ?: throw AiException(UNREADABLE_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
        val dto = decode(json) { json.decodeFromString(ProductDto.serializer(), payload) }
        return dto.toResult(sources = emptyList())
    }

    // --- web search (report_product tool) --------------------------------------------------------

    /** The report_product call of [message], or null if the model did not call it (yet). */
    fun findReport(message: Message): ToolUseBlock? = try {
        message.content().firstNotNullOfOrNull { block ->
            block.toolUse().orElse(null)?.takeIf { it.name() == LookupRequests.REPORT_TOOL_NAME }
        }
    } catch (e: RuntimeException) {
        null
    }

    /** Result of a report_product call; null when the model reported `found = false` or no values. */
    fun parseReport(block: ToolUseBlock, json: Json): NutritionLookupResult? {
        val element = try {
            block._input().toJsonElement()
        } catch (e: RuntimeException) {
            throw AiException(UNREADABLE_MESSAGE, e, AiErrorKind.BAD_RESPONSE)
        }
        if (element !is JsonObject) throw AiException(UNREADABLE_MESSAGE, kind = AiErrorKind.BAD_RESPONSE)
        val dto = decode(json) { json.decodeFromJsonElement(ReportDto.serializer(), element) }
        if (!dto.found) return null
        val result = dto.product.toResult(sources = dto.sources)
        return result.takeIf { it.hasAnyNutrition() }
    }

    /**
     * The assistant turn of [message] as a request message (for continuing a paused turn). Blocks the
     * SDK cannot convert (e.g. markers of newer API features) are passed on as raw JSON, else dropped.
     */
    fun assistantParam(message: Message): MessageParam? {
        val blocks = try {
            message.content().mapNotNull { it.toParamOrNull() }
        } catch (e: RuntimeException) {
            emptyList()
        }
        return if (blocks.isEmpty()) null else MessageParam.ofAssistantOfContentBlockParams(blocks)
    }

    private fun ContentBlock.toParamOrNull(): ContentBlockParam? =
        runCatching { toParam() }.getOrNull()
            ?: runCatching { _json().orElse(null)?.convert(ContentBlockParam::class.java) }.getOrNull()

    // --- mapping ---------------------------------------------------------------------------------

    private inline fun <T> decode(json: Json, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw AiException(UNREADABLE_MESSAGE, e, AiErrorKind.BAD_RESPONSE)
    } catch (e: IllegalArgumentException) {
        throw AiException(UNREADABLE_MESSAGE, e, AiErrorKind.BAD_RESPONSE)
    }

    private fun NutritionLookupResult.hasAnyNutrition(): Boolean =
        listOf(kcal, fat, carbs, sugar, fiber, protein, salt).any { it != null }

    private fun ProductDto.toResult(sources: List<String>): NutritionLookupResult {
        val kcalValue = energyKcal.clean(MAX_KCAL)
            ?: energyKj.clean(MAX_KCAL * KJ_PER_KCAL)?.let { round1(it / KJ_PER_KCAL) }
        val servingGramsValue = servingGrams.clean(MAX_SERVING)?.takeIf { it > 0.0 }
        val servingNameValue = servingName.text()
            ?: servingGramsValue?.let { "Portion" }
        return NutritionLookupResult(
            name = name.text(),
            brand = brand.text(),
            unit = if (unit?.trim().equals("ml", ignoreCase = true)) "ml" else "g",
            kcal = kcalValue,
            fat = fat.clean(MAX_GRAMS_PER_100),
            carbs = carbohydrates.clean(MAX_GRAMS_PER_100),
            sugar = sugars.clean(MAX_GRAMS_PER_100),
            fiber = fiber.clean(MAX_GRAMS_PER_100),
            protein = protein.clean(MAX_GRAMS_PER_100),
            salt = salt.clean(MAX_GRAMS_PER_100),
            servingName = if (servingGramsValue != null) servingNameValue?.take(60) else null,
            servingGrams = servingGramsValue,
            sources = cleanSources(sources),
            notes = notes?.trim().orEmpty().take(MAX_NOTES),
            confident = confident ?: false,
        )
    }

    private fun String?.text(): String? = this?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

    /** Negative, NaN, infinite or implausibly large values count as "not known". */
    private fun Double?.clean(max: Double): Double? =
        this?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 && it <= max }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0

    fun cleanSources(sources: List<String>): List<String> = sources
        .map { it.trim() }
        .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        .filter { !it.any(Char::isWhitespace) }
        .distinct()
        .take(MAX_SOURCES)

    /** Converts an SDK [JsonValue] (e.g. a tool input) into a kotlinx [JsonElement]. */
    fun JsonValue.toJsonElement(): JsonElement = accept(object : JsonValue.Visitor<JsonElement> {
        override fun visitNull(): JsonElement = JsonNull
        override fun visitMissing(): JsonElement = JsonNull
        override fun visitBoolean(value: Boolean): JsonElement = JsonPrimitive(value)
        override fun visitNumber(value: Number): JsonElement = JsonPrimitive(value)
        override fun visitString(value: String): JsonElement = JsonPrimitive(value)
        override fun visitArray(values: List<JsonValue>): JsonElement = JsonArray(values.map { it.toJsonElement() })
        override fun visitObject(values: Map<String, JsonValue>): JsonElement =
            JsonObject(values.mapValues { (_, v) -> v.toJsonElement() })
        override fun visitDefault(): JsonElement = JsonNull
    })

    // --- DTOs ------------------------------------------------------------------------------------

    @Serializable
    internal data class ProductDto(
        @SerialName(LookupRequests.F_NAME) val name: String? = null,
        @SerialName(LookupRequests.F_BRAND) val brand: String? = null,
        @SerialName(LookupRequests.F_UNIT) val unit: String? = null,
        @SerialName(LookupRequests.F_KCAL) val energyKcal: Double? = null,
        @SerialName(LookupRequests.F_KJ) val energyKj: Double? = null,
        @SerialName(LookupRequests.F_FAT) val fat: Double? = null,
        @SerialName(LookupRequests.F_CARBS) val carbohydrates: Double? = null,
        @SerialName(LookupRequests.F_SUGAR) val sugars: Double? = null,
        @SerialName(LookupRequests.F_FIBER) val fiber: Double? = null,
        @SerialName(LookupRequests.F_PROTEIN) val protein: Double? = null,
        @SerialName(LookupRequests.F_SALT) val salt: Double? = null,
        @SerialName(LookupRequests.F_SERVING_NAME) val servingName: String? = null,
        @SerialName(LookupRequests.F_SERVING_GRAMS) val servingGrams: Double? = null,
        @SerialName(LookupRequests.F_NOTES) val notes: String? = null,
        @SerialName(LookupRequests.F_CONFIDENT) val confident: Boolean? = null,
    )

    /** report_product input: the product fields plus found/sources (flat object). */
    @Serializable
    internal data class ReportDto(
        @SerialName(LookupRequests.F_FOUND) val found: Boolean = false,
        @SerialName(LookupRequests.F_NAME) val name: String? = null,
        @SerialName(LookupRequests.F_BRAND) val brand: String? = null,
        @SerialName(LookupRequests.F_UNIT) val unit: String? = null,
        @SerialName(LookupRequests.F_KCAL) val energyKcal: Double? = null,
        @SerialName(LookupRequests.F_KJ) val energyKj: Double? = null,
        @SerialName(LookupRequests.F_FAT) val fat: Double? = null,
        @SerialName(LookupRequests.F_CARBS) val carbohydrates: Double? = null,
        @SerialName(LookupRequests.F_SUGAR) val sugars: Double? = null,
        @SerialName(LookupRequests.F_FIBER) val fiber: Double? = null,
        @SerialName(LookupRequests.F_PROTEIN) val protein: Double? = null,
        @SerialName(LookupRequests.F_SALT) val salt: Double? = null,
        @SerialName(LookupRequests.F_SERVING_NAME) val servingName: String? = null,
        @SerialName(LookupRequests.F_SERVING_GRAMS) val servingGrams: Double? = null,
        @SerialName(LookupRequests.F_NOTES) val notes: String? = null,
        @SerialName(LookupRequests.F_CONFIDENT) val confident: Boolean? = null,
        @SerialName(LookupRequests.F_SOURCES) val sources: List<String> = emptyList(),
    ) {
        val product: ProductDto
            get() = ProductDto(
                name, brand, unit, energyKcal, energyKj, fat, carbohydrates, sugars, fiber, protein, salt,
                servingName, servingGrams, notes, confident,
            )
    }
}
