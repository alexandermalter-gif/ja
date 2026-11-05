package de.kalorienkompass.ui.food

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.remote.off.OffApiException
import de.kalorienkompass.data.repo.BarcodeLookupResult
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Local search results ("Meine Lebensmittel"). */
internal data class LocalSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<FoodEntity> = emptyList(),
)

internal enum class RemoteStatus { IDLE, LOADING, LOADED, ERROR }

/** Open Food Facts results (text search or barcode lookup for an all-digit query). */
internal data class RemoteSearchState(
    val query: String = "",
    val status: RemoteStatus = RemoteStatus.IDLE,
    val results: List<FoodEntity> = emptyList(),
    val page: Int = 0,
    val endReached: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    /** true if [query] was looked up as a barcode */
    val isBarcode: Boolean = false,
)

@OptIn(FlowPreview::class)
internal class FoodSearchViewModel(
    private val foodRepository: FoodRepository,
    private val diaryRepository: DiaryRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Text field content (Compose state, so typing is never delayed by a flow). */
    var query: String by mutableStateOf(savedState.get<String>(KEY_QUERY).orEmpty())
        private set

    val recent: StateFlow<List<FoodEntity>?> = foodRepository.observeRecent(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favorites: StateFlow<List<FoodEntity>?> = foodRepository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val customFoods: StateFlow<List<FoodEntity>?> = foodRepository.observeCustomFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _local = MutableStateFlow(LocalSearchState())
    val local: StateFlow<LocalSearchState> = _local.asStateFlow()

    private val _remote = MutableStateFlow(RemoteSearchState())
    val remote: StateFlow<RemoteSearchState> = _remote.asStateFlow()

    /** true while a tapped Open Food Facts product is being stored locally. */
    private val _opening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = _opening.asStateFlow()

    /** One-shot message for the snackbar (German). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var remoteJob: Job? = null

    init {
        val trimmedQuery = snapshotFlow { query.trim() }.distinctUntilChanged()

        // Local database: 400 ms debounce.
        viewModelScope.launch {
            trimmedQuery
                .debounce { if (it.isEmpty()) 0L else LOCAL_DEBOUNCE_MS }
                .collectLatest { q -> runLocalSearch(q) }
        }

        // Open Food Facts: 800 ms debounce and at least 3 characters.
        viewModelScope.launch {
            trimmedQuery
                .map { q -> q.takeIf { it.length >= MIN_REMOTE_CHARS } }
                .distinctUntilChanged()
                .debounce { if (it == null) 0L else REMOTE_DEBOUNCE_MS }
                .collectLatest { q ->
                    if (q == null) {
                        remoteJob?.cancel()
                        _remote.value = RemoteSearchState()
                    } else if (q != _remote.value.query || _remote.value.status == RemoteStatus.IDLE) {
                        startRemoteSearch(q)
                    }
                }
        }
    }

    fun onQueryChange(text: String) {
        query = text.take(MAX_QUERY_LENGTH)
        savedState[KEY_QUERY] = query
    }

    fun clearQuery() = onQueryChange("")

    /** IME search action: searches Open Food Facts immediately (from 2 characters). */
    fun searchNow() {
        val q = query.trim()
        if (q.length < 2) return
        val current = _remote.value
        if (q != current.query || current.status == RemoteStatus.ERROR || current.status == RemoteStatus.IDLE) {
            startRemoteSearch(q)
        }
    }

    fun retryRemote() {
        val q = query.trim().ifEmpty { _remote.value.query }
        if (q.isNotEmpty()) startRemoteSearch(q)
    }

    fun loadMore() {
        val current = _remote.value
        if (current.isBarcode || current.status != RemoteStatus.LOADED || current.endReached || current.loadingMore) return
        val q = current.query
        val nextPage = current.page + 1
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            _remote.update { it.copy(loadingMore = true, loadMoreError = null) }
            try {
                val more = foodRepository.searchRemote(q, nextPage)
                _remote.update { state ->
                    if (state.query != q) return@update state
                    val known = state.results.mapNotNullTo(HashSet()) { it.barcode }
                    val fresh = more.filter { it.barcode == null || it.barcode !in known }
                    state.copy(
                        results = state.results + fresh,
                        page = nextPage,
                        endReached = more.size < MIN_FULL_PAGE,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _remote.update { it.copy(loadingMore = false, loadMoreError = remoteErrorMessage(e)) }
            }
        }
    }

    private suspend fun runLocalSearch(q: String) {
        if (q.isEmpty()) {
            _local.value = LocalSearchState()
            return
        }
        _local.update { it.copy(loading = true) }
        val results = try {
            foodRepository.searchLocal(q, LOCAL_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        _local.value = LocalSearchState(query = q, loading = false, results = results)
    }

    private fun startRemoteSearch(q: String) {
        remoteJob?.cancel()
        val isBarcode = BARCODE_QUERY.matches(q)
        _remote.value = RemoteSearchState(query = q, status = RemoteStatus.LOADING, isBarcode = isBarcode)
        remoteJob = viewModelScope.launch {
            try {
                if (isBarcode) {
                    when (val result = foodRepository.lookupBarcode(q)) {
                        is BarcodeLookupResult.Found -> _remote.value = RemoteSearchState(
                            query = q,
                            status = RemoteStatus.LOADED,
                            results = listOf(result.food),
                            page = 1,
                            endReached = true,
                            isBarcode = true,
                        )
                        BarcodeLookupResult.NotFound -> _remote.value = RemoteSearchState(
                            query = q,
                            status = RemoteStatus.LOADED,
                            page = 1,
                            endReached = true,
                            isBarcode = true,
                        )
                        is BarcodeLookupResult.Error -> _remote.value = RemoteSearchState(
                            query = q,
                            status = RemoteStatus.ERROR,
                            error = result.message,
                            isBarcode = true,
                        )
                    }
                } else {
                    val results = foodRepository.searchRemote(q, 1)
                    _remote.value = RemoteSearchState(
                        query = q,
                        status = RemoteStatus.LOADED,
                        results = results,
                        page = 1,
                        endReached = results.size < MIN_FULL_PAGE,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _remote.value = RemoteSearchState(
                    query = q,
                    status = RemoteStatus.ERROR,
                    error = remoteErrorMessage(e),
                    isBarcode = isBarcode,
                )
            }
        }
    }

    /**
     * Opens a search result: Open Food Facts products (id = 0) are stored locally first.
     * [onReady] receives the local id.
     */
    fun openFood(food: FoodEntity, onReady: (Long) -> Unit) {
        if (food.id != 0L) {
            onReady(food.id)
            return
        }
        if (_opening.value) return
        _opening.value = true
        viewModelScope.launch {
            try {
                val id = foodRepository.cacheRemoteFood(food)
                onReady(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Das Produkt konnte nicht gespeichert werden."
            } finally {
                _opening.value = false
            }
        }
    }

    /** Quick entry (kcal + optional macros) for [date]/[meal]. */
    fun addQuick(
        date: LocalDate,
        meal: MealType,
        name: String,
        nutrition: Nutrition,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = try {
                diaryRepository.addQuick(date, meal, name, nutrition)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            onDone(ok)
        }
    }

    private fun remoteErrorMessage(e: Exception): String = when (e) {
        is OffApiException -> e.message ?: "Open Food Facts ist gerade nicht verfügbar."
        is InterruptedIOException -> "Open Food Facts antwortet gerade nicht (Zeitüberschreitung). Bitte versuche es erneut."
        is UnknownHostException, is ConnectException ->
            "Keine Internetverbindung. Deine eigenen und die Basis-Lebensmittel findest du trotzdem oben."
        is IOException -> "Keine Verbindung zu Open Food Facts. Bitte prüfe deine Internetverbindung."
        else -> "Die Suche bei Open Food Facts ist fehlgeschlagen."
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        private const val KEY_QUERY = "food_search_query"
        private const val LOCAL_DEBOUNCE_MS = 400L
        private const val REMOTE_DEBOUNCE_MS = 800L
        private const val MIN_REMOTE_CHARS = 3
        private const val LOCAL_LIMIT = 50
        private const val MAX_QUERY_LENGTH = 100

        /** A page with fewer usable products than this is treated as the last page. */
        private const val MIN_FULL_PAGE = 10

        /** EAN-8, UPC-A, EAN-13, ITF-14 typed into the search field. */
        private val BARCODE_QUERY = Regex("""^\d{8,14}$""")
    }
}
