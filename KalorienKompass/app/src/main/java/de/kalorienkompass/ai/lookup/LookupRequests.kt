package de.kalorienkompass.ai.lookup

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20260209
import de.kalorienkompass.ai.FoodPhotoRequests
import de.kalorienkompass.data.model.AiModel

/**
 * Builds the Messages API requests of the product lookup (pure, testable on the JVM):
 * reading a photo of a nutrition table and researching a product on the web.
 *
 * Same rules as the photo recognition ([FoodPhotoRequests]): no `thinking` parameter, no forced
 * `tool_choice` (400 on these models), effort MEDIUM, max_tokens 16000, server-side refusal
 * fallbacks (`fallbacks: "default"`) for Opus and Sonnet only.
 */
internal object LookupRequests {

    const val REPORT_TOOL_NAME: String = "report_product"
    const val WEB_SEARCH_MAX_USES: Long = 5L
    private const val MAX_HINT_LENGTH = 200

    // JSON property names (shared by the label schema and the report_product tool)
    const val F_NAME = "name"
    const val F_BRAND = "brand"
    const val F_UNIT = "unit"
    const val F_KCAL = "energy_kcal"
    const val F_KJ = "energy_kj"
    const val F_FAT = "fat"
    const val F_CARBS = "carbohydrates"
    const val F_SUGAR = "sugars"
    const val F_FIBER = "fiber"
    const val F_PROTEIN = "protein"
    const val F_SALT = "salt"
    const val F_SERVING_NAME = "serving_name"
    const val F_SERVING_GRAMS = "serving_grams"
    const val F_NOTES = "notes"
    const val F_CONFIDENT = "confident"
    const val F_FOUND = "found"
    const val F_SOURCES = "sources"

    val UNIT_VALUES: List<String> = listOf("g", "ml")

    // ---------------------------------------------------------------------------------------------
    // Nutrition label photo
    // ---------------------------------------------------------------------------------------------

    val LABEL_SYSTEM_PROMPT: String = """
        Du hilfst in einer deutschsprachigen Kalorienzähler-App dabei, ein verpacktes Lebensmittel zu erfassen. Du liest die Nährwerttabelle (EU-Nährwertdeklaration) auf dem Foto einer Verpackung aus.

        So gehst du vor:
        - Übernimm die Werte so, wie sie in der Spalte „pro 100 g“ bzw. „pro 100 ml“ gedruckt sind – Ziffer für Ziffer, ohne zu runden.
        - Gibt es nur Werte pro Portion, rechne mit der angegebenen Portionsgröße auf 100 g bzw. 100 ml um (Wert ÷ Portionsgröße × 100). Steht keine Portionsgröße dabei, lass die Werte leer (null).
        - unit: „ml“, wenn sich die Tabelle auf 100 ml bezieht (Getränke), sonst „g“.
        - energy_kcal: die kcal-Angabe. energy_kj: die kJ-Angabe. Steht nur kJ auf der Packung, rechne zusätzlich kcal = kJ ÷ 4,184.
        - fat = „Fett“ (nicht „davon gesättigte Fettsäuren“), carbohydrates = „Kohlenhydrate“ (nicht „davon Zucker“), sugars = „davon Zucker“, fiber = „Ballaststoffe“, protein = „Eiweiß“, salt = „Salz“ – jeweils in Gramm.
        - Jeder Wert, den du nicht sicher lesen kannst oder der nicht in der Tabelle steht, ist null. Rate niemals und schätze keine Werte.
        - name und brand: nur wenn Produktname bzw. Marke auf dem Foto zu lesen sind, sonst leerer Text.
        - serving_name und serving_grams: wenn die Packung eine Portion nennt (z. B. „1 Riegel = 25 g“), sonst leerer Text bzw. null. serving_name kurz auf Deutsch, z. B. „1 Riegel“ oder „1 Glas (250 ml)“.
        - confident: false, wenn das Foto unscharf, verdeckt, spiegelnd oder abgeschnitten ist, wenn Werte fehlen oder wenn du dir bei einer Ziffer unsicher bist; sonst true.
        - notes: höchstens zwei kurze Sätze auf Deutsch, z. B. was nicht lesbar war oder dass von Portionswerten umgerechnet wurde; leer, wenn es nichts Wichtiges gibt.
        - Ist auf dem Foto keine Nährwerttabelle zu sehen, setze alle Nährwerte auf null, confident auf false und erkläre es kurz in notes.

        Antworte ausschließlich mit dem JSON-Objekt im vorgegebenen Schema.
    """.trimIndent()

    const val LABEL_USER_PROMPT: String =
        "Lies die Nährwerttabelle auf diesem Foto der Verpackung aus (Werte pro 100 g bzw. 100 ml)."

    /** Request for one photo of a nutrition table (JPEG, base64 without line breaks). */
    fun labelParams(
        model: AiModel,
        imageBase64: String,
        withFallbacks: Boolean = FoodPhotoRequests.supportsFallbacks(model),
    ): MessageCreateParams {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(imageBase64)
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build(),
            )
            .build()
        val text = TextBlockParam.builder().text(LABEL_USER_PROMPT).build()

        val builder = MessageCreateParams.builder()
            .model(model.modelId)
            .maxTokens(FoodPhotoRequests.MAX_TOKENS)
            .system(LABEL_SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(JsonOutputFormat.builder().schema(labelSchema()).build())
                    .build(),
            )
            .addUserMessageOfContentBlockParams(
                listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(text)),
            )
        if (withFallbacks && FoodPhotoRequests.supportsFallbacks(model)) builder.withServerSideFallbacks()
        return builder.build()
    }

    /** Structured-output schema of the label answer (all properties required, no extra properties). */
    fun labelSchema(): JsonOutputFormat.Schema {
        val properties = productProperties()
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty("required", JsonValue.from(properties.keys.toList()))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }

    // ---------------------------------------------------------------------------------------------
    // Web search
    // ---------------------------------------------------------------------------------------------

    val WEB_SYSTEM_PROMPT: String = """
        Du recherchierst für eine deutschsprachige Kalorienzähler-App die Nährwerte eines verpackten Produkts im Internet.

        So gehst du vor:
        - Suche mit der Websuche nach der EAN/GTIN (Barcode) und/oder dem Produktnamen. Gute Quellen sind die Website des Herstellers, große deutsche Händler und Onlineshops (z. B. REWE, EDEKA, Kaufland, Lidl, Aldi, dm, Rossmann, Amazon) und Produktdatenbanken (z. B. Open Food Facts, Codecheck, fddb).
        - Übernimm nur Werte von Seiten, die eindeutig genau dieses Produkt beschreiben: Der Barcode stimmt überein, oder Marke, Produktname, Sorte und Packungsgröße passen eindeutig.
        - Übernimm die Nährwerte genau so, wie sie pro 100 g bzw. 100 ml angegeben sind. Rechne nur um, wenn ausschließlich Werte pro Portion mit Portionsgröße vorliegen. Steht nur kJ da, gib energy_kj an und rechne energy_kcal = kJ ÷ 4,184.
        - fat = Fett, carbohydrates = Kohlenhydrate (ohne Ballaststoffe, nicht „davon Zucker“), sugars = davon Zucker, fiber = Ballaststoffe, protein = Eiweiß, salt = Salz – jeweils in Gramm.
        - Rate niemals und schätze keine Werte. Was du nicht in einer Quelle findest, ist null.
        - name: Produktname auf Deutsch, wie er auf der Packung steht; brand: Marke; unit: „ml“ bei Getränken (Angaben pro 100 ml), sonst „g“; serving_name/serving_grams nur, wenn eine Portion angegeben ist.
        - sources: die URLs der Seiten, von denen die Werte stammen.
        - confident: false, wenn die Quellen sich widersprechen oder nicht ganz sicher dieses Produkt meinen; sonst true.
        - notes: höchstens zwei kurze Sätze auf Deutsch (z. B. welche Quelle genutzt wurde oder was fehlt).
        - Beende deine Arbeit, indem du genau einmal das Tool report_product aufrufst. Setze found=false, wenn du das Produkt nicht zuverlässig gefunden hast oder keine vertrauenswürdigen Nährwerte vorliegen.
    """.trimIndent()

    /** Text of the follow-up message when the model ended its turn without calling report_product. */
    const val WEB_REPORT_REMINDER: String =
        "Bitte melde dein Ergebnis jetzt mit genau einem Aufruf des Tools report_product " +
            "(found=false, wenn du das Produkt nicht zuverlässig gefunden hast)."

    fun webUserPrompt(barcode: String?, nameHint: String?): String {
        val code = cleanBarcode(barcode)
        val name = cleanHint(nameHint)
        return buildString {
            append("Finde die Nährwerte dieses Produkts.")
            if (code != null) {
                append("\n\nBarcode (EAN/GTIN): ")
                append(code)
            }
            if (name != null) {
                append(if (code != null) "\n" else "\n\n")
                append("Produktname (vom Nutzer eingegeben): ")
                append(name)
            }
        }
    }

    /**
     * Request of the web research. [history] holds the turns after the first user message
     * (assistant content of a paused turn, an optional reminder) when a turn is continued.
     */
    fun webSearchParams(
        model: AiModel,
        barcode: String?,
        nameHint: String?,
        withFallbacks: Boolean = FoodPhotoRequests.supportsFallbacks(model),
        history: List<MessageParam> = emptyList(),
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model.modelId)
            .maxTokens(FoodPhotoRequests.MAX_TOKENS)
            .system(WEB_SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            .addTool(
                WebSearchTool20260209.builder()
                    .maxUses(WEB_SEARCH_MAX_USES)
                    .userLocation(UserLocation.builder().country("DE").timezone("Europe/Berlin").build())
                    .build(),
            )
            .addTool(reportProductTool())
            // auto only: forced tool_choice ("any"/"tool") returns 400 on these models
            .toolChoice(ToolChoiceAuto.builder().build())
            .addUserMessage(webUserPrompt(barcode, nameHint))
        history.forEach { builder.addMessage(it) }
        if (withFallbacks && FoodPhotoRequests.supportsFallbacks(model)) builder.withServerSideFallbacks()
        return builder.build()
    }

    /** Strict client tool through which the model reports its result exactly once. */
    fun reportProductTool(): Tool {
        val properties = linkedMapOf<String, Any>(
            F_FOUND to mapOf(
                "type" to "boolean",
                "description" to "true, wenn das Produkt eindeutig gefunden wurde und die Nährwerte aus vertrauenswürdigen Quellen stammen",
            ),
        )
        properties.putAll(productProperties())
        properties[F_SOURCES] = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "string"),
            "description" to "URLs der Seiten, von denen die Werte stammen",
        )
        val schemaProperties = Tool.InputSchema.Properties.builder()
        properties.forEach { (key, value) -> schemaProperties.putAdditionalProperty(key, JsonValue.from(value)) }
        return Tool.builder()
            .name(REPORT_TOOL_NAME)
            .description(
                "Meldet das Ergebnis der Recherche: die Nährwerte des Produkts pro 100 g bzw. 100 ml " +
                    "(oder found=false). Genau einmal am Ende aufrufen.",
            )
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(schemaProperties.build())
                    .required(properties.keys.toList())
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }

    fun cleanBarcode(barcode: String?): String? =
        barcode?.filter { !it.isWhitespace() }?.takeIf { it.isNotEmpty() }?.take(32)

    fun cleanHint(hint: String?): String? =
        hint?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_HINT_LENGTH)

    /**
     * Product properties shared by the label schema and the report tool. Unknown numbers are null
     * (`anyOf` number/null), unknown texts are empty strings. No numeric min/max keywords.
     */
    private fun productProperties(): LinkedHashMap<String, Any> {
        fun nullableNumber(description: String) = mapOf(
            "anyOf" to listOf(mapOf("type" to "number"), mapOf("type" to "null")),
            "description" to description,
        )
        fun string(description: String) = mapOf("type" to "string", "description" to description)
        return linkedMapOf(
            F_NAME to string("Produktname, leer wenn unbekannt"),
            F_BRAND to string("Marke, leer wenn unbekannt"),
            F_UNIT to mapOf(
                "type" to "string",
                "enum" to UNIT_VALUES,
                "description" to "Bezugsgröße der Nährwerte: g (pro 100 g) oder ml (pro 100 ml)",
            ),
            F_KCAL to nullableNumber("Energie in kcal pro 100 g/ml"),
            F_KJ to nullableNumber("Energie in kJ pro 100 g/ml"),
            F_FAT to nullableNumber("Fett in g pro 100 g/ml"),
            F_CARBS to nullableNumber("Kohlenhydrate in g pro 100 g/ml"),
            F_SUGAR to nullableNumber("davon Zucker in g pro 100 g/ml"),
            F_FIBER to nullableNumber("Ballaststoffe in g pro 100 g/ml"),
            F_PROTEIN to nullableNumber("Eiweiß in g pro 100 g/ml"),
            F_SALT to nullableNumber("Salz in g pro 100 g/ml"),
            F_SERVING_NAME to string("Bezeichnung einer Portion, z. B. „1 Riegel“; leer wenn keine angegeben"),
            F_SERVING_GRAMS to nullableNumber("Gramm bzw. Milliliter einer Portion"),
            F_NOTES to string("Höchstens zwei kurze Sätze auf Deutsch, leer wenn nichts Wichtiges"),
            F_CONFIDENT to mapOf(
                "type" to "boolean",
                "description" to "false, wenn Werte unsicher, unvollständig oder schlecht lesbar sind",
            ),
        )
    }

    /** Refusal fallback: the API re-runs a declined request on the model Anthropic recommends. */
    private fun MessageCreateParams.Builder.withServerSideFallbacks(): MessageCreateParams.Builder =
        putAdditionalHeader("anthropic-beta", FoodPhotoRequests.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
}
