package de.kalorienkompass.ui.food

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiErrors
import de.kalorienkompass.ai.AiException
import de.kalorienkompass.ai.ImagePreprocessor
import de.kalorienkompass.ai.lookup.NutritionLabelReader
import de.kalorienkompass.ai.lookup.NutritionLookupResult
import de.kalorienkompass.ai.lookup.ProductWebSearch
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.util.formatInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Everything the custom food screen shows. */
internal data class CustomFoodUiState(
    val form: CustomFoodForm = CustomFoodForm(),
    /** form as loaded/started – used to detect unsaved changes */
    val initialForm: CustomFoodForm = CustomFoodForm(),
    val loading: Boolean = false,
    val notFound: Boolean = false,
    /** the stored food when editing */
    val original: FoodEntity? = null,
    /** show validation errors (after the first save attempt) */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    /** German message of a barcode conflict reported by FoodRepository.saveCustomFood */
    val barcodeConflict: String? = null,
    /** info/disclaimer above the form, e.g. after values were filled in automatically (spec §13) */
    val assistNotice: String? = null,
    /** source URLs shown below [assistNotice] */
    val assistSources: List<String> = emptyList(),
    /** state of the AI lookup ("Nährwerttabelle fotografieren" / "Im Web suchen (KI)") */
    val assist: AssistState = AssistState.Idle,
) {
    val isEditing: Boolean get() = original != null
    val isDirty: Boolean get() = form != initialForm
}

/** Which AI lookup (spec §13). */
internal enum class AssistKind { LABEL, WEB }

/** State of the AI lookup on the custom food form. */
internal sealed interface AssistState {
    data object Idle : AssistState

    /** Request running; [photo] = preview of the nutrition table photo (LABEL only, once prepared). */
    data class Running(val kind: AssistKind, val photo: Bitmap? = null) : AssistState

    /** No API key stored → show the "no key" guidance with a button to the settings. */
    data object MissingKey : AssistState

    /**
     * The lookup failed or found nothing. [message] is German; [opensSettings] = offer the settings
     * (invalid key, no access); [canRetry] = "Erneut versuchen" makes sense.
     */
    data class Failed(
        val kind: AssistKind,
        val message: String,
        val opensSettings: Boolean = false,
        val canRetry: Boolean = true,
    ) : AssistState
}

/**
 * Create or edit an own food (source CUSTOM; AI foods can be corrected as well).
 * The form survives process death via [SavedStateHandle].
 *
 * Integration point for spec §13 ("Nährwerttabelle fotografieren", "Im Web suchen (KI)"):
 * call [prefill] with the values found (per 100 g/ml) and [showAssistNotice] with the disclaimer and
 * source URLs. Nothing is saved until the user taps "Speichern".
 */
class CustomFoodEditViewModel internal constructor(
    private val foodId: Long?,
    initialBarcode: String?,
    private val foodRepository: FoodRepository,
    private val savedState: SavedStateHandle,
    /** application context (photo decoding); null disables the label reader */
    private val appContext: Context? = null,
    private val labelReader: NutritionLabelReader? = null,
    private val webSearch: ProductWebSearch? = null,
    apiKey: Flow<String?> = flowOf(null),
) : ViewModel() {

    internal var state: CustomFoodUiState by mutableStateOf(CustomFoodUiState())
        private set

    /** One-shot snackbar message (German). */
    private val _message = MutableStateFlow<String?>(null)
    internal val message: StateFlow<String?> = _message.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** Unit was set explicitly by [prefill] – a slower DB load must not overwrite it. */
    private var unitPrefilled = false

    /** true/false once known, null while loading. */
    internal val hasApiKey: StateFlow<Boolean?> = apiKey
        .map { !it.isNullOrBlank() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var assistJob: Job? = null

    /** JPEG of the last nutrition table photo (for "Erneut versuchen"). */
    private var lastLabelJpeg: ByteArray? = null

    init {
        val restored = savedState.get<String>(KEY_FORM)?.let { stored ->
            runCatching { json.decodeFromString(CustomFoodForm.serializer(), stored) }.getOrNull()
        }
        val restoredInitial = savedState.get<String>(KEY_INITIAL_FORM)?.let { stored ->
            runCatching { json.decodeFromString(CustomFoodForm.serializer(), stored) }.getOrNull()
        }
        when {
            foodId != null -> {
                state = CustomFoodUiState(
                    form = restored ?: CustomFoodForm(),
                    initialForm = restoredInitial ?: CustomFoodForm(),
                    loading = true,
                )
                load(foodId, keepRestored = restored != null)
            }
            restored != null -> state = CustomFoodUiState(form = restored, initialForm = restoredInitial ?: restored)
            else -> {
                val start = CustomFoodForm(
                    barcode = initialBarcode?.trim().orEmpty(),
                    portions = listOf(PortionInput()),
                )
                state = CustomFoodUiState(form = start, initialForm = start)
                persistInitial(start)
            }
        }
    }

    private fun load(id: Long, keepRestored: Boolean) {
        viewModelScope.launch {
            val food = try {
                foodRepository.getFood(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (food == null) {
                state = state.copy(loading = false, notFound = true)
                return@launch
            }
            val loaded = food.toForm().let { if (it.portions.isEmpty()) it.copy(portions = listOf(PortionInput())) else it }
            val form = if (keepRestored) state.form else merge(current = state.form, loaded = loaded)
            val initial = if (keepRestored) state.initialForm else loaded
            state = state.copy(form = form, initialForm = initial, original = food, loading = false)
            persist(form)
            persistInitial(initial)
        }
    }

    /** Values the user (or [prefill]) entered before the DB load finished win over the stored ones. */
    private fun merge(current: CustomFoodForm, loaded: CustomFoodForm): CustomFoodForm = CustomFoodForm(
        name = current.name.ifBlank { loaded.name },
        brand = current.brand.ifBlank { loaded.brand },
        barcode = current.barcode.ifBlank { loaded.barcode },
        unit = if (unitPrefilled) current.unit else loaded.unit,
        kcal = current.kcal.ifBlank { loaded.kcal },
        fat = current.fat.ifBlank { loaded.fat },
        carbs = current.carbs.ifBlank { loaded.carbs },
        sugar = current.sugar.ifBlank { loaded.sugar },
        fiber = current.fiber.ifBlank { loaded.fiber },
        protein = current.protein.ifBlank { loaded.protein },
        salt = current.salt.ifBlank { loaded.salt },
        portions = if (current.portions.any { it.name.isNotBlank() || it.grams.isNotBlank() }) current.portions else loaded.portions,
    )

    /** Live validation (errors are only displayed once [CustomFoodUiState.showErrors] is set). */
    internal val validation: FormValidation get() = validateCustomFood(state.form)

    internal fun update(transform: (CustomFoodForm) -> CustomFoodForm) {
        val before = state.form
        val after = transform(before)
        if (after == before) return
        state = state.copy(
            form = after,
            barcodeConflict = if (after.barcode != before.barcode) null else state.barcodeConflict,
        )
        persist(after)
    }

    internal fun addPortion() = update { it.copy(portions = it.portions + PortionInput()) }

    internal fun removePortion(index: Int) = update { form ->
        form.copy(portions = form.portions.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(PortionInput()) })
    }

    internal fun updatePortion(index: Int, transform: (PortionInput) -> PortionInput) = update { form ->
        form.copy(portions = form.portions.mapIndexed { i, p -> if (i == index) transform(p) else p })
    }

    /** Uses the energy computed from the macros (only offered while the kcal field is empty). */
    internal fun applyEstimatedKcal(kcal: Double) = update { it.copy(kcal = formatInput(kcal, maxDecimals = 0)) }

    /** Name typed in the search before "Eigenes Lebensmittel anlegen" (new foods only). */
    internal fun applySearchName(name: String) {
        if (foodId != null || state.form.name.isNotBlank()) return
        val pristine = !state.isDirty
        update { it.copy(name = name.take(120)) }
        if (pristine) {
            state = state.copy(initialForm = state.form)
            persistInitial(state.form)
        }
    }

    /**
     * Fills the form with values found elsewhere (e.g. read from a photo of the nutrition table or
     * researched on the web; all nutrition values per 100 g/ml) WITHOUT saving. Only non-null
     * values replace the current input. Shows a "bitte prüfen" notice unless one is already shown
     * (replace it with [showAssistNotice]).
     */
    fun prefill(
        name: String? = null,
        brand: String? = null,
        unit: String? = null,
        kcal: Double? = null,
        fat: Double? = null,
        carbs: Double? = null,
        sugar: Double? = null,
        fiber: Double? = null,
        protein: Double? = null,
        salt: Double? = null,
        servingName: String? = null,
        servingGrams: Double? = null,
    ) {
        val normalizedUnit = normalizeUnit(unit)
        if (normalizedUnit != null) unitPrefilled = true

        update { form ->
            form.withPrefill(
                name = name,
                brand = brand,
                unit = unit,
                kcal = kcal,
                fat = fat,
                carbs = carbs,
                sugar = sugar,
                fiber = fiber,
                protein = protein,
                salt = salt,
                servingName = servingName,
                servingGrams = servingGrams,
            )
        }
        if (state.assistNotice == null) {
            state = state.copy(assistNotice = DEFAULT_PREFILL_NOTICE)
        }
    }

    /**
     * Shows an info box above the form (e.g. "Von der KI gelesen – bitte Werte prüfen") with optional
     * source links. Pass null to hide it.
     */
    fun showAssistNotice(message: String?, sourceUrls: List<String> = emptyList()) {
        state = state.copy(
            assistNotice = message?.takeIf { it.isNotBlank() },
            assistSources = if (message.isNullOrBlank()) emptyList() else sourceUrls.filter { it.isNotBlank() }.distinct(),
        )
    }

    /** Current form input (e.g. as a name hint for a web search). */
    val currentName: String get() = state.form.name.trim()

    /** Current barcode input, null if empty. */
    val currentBarcode: String? get() = state.form.barcode.trim().takeIf { it.isNotEmpty() }

    internal fun dismissAssistNotice() = showAssistNotice(null)

    // ---------------------------------------------------------------------------------------------
    // AI lookup (spec §13): photo of the nutrition table / web search → prefill (never saves)
    // ---------------------------------------------------------------------------------------------

    /**
     * True exactly once per screen (survives recreation): whether the action requested by the route
     * (`action=label|web`) should be started now.
     */
    internal fun consumeAutoAction(): Boolean {
        if (savedState.get<Boolean>(KEY_AUTO_ACTION_DONE) == true) return false
        savedState[KEY_AUTO_ACTION_DONE] = true
        return true
    }

    /** False (and the "no key" guidance is shown) if no API key is stored. */
    internal fun ensureApiKey(): Boolean {
        if (hasApiKey.value == false) {
            state = state.copy(assist = AssistState.MissingKey)
            return false
        }
        return true
    }

    internal val isAssistRunning: Boolean get() = state.assist is AssistState.Running

    /** Photo taken with the camera into [file] (deleted after reading). */
    internal fun onLabelPhotoCaptured(file: File) = readLabel(Uri.fromFile(file), deleteAfterReading = file)

    /** Photo chosen from the gallery. */
    internal fun onLabelPhotoPicked(uri: Uri) = readLabel(uri, deleteAfterReading = null)

    private fun readLabel(uri: Uri, deleteAfterReading: File?) {
        val context = appContext
        val reader = labelReader
        if (context == null || reader == null) {
            state = state.copy(assist = AssistState.Failed(AssistKind.LABEL, UNAVAILABLE_MESSAGE, canRetry = false))
            return
        }
        assistJob?.cancel()
        state = state.copy(assist = AssistState.Running(AssistKind.LABEL))
        assistJob = viewModelScope.launch {
            val prepared = try {
                ImagePreprocessor.prepare(context, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showAssistError(AssistKind.LABEL, AiErrors.from(e), canRetry = false)
                return@launch
            } finally {
                if (deleteAfterReading != null) withContext(Dispatchers.IO) { deleteAfterReading.delete() }
            }
            lastLabelJpeg = prepared.jpeg
            state = state.copy(assist = AssistState.Running(AssistKind.LABEL, prepared.bitmap))
            runLabel(reader, prepared.jpeg)
        }
    }

    private suspend fun runLabel(reader: NutritionLabelReader, jpeg: ByteArray) {
        try {
            val result = reader.read(jpeg)
            applyLookupResult(result, AssistKind.LABEL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showAssistError(AssistKind.LABEL, AiErrors.from(e))
        }
    }

    /** Web research by the barcode and/or the name (+ brand) currently in the form. */
    internal fun startWebSearch() {
        val search = webSearch
        if (search == null) {
            state = state.copy(assist = AssistState.Failed(AssistKind.WEB, UNAVAILABLE_MESSAGE, canRetry = false))
            return
        }
        val barcode = currentBarcode
        val nameHint = listOf(state.form.brand.trim(), state.form.name.trim())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
        if (barcode == null && nameHint == null) {
            state = state.copy(
                assist = AssistState.Failed(
                    AssistKind.WEB,
                    "Gib zuerst einen Barcode oder den Produktnamen (am besten mit Marke) ein – danach kann die KI suchen.",
                    canRetry = false,
                ),
            )
            return
        }
        assistJob?.cancel()
        state = state.copy(assist = AssistState.Running(AssistKind.WEB))
        assistJob = viewModelScope.launch {
            try {
                val result = search.search(barcode, nameHint)
                if (result == null) {
                    state = state.copy(
                        assist = AssistState.Failed(
                            AssistKind.WEB,
                            "Die KI hat im Web keine verlässlichen Nährwerte zu diesem Produkt gefunden. " +
                                "Fotografiere am besten die Nährwerttabelle oder gib die Werte manuell ein.",
                        ),
                    )
                } else {
                    applyLookupResult(result, AssistKind.WEB)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showAssistError(AssistKind.WEB, AiErrors.from(e))
            }
        }
    }

    /** "Erneut versuchen" after a failure: repeats the web search or the last photo. */
    internal fun retryAssist(): Boolean {
        val failed = state.assist as? AssistState.Failed ?: return false
        return when (failed.kind) {
            AssistKind.WEB -> {
                startWebSearch()
                true
            }
            AssistKind.LABEL -> {
                val jpeg = lastLabelJpeg
                val reader = labelReader
                if (jpeg == null || reader == null) return false // caller opens the camera again
                assistJob?.cancel()
                state = state.copy(assist = AssistState.Running(AssistKind.LABEL))
                assistJob = viewModelScope.launch { runLabel(reader, jpeg) }
                true
            }
        }
    }

    internal fun cancelAssist() {
        assistJob?.cancel()
        assistJob = null
        state = state.copy(assist = AssistState.Idle)
    }

    internal fun dismissAssist() {
        if (state.assist !is AssistState.Running) state = state.copy(assist = AssistState.Idle)
    }

    private fun showAssistError(kind: AssistKind, error: AiException, canRetry: Boolean = error.isRetryable) {
        state = if (error.kind == AiErrorKind.MISSING_KEY) {
            state.copy(assist = AssistState.MissingKey)
        } else {
            state.copy(
                assist = AssistState.Failed(
                    kind = kind,
                    message = error.message ?: "Die KI-Anfrage ist fehlgeschlagen.",
                    opensSettings = error.opensSettings,
                    canRetry = canRetry,
                ),
            )
        }
    }

    /** Prefills the form (never saves) and shows the disclaimer, notes and sources. */
    private fun applyLookupResult(result: NutritionLookupResult, kind: AssistKind) {
        prefill(
            name = result.name,
            brand = result.brand,
            unit = result.unit,
            kcal = result.kcal,
            fat = result.fat,
            carbs = result.carbs,
            sugar = result.sugar,
            fiber = result.fiber,
            protein = result.protein,
            salt = result.salt,
            servingName = result.servingName,
            servingGrams = result.servingGrams,
        )
        state = state.copy(assist = AssistState.Idle, showErrors = false)
        showAssistNotice(lookupNotice(result, kind), result.sources)
        _message.value = if (kind == AssistKind.LABEL) "Werte aus dem Foto übernommen" else "Werte aus dem Web übernommen"
    }

    /** Validates and saves; [onSaved] receives the food id. */
    internal fun save(onSaved: (Long) -> Unit) {
        if (state.saving || state.loading) return
        val check = validateCustomFood(state.form)
        if (!check.isValid) {
            state = state.copy(showErrors = true)
            _message.value = check.generalError ?: "Bitte prüfe die markierten Felder."
            return
        }
        state = state.copy(saving = true, barcodeConflict = null)
        viewModelScope.launch {
            try {
                val id = foodRepository.saveCustomFood(state.form.toFoodEntity(state.original))
                savedState.remove<String>(KEY_FORM)
                savedState.remove<String>(KEY_INITIAL_FORM)
                state = state.copy(saving = false, initialForm = state.form)
                onSaved(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // e.g. "Der Barcode … ist bereits dem Lebensmittel „…“ zugeordnet."
                val text = e.message ?: "Dieser Barcode ist bereits einem anderen Lebensmittel zugeordnet."
                state = state.copy(saving = false, showErrors = true, barcodeConflict = text)
                _message.value = text
            } catch (e: Exception) {
                state = state.copy(saving = false)
                _message.value = "Speichern fehlgeschlagen. Bitte versuche es erneut."
            }
        }
    }

    /** Deletes the edited food; refuses (with a message) if it is used in a recipe. */
    internal fun delete(onDeleted: () -> Unit) {
        val original = state.original ?: return
        if (state.deleting) return
        state = state.copy(deleting = true)
        viewModelScope.launch {
            val deleted = try {
                foodRepository.deleteFood(original)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(deleting = false)
                _message.value = "Löschen fehlgeschlagen. Bitte versuche es erneut."
                return@launch
            }
            state = state.copy(deleting = false)
            if (deleted) {
                savedState.remove<String>(KEY_FORM)
                savedState.remove<String>(KEY_INITIAL_FORM)
                onDeleted()
            } else {
                _message.value = "„${original.name}“ wird in einem Rezept verwendet und kann nicht gelöscht werden. " +
                    "Entferne es zuerst aus dem Rezept."
            }
        }
    }

    internal fun messageShown() {
        _message.value = null
    }

    private fun persist(form: CustomFoodForm) {
        savedState[KEY_FORM] = json.encodeToString(CustomFoodForm.serializer(), form)
    }

    private fun persistInitial(form: CustomFoodForm) {
        savedState[KEY_INITIAL_FORM] = json.encodeToString(CustomFoodForm.serializer(), form)
    }

    companion object {
        private const val KEY_FORM = "custom_food_form"
        private const val KEY_INITIAL_FORM = "custom_food_initial_form"
        private const val KEY_AUTO_ACTION_DONE = "custom_food_auto_action_done"
        const val DEFAULT_PREFILL_NOTICE = "Werte wurden automatisch übernommen – bitte vor dem Speichern prüfen."
        private const val UNAVAILABLE_MESSAGE = "Diese KI-Funktion ist gerade nicht verfügbar."
    }
}

/** Disclaimer shown after an AI lookup (spec §13), plus what is uncertain or missing. */
internal fun lookupNotice(result: NutritionLookupResult, kind: AssistKind): String = buildString {
    append(
        if (kind == AssistKind.LABEL) {
            "Von der KI gelesen – bitte Werte mit der Packung abgleichen."
        } else {
            "Von der KI recherchiert – bitte Werte mit der Packung abgleichen."
        },
    )
    val missing = listOfNotNull(
        "Energie".takeIf { result.kcal == null },
        "Fett".takeIf { result.fat == null },
        "Kohlenhydrate".takeIf { result.carbs == null },
        "Eiweiß".takeIf { result.protein == null },
    )
    if (missing.isNotEmpty()) {
        append(" Nicht gefunden: ")
        append(missing.joinToString(", "))
        append(" – bitte ergänzen.")
    }
    if (!result.confident) append(" Die KI war sich nicht bei allen Werten sicher.")
    val notes = result.notes.trim()
    if (notes.isNotEmpty()) {
        append("\nHinweis der KI: ")
        append(notes)
    }
}
