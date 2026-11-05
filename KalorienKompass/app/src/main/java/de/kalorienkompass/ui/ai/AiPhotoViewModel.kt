package de.kalorienkompass.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import de.kalorienkompass.ai.AiErrorKind
import de.kalorienkompass.ai.AiErrors
import de.kalorienkompass.ai.AiException
import de.kalorienkompass.ai.AiFoodItem
import de.kalorienkompass.ai.AiFoodMatcher
import de.kalorienkompass.ai.FoodCandidate
import de.kalorienkompass.ai.FoodMatchResult
import de.kalorienkompass.ai.FoodPhotoAnalyzer
import de.kalorienkompass.ai.ImagePreprocessor
import de.kalorienkompass.ai.PreparedPhoto
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.FoodPortion
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.domain.NutritionMath
import de.kalorienkompass.util.formatInput
import de.kalorienkompass.util.parseDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToLong

enum class AnalysisStep(val title: String, val subtitle: String) {
    PREPARING("Foto wird vorbereitet…", "Das Foto wird verkleinert und ausgerichtet."),
    ANALYZING("KI analysiert dein Essen…", "Das dauert meist 10–30 Sekunden."),
    MATCHING("Passende Lebensmittel werden gesucht…", "Gleich geschafft."),
}

/** One recognized food in the review list. */
data class ReviewItem(
    val key: Long,
    val ai: AiFoodItem,
    /** Amount as typed by the user (g or ml). */
    val amountText: String,
    val candidates: List<FoodCandidate>,
    /** Chosen database food; null = use the AI estimate ("KI-Schätzung"). */
    val selectedFood: FoodEntity?,
) {
    /** Parsed amount; null if empty or invalid. */
    val amount: Double?
        get() = parseDecimal(amountText)?.takeIf { it > 0.0 && it <= MAX_AMOUNT }

    val usesAiEstimate: Boolean get() = selectedFood == null

    /** "g" or "ml" (from the chosen database food). */
    val unit: String
        get() = selectedFood?.unit?.takeIf { it.isNotBlank() } ?: "g"

    val per100: Nutrition
        get() = selectedFood?.let { NutritionMath.per100(it) }
            ?: Nutrition(ai.kcalPer100g, ai.proteinPer100g, ai.carbsPer100g, ai.fatPer100g)

    val nutrition: Nutrition
        get() = per100 * ((amount ?: 0.0) / 100.0)

    companion object {
        const val MAX_AMOUNT = 5_000.0
    }
}

sealed interface AiPhotoUiState {
    data object Start : AiPhotoUiState

    data class Analyzing(val photo: Bitmap?, val step: AnalysisStep) : AiPhotoUiState

    data class Review(
        val photo: Bitmap?,
        val mealName: String,
        val notes: String,
        val items: List<ReviewItem>,
        val saving: Boolean = false,
    ) : AiPhotoUiState {
        val total: Nutrition
            get() = items.fold(Nutrition.ZERO) { acc, item -> acc + item.nutrition }

        /** Items with a valid amount (only these are added). */
        val addableCount: Int
            get() = items.count { it.amount != null }
    }

    data class Failure(
        val message: String,
        val kind: AiErrorKind,
        val photo: Bitmap?,
        val canRetry: Boolean,
    ) : AiPhotoUiState
}

sealed interface AiPhotoEvent {
    data class Added(val count: Int, val meal: MealType) : AiPhotoEvent
    data class ShowMessage(val text: String) : AiPhotoEvent
    data class ItemRemoved(val key: Long, val name: String) : AiPhotoEvent
}

class AiPhotoViewModel(
    private val appContext: Context,
    private val analyzer: FoodPhotoAnalyzer,
    private val matcher: AiFoodMatcher,
    private val foodRepository: FoodRepository,
    private val diaryRepository: DiaryRepository,
    private val database: AppDatabase,
    settingsRepository: SettingsRepository,
    val date: LocalDate,
    initialMeal: MealType,
) : ViewModel() {

    /** null while loading. */
    val hasApiKey: StateFlow<Boolean?> = settingsRepository.aiApiKey
        .map { !it.isNullOrBlank() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val aiModel: StateFlow<AiModel> = settingsRepository.settings
        .map { it.aiModel }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiModel.OPUS)

    private val _state = MutableStateFlow<AiPhotoUiState>(AiPhotoUiState.Start)
    val state: StateFlow<AiPhotoUiState> = _state.asStateFlow()

    private val _meal = MutableStateFlow(initialMeal)
    val meal: StateFlow<MealType> = _meal.asStateFlow()

    private val _events = Channel<AiPhotoEvent>(Channel.BUFFERED)
    val events: Flow<AiPhotoEvent> = _events.receiveAsFlow()

    private var analysisJob: Job? = null
    private var lastPhoto: PreparedPhoto? = null
    private var lastHint: String? = null
    private var nextKey = 1L
    private val removedItems = HashMap<Long, Pair<Int, ReviewItem>>()

    /** Photo taken with the camera into [file]; the file is deleted once it has been read. */
    fun onPhotoCaptured(file: File, hint: String) = startAnalysis(Uri.fromFile(file), hint, deleteAfterReading = file)

    /** Photo chosen from the gallery. */
    fun onPhotoPicked(uri: Uri, hint: String) = startAnalysis(uri, hint, deleteAfterReading = null)

    /** Repeats the analysis of the last photo (after an error). */
    fun retry() {
        val photo = lastPhoto
        if (photo == null) {
            _state.value = AiPhotoUiState.Start
            return
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch { analyze(photo, lastHint) }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _state.value = AiPhotoUiState.Start
    }

    /** Back to the start screen (new photo); the current result is discarded. */
    fun startOver() {
        analysisJob?.cancel()
        analysisJob = null
        lastPhoto = null
        removedItems.clear()
        _state.value = AiPhotoUiState.Start
    }

    fun setMeal(meal: MealType) {
        _meal.value = meal
    }

    fun updateAmount(key: Long, text: String) = updateItem(key) { it.copy(amountText = text) }

    /** [food] = null → use the AI estimate. Remote (not yet stored) foods are cached first. */
    fun selectFood(key: Long, food: FoodEntity?) {
        if (food == null || food.id != 0L) {
            applySelection(key, food)
            return
        }
        viewModelScope.launch {
            try {
                val id = foodRepository.cacheRemoteFood(food)
                applySelection(key, foodRepository.getFood(id) ?: food.copy(id = id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(AiPhotoEvent.ShowMessage("Das Lebensmittel konnte nicht übernommen werden."))
            }
        }
    }

    fun removeItem(key: Long) {
        val review = _state.value as? AiPhotoUiState.Review ?: return
        val index = review.items.indexOfFirst { it.key == key }
        if (index < 0) return
        val item = review.items[index]
        removedItems[key] = index to item
        _state.value = review.copy(items = review.items.filterNot { it.key == key })
        _events.trySend(AiPhotoEvent.ItemRemoved(key, item.ai.name))
    }

    fun undoRemove(key: Long) {
        val (index, item) = removedItems.remove(key) ?: return
        _state.update { current ->
            if (current !is AiPhotoUiState.Review || current.items.any { it.key == key }) return@update current
            val items = current.items.toMutableList()
            items.add(index.coerceIn(0, items.size), item)
            current.copy(items = items)
        }
    }

    /** Local food search for the "Anderes Lebensmittel" dialog. */
    suspend fun searchLocal(query: String): List<FoodEntity> {
        val q = query.trim()
        return if (q.length < 2) emptyList() else foodRepository.searchLocal(q, SEARCH_LIMIT)
    }

    /** Open Food Facts search (results are not stored yet). Throws IOException on network problems. */
    suspend fun searchRemote(query: String): List<FoodEntity> {
        val q = query.trim()
        return if (q.length < 2) emptyList() else foodRepository.searchRemote(q)
    }

    /** Adds every item with a valid amount to the diary (one transaction). */
    fun addAll() {
        val review = _state.value as? AiPhotoUiState.Review ?: return
        if (review.saving) return
        val items = review.items.filter { it.amount != null }
        if (items.isEmpty()) {
            _events.trySend(AiPhotoEvent.ShowMessage("Bitte gib bei mindestens einem Lebensmittel eine Menge an."))
            return
        }
        val meal = _meal.value
        _state.value = review.copy(saving = true)
        viewModelScope.launch {
            try {
                database.withTransaction {
                    for (item in items) {
                        val amount = item.amount ?: continue
                        val food = item.selectedFood ?: saveAiFood(item.ai, amount)
                        diaryRepository.addFood(date, meal, food, amount, food.unit.ifBlank { "g" }, amount)
                    }
                }
                _events.send(AiPhotoEvent.Added(items.size, meal))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { (it as? AiPhotoUiState.Review)?.copy(saving = false) ?: it }
                _events.send(
                    AiPhotoEvent.ShowMessage("Speichern fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"),
                )
            }
        }
    }

    private fun startAnalysis(uri: Uri, hint: String, deleteAfterReading: File?) {
        analysisJob?.cancel()
        removedItems.clear()
        analysisJob = viewModelScope.launch {
            _state.value = AiPhotoUiState.Analyzing(photo = null, step = AnalysisStep.PREPARING)
            val photo = try {
                ImagePreprocessor.prepare(appContext, uri)
            } catch (e: AiException) {
                _state.value = AiPhotoUiState.Failure(e.message ?: DEFAULT_ERROR, e.kind, photo = null, canRetry = false)
                return@launch
            } finally {
                if (deleteAfterReading != null) {
                    withContext(NonCancellable + Dispatchers.IO) { deleteAfterReading.delete() }
                }
            }
            lastPhoto = photo
            lastHint = hint.trim().takeIf { it.isNotEmpty() }
            analyze(photo, lastHint)
        }
    }

    private suspend fun analyze(photo: PreparedPhoto, hint: String?) {
        _state.value = AiPhotoUiState.Analyzing(photo.bitmap, AnalysisStep.ANALYZING)
        val analysis = try {
            analyzer.analyze(photo.jpeg, hint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e as? AiException ?: AiErrors.from(e)
            _state.value = AiPhotoUiState.Failure(
                message = error.message ?: DEFAULT_ERROR,
                kind = error.kind,
                photo = photo.bitmap,
                canRetry = error.isRetryable || error.kind == AiErrorKind.REFUSAL,
            )
            return
        }

        _state.value = AiPhotoUiState.Analyzing(photo.bitmap, AnalysisStep.MATCHING)
        val items = analysis.items.map { item ->
            val match = try {
                matcher.match(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FoodMatchResult.EMPTY
            }
            ReviewItem(
                key = nextKey++,
                ai = item,
                amountText = formatAmount(item.grams),
                candidates = match.candidates,
                selectedFood = match.best,
            )
        }
        _state.value = AiPhotoUiState.Review(
            photo = photo.bitmap,
            mealName = analysis.mealName,
            notes = analysis.notes,
            items = items,
        )
    }

    private fun applySelection(key: Long, food: FoodEntity?) = updateItem(key) { item ->
        val candidates = if (food != null && item.candidates.none { it.food.id == food.id }) {
            listOf(FoodCandidate(food, 1.0)) + item.candidates
        } else {
            item.candidates
        }
        item.copy(selectedFood = food, candidates = candidates)
    }

    private inline fun updateItem(key: Long, crossinline transform: (ReviewItem) -> ReviewItem) {
        _state.update { current ->
            if (current !is AiPhotoUiState.Review || current.saving) return@update current
            current.copy(items = current.items.map { if (it.key == key) transform(it) else it })
        }
    }

    /**
     * Stores the AI estimate as a food (source AI) so the diary entry links to it and it shows up
     * under "Zuletzt". An earlier AI food with the same name is updated instead of duplicated.
     */
    private suspend fun saveAiFood(ai: AiFoodItem, amount: Double): FoodEntity {
        val name = ai.name.trim().ifEmpty { ai.searchTerm.trim() }.ifEmpty { "KI-Schätzung" }
        val existing = foodRepository.searchLocal(name, 20)
            .firstOrNull { it.source == FoodSource.AI && it.name.equals(name, ignoreCase = true) }
        val base = existing ?: FoodEntity(
            name = name,
            kcal = 0.0,
            protein = 0.0,
            carbs = 0.0,
            fat = 0.0,
            source = FoodSource.AI,
        )
        val food = base.copy(
            name = name,
            kcal = ai.kcalPer100g,
            protein = ai.proteinPer100g,
            carbs = ai.carbsPer100g,
            fat = ai.fatPer100g,
            unit = "g",
            portions = listOf(FoodPortion(PHOTO_PORTION_NAME, (amount * 10).roundToLong() / 10.0)),
            source = FoodSource.AI,
        )
        val id = foodRepository.saveCustomFood(food)
        return food.copy(id = id)
    }

    private fun formatAmount(grams: Double): String = when {
        grams <= 0.0 -> ""
        grams < 10.0 -> formatInput(grams, maxDecimals = 1)
        else -> formatInput(grams, maxDecimals = 0)
    }

    private companion object {
        const val DEFAULT_ERROR = "Die Analyse ist fehlgeschlagen. Bitte versuche es erneut."
        const val PHOTO_PORTION_NAME = "Portion vom Foto"
        const val SEARCH_LIMIT = 40
    }
}
