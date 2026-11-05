package de.kalorienkompass.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import de.kalorienkompass.data.model.AiModel

/**
 * Builds the Messages API requests of the photo recognition (pure, testable on the JVM).
 *
 * - no `thinking` parameter (Claude 5.5 models think adaptively; disabling it returns 400),
 * - no forced `tool_choice` (400 on these models) – the JSON shape comes from structured outputs
 *   (`output_config.format`),
 * - server-side refusal fallbacks (`fallbacks: "default"`) for Opus and Sonnet; Haiku has none.
 */
internal object FoodPhotoRequests {

    const val MAX_TOKENS: Long = 16_000L
    const val TEST_MAX_TOKENS: Long = 16L
    const val FALLBACK_BETA: String = "server-side-fallback-2026-07-01"
    private const val MAX_HINT_LENGTH = 500

    const val CONFIDENCE_HIGH = "hoch"
    const val CONFIDENCE_MEDIUM = "mittel"
    const val CONFIDENCE_LOW = "niedrig"
    val CONFIDENCE_VALUES: List<String> = listOf(CONFIDENCE_HIGH, CONFIDENCE_MEDIUM, CONFIDENCE_LOW)

    val SYSTEM_PROMPT: String = """
        Du hilfst in einer deutschsprachigen Kalorienzähler-App dabei, Mahlzeiten anhand eines Fotos zu erfassen. Du bestimmst, welche Lebensmittel und Getränke zu sehen sind und wie viel davon.

        So gehst du vor:
        - Erfasse jede erkennbare Komponente als eigenen Eintrag: Hauptspeise, Beilagen, Saucen, Dressings, Toppings und Getränke getrennt (z. B. Spaghetti, Tomatensauce und Parmesan als drei Einträge). Fasse nur zusammen, was sich nicht sinnvoll trennen lässt (z. B. Lasagne, Eintopf, Auflauf).
        - grams: geschätzte Menge der Portion auf dem Foto in Gramm; bei Getränken Milliliter. Nutze Größenhinweise wie Teller (meist 24–28 cm Durchmesser), Besteck, Gläser, Tassen, Hände oder Verpackungen. Schätze realistisch und nicht absichtlich niedrig.
        - Rechne erkennbares oder für die Zubereitung typisches Fett (Öl, Butter, Dressing) in die Nährwerte der jeweiligen Speise ein oder erfasse es als eigenen Eintrag, wenn es deutlich sichtbar ist.
        - name: kurzer, verständlicher deutscher Name, wie man ihn in ein Ernährungstagebuch schreiben würde (z. B. „Spaghetti“, „Hähnchenbrust, gebraten“).
        - search_term: kurzer, allgemeiner deutscher Suchbegriff für eine Lebensmitteldatenbank, ohne Marke und Menge, mit Zubereitungszustand wenn er die Nährwerte verändert (z. B. „Spaghetti gekocht“, „Tomatensauce“, „Reis gekocht“, „Hähnchenbrust gebraten“).
        - Nährwerte (kcal_per_100g, protein_per_100g, carbs_per_100g, fat_per_100g) immer pro 100 g des Lebensmittels so, wie es auf dem Foto verzehrfertig zu sehen ist (z. B. gekochte statt rohe Nudeln). Kohlenhydrate ohne Ballaststoffe, wie in der EU-Nährwertkennzeichnung.
        - confidence: „hoch“, wenn Lebensmittel und Menge gut erkennbar sind; „mittel“, wenn Menge oder Zubereitung unsicher sind; „niedrig“, wenn du raten musst.
        - meal_name: kurzer deutscher Name für die ganze Mahlzeit (z. B. „Spaghetti Bolognese mit Parmesan“).
        - notes: höchstens zwei kurze Sätze auf Deutsch zu wichtigen Annahmen (z. B. verstecktes Fett, verdeckte Zutaten, Portionsgröße); leer, wenn es nichts Wichtiges gibt.
        - Ein Hinweis des Nutzers zu Zutaten, Zubereitung oder Menge hat Vorrang vor deinem Eindruck vom Foto.
        - Ist auf dem Foto nichts Essbares zu erkennen, gib eine leere Liste items zurück und erkläre es kurz in notes.

        Antworte ausschließlich mit dem JSON-Objekt im vorgegebenen Schema.
    """.trimIndent()

    fun supportsFallbacks(model: AiModel): Boolean = when (model) {
        AiModel.OPUS, AiModel.SONNET -> true
        AiModel.HAIKU -> false
    }

    fun userPrompt(hint: String?): String {
        val cleanHint = hint
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_HINT_LENGTH)
            .orEmpty()
        return buildString {
            append("Analysiere dieses Foto meiner Mahlzeit und erfasse alle sichtbaren Lebensmittel und Getränke.")
            if (cleanHint.isNotEmpty()) {
                append("\n\nMein Hinweis dazu: ")
                append(cleanHint)
            }
        }
    }

    /** Request for one photo (JPEG, base64 without line breaks). */
    fun analyzeParams(
        model: AiModel,
        imageBase64: String,
        hint: String?,
        withFallbacks: Boolean = supportsFallbacks(model),
    ): MessageCreateParams {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(imageBase64)
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build(),
            )
            .build()
        val text = TextBlockParam.builder().text(userPrompt(hint)).build()

        val builder = MessageCreateParams.builder()
            .model(model.modelId)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(JsonOutputFormat.builder().schema(mealAnalysisSchema()).build())
                    .build(),
            )
            .addUserMessageOfContentBlockParams(
                listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(text)),
            )
        if (withFallbacks && supportsFallbacks(model)) builder.withServerSideFallbacks()
        return builder.build()
    }

    /** Minimal request to check key + model. */
    fun testParams(model: AiModel): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model.modelId)
            .maxTokens(TEST_MAX_TOKENS)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage("Antworte nur mit: OK")
            .build()

    /**
     * JSON schema of the answer. Every object lists all properties in `required` and sets
     * `additionalProperties: false`; no numeric min/max keywords (not supported by structured outputs).
     */
    fun mealAnalysisSchema(): JsonOutputFormat.Schema {
        val number = { description: String -> mapOf("type" to "number", "description" to description) }
        val itemSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Kurzer deutscher Name des Lebensmittels"),
                "search_term" to mapOf(
                    "type" to "string",
                    "description" to "Allgemeiner deutscher Suchbegriff für eine Lebensmitteldatenbank",
                ),
                "grams" to number("Geschätzte Menge auf dem Foto in Gramm (Getränke in Milliliter)"),
                "kcal_per_100g" to number("Energie in kcal pro 100 g"),
                "protein_per_100g" to number("Eiweiß in g pro 100 g"),
                "carbs_per_100g" to number("Kohlenhydrate (ohne Ballaststoffe) in g pro 100 g"),
                "fat_per_100g" to number("Fett in g pro 100 g"),
                "confidence" to mapOf(
                    "type" to "string",
                    "enum" to CONFIDENCE_VALUES,
                    "description" to "Wie sicher Lebensmittel und Menge erkannt wurden",
                ),
            ),
            "required" to ITEM_FIELDS,
            "additionalProperties" to false,
        )
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "meal_name" to mapOf("type" to "string", "description" to "Kurzer deutscher Name der Mahlzeit"),
                        "notes" to mapOf("type" to "string", "description" to "Wichtige Annahmen, höchstens zwei Sätze"),
                        "items" to mapOf("type" to "array", "items" to itemSchema),
                    ),
                ),
            )
            .putAdditionalProperty("required", JsonValue.from(MEAL_FIELDS))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }

    private val MEAL_FIELDS = listOf("meal_name", "notes", "items")
    private val ITEM_FIELDS = listOf(
        "name", "search_term", "grams", "kcal_per_100g", "protein_per_100g", "carbs_per_100g", "fat_per_100g", "confidence",
    )

    /** Refusal fallback: the API re-runs a declined request on the model Anthropic recommends. */
    private fun MessageCreateParams.Builder.withServerSideFallbacks(): MessageCreateParams.Builder =
        putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
}
