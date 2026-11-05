package de.kalorienkompass.ui.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.recipes.RecipeListContent
import de.kalorienkompass.util.dayLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/** A typed query that looks like an EAN/UPC barcode. */
private val BARCODE_LIKE = Regex("""^\d{8,14}$""")

/** Tabs shown while the search field is empty. */
private enum class SearchTab(val label: String) {
    RECENT("Zuletzt"),
    FAVORITES("Favoriten"),
    CUSTOM("Eigene"),
    RECIPES("Rezepte"),
}

/**
 * Food search: local database (built-in, own and cached foods) + Open Food Facts, plus shortcuts
 * (barcode, photo AI, quick entry, own food). In [pickMode] it selects a recipe ingredient.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSearchScreen(navController: NavController, date: LocalDate?, meal: MealType?, pickMode: Boolean) {
    val vm: FoodSearchViewModel = viewModel(
        key = "food_search_${date?.toEpochDay()}_${meal?.name}_$pickMode",
        factory = foodViewModelFactory { container, handle ->
            FoodSearchViewModel(container.foodRepository, container.diaryRepository, handle)
        },
    )
    val local by vm.local.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val customFoods by vm.customFoods.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Date/meal for quick entries and the photo AI when the search was opened without them.
    val effectiveDate = date ?: LocalDate.now()
    val effectiveMeal = meal ?: MealType.forTimeOfDay()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        vm.messageShown()
    }

    val openFood: (FoodEntity) -> Unit = { food ->
        keyboard?.hide()
        vm.openFood(food) { id -> navController.navigate(Routes.foodDetail(id, date, meal, pickMode)) }
    }
    val createCustom: (String?, String?) -> Unit = { name, barcode ->
        keyboard?.hide()
        navController.navigate(Routes.customFood(barcode = barcode, date = date, meal = meal, pick = pickMode))
        val prefill = name?.trim()?.takeIf { it.isNotEmpty() && barcode == null }
        if (prefill != null) {
            navController.currentBackStackEntry?.savedStateHandle?.set(
                CUSTOM_FOOD_PREFILL_NAME,
                prefill.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.GERMAN) else it.toString() },
            )
        }
    }
    val scanBarcode: () -> Unit = {
        keyboard?.hide()
        navController.navigate(Routes.barcode(date, meal, pickMode))
    }

    val title: String
    val subtitle: String?
    when {
        pickMode -> {
            title = "Zutat hinzufügen"
            subtitle = "Lebensmittel für dein Rezept wählen"
        }
        meal != null -> {
            title = meal.label
            subtitle = dayLabel(effectiveDate)
        }
        else -> {
            title = "Lebensmittel suchen"
            subtitle = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            SearchField(
                query = vm.query,
                onQueryChange = vm::onQueryChange,
                onClear = vm::clearQuery,
                onSearch = {
                    vm.searchNow()
                    keyboard?.hide()
                },
            )
            ActionChipsRow(
                pickMode = pickMode,
                onBarcode = scanBarcode,
                onPhoto = { navController.navigate(Routes.aiPhoto(effectiveDate, effectiveMeal)) },
                onQuickAdd = { showQuickAdd = true },
                onCustom = { createCustom(null, null) },
            )
            if (opening) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (vm.query.isBlank()) {
                EmptyQueryContent(
                    navController = navController,
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                    pickMode = pickMode,
                    date = date,
                    meal = meal,
                    recent = recent,
                    favorites = favorites,
                    customFoods = customFoods,
                    onOpen = openFood,
                    onCreateCustom = { createCustom(null, null) },
                )
            } else {
                SearchResults(
                    query = vm.query.trim(),
                    local = local,
                    remote = remote,
                    openingEnabled = !opening,
                    onOpen = openFood,
                    onRetry = vm::retryRemote,
                    onLoadMore = vm::loadMore,
                    onSearchNow = vm::searchNow,
                    onCreateCustom = createCustom,
                    onScan = scanBarcode,
                    onScrollStarted = { keyboard?.hide() },
                )
            }
        }
    }

    if (showQuickAdd) {
        QuickAddDialog(
            mealLabel = effectiveMeal.label,
            onConfirm = { name, kcal, protein, carbs, fat ->
                vm.addQuick(effectiveDate, effectiveMeal, name, Nutrition(kcal, protein, carbs, fat)) { ok ->
                    if (ok) {
                        navController.popBackStack()
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Der Schnelleintrag konnte nicht gespeichert werden.") }
                    }
                }
            },
            onDismiss = { showQuickAdd = false },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoFocused) {
            autoFocused = true
            delay(150)
            runCatching { focusRequester.requestFocus() }
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Lebensmittel, Marke oder Barcode") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Suche leeren")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun ActionChipsRow(
    pickMode: Boolean,
    onBarcode: () -> Unit,
    onPhoto: () -> Unit,
    onQuickAdd: () -> Unit,
    onCustom: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShortcutChip("Barcode", Icons.Filled.QrCodeScanner, onBarcode)
        if (!pickMode) {
            ShortcutChip("Foto (KI)", Icons.Filled.PhotoCamera, onPhoto)
            ShortcutChip("Schnell", Icons.Filled.Bolt, onQuickAdd)
        }
        ShortcutChip("Eigenes", Icons.Filled.Add, onCustom)
    }
}

@Composable
private fun ShortcutChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        shape = RoundedCornerShape(12.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.primary,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    )
}

// -------------------------------------------------------------------------------------------------
// Empty query: tabs
// -------------------------------------------------------------------------------------------------

@Composable
private fun EmptyQueryContent(
    navController: NavController,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    pickMode: Boolean,
    date: LocalDate?,
    meal: MealType?,
    recent: List<FoodEntity>?,
    favorites: List<FoodEntity>?,
    customFoods: List<FoodEntity>?,
    onOpen: (FoodEntity) -> Unit,
    onCreateCustom: () -> Unit,
) {
    val tabs = remember(pickMode) {
        if (pickMode) listOf(SearchTab.RECENT, SearchTab.FAVORITES, SearchTab.CUSTOM) else SearchTab.entries.toList()
    }
    val selected = tabs[selectedIndex.coerceIn(0, tabs.lastIndex)]

    Column(modifier = Modifier.fillMaxSize()) {
        FoodTabsRow(
            labels = tabs.map { it.label },
            selectedIndex = tabs.indexOf(selected),
            onSelect = onSelect,
        )
        when (selected) {
            SearchTab.RECENT -> FoodList(
                foods = recent,
                onOpen = onOpen,
                empty = {
                    EmptyState(
                        icon = Icons.Filled.History,
                        title = "Noch nichts eingetragen",
                        text = "Lebensmittel, die du einträgst, erscheinen hier – so findest du sie beim nächsten Mal schneller.",
                    )
                },
            )
            SearchTab.FAVORITES -> FoodList(
                foods = favorites,
                onOpen = onOpen,
                empty = {
                    EmptyState(
                        icon = Icons.Filled.StarBorder,
                        title = "Noch keine Favoriten",
                        text = "Tippe in der Detailansicht eines Lebensmittels auf den Stern, um es hier abzulegen.",
                    )
                },
            )
            SearchTab.CUSTOM -> FoodList(
                foods = customFoods,
                onOpen = onOpen,
                empty = {
                    EmptyState(
                        icon = Icons.Filled.EditNote,
                        title = "Keine eigenen Lebensmittel",
                        text = "Lege Produkte an, die in keiner Datenbank stehen – z. B. vom Bäcker oder vom Wochenmarkt.",
                        actionLabel = "Eigenes Lebensmittel anlegen",
                        onAction = onCreateCustom,
                    )
                },
            )
            SearchTab.RECIPES -> RecipeListContent(
                navController = navController,
                date = date,
                meal = meal,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Lazy list of foods with loading (null) and empty state. */
@Composable
internal fun FoodList(
    foods: List<FoodEntity>?,
    onOpen: (FoodEntity) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Int = 24,
    empty: @Composable () -> Unit,
) {
    when {
        foods == null -> LoadingBox(modifier = modifier)
        foods.isEmpty() -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { empty() }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(foods, key = { it.id }) { food ->
                FoodListItem(food = food, onClick = { onOpen(food) })
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Query: local + Open Food Facts results
// -------------------------------------------------------------------------------------------------

@Composable
private fun SearchResults(
    query: String,
    local: LocalSearchState,
    remote: RemoteSearchState,
    openingEnabled: Boolean,
    onOpen: (FoodEntity) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSearchNow: () -> Unit,
    onCreateCustom: (name: String?, barcode: String?) -> Unit,
    onScan: () -> Unit,
    onScrollStarted: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) onScrollStarted()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- local ----
        val localCurrent = local.query == query
        item(key = "local_header") {
            ListSectionLabel(
                text = "Meine Lebensmittel",
                trailing = if (localCurrent && !local.loading && local.results.isNotEmpty()) "${local.results.size} Treffer" else null,
            )
        }
        when {
            local.results.isEmpty() && (local.loading || !localCurrent) -> item(key = "local_loading") {
                InlineProgress("Suche in deinen Lebensmitteln …")
            }
            local.results.isEmpty() -> item(key = "local_empty") {
                HintText("Keine Treffer bei deinen eigenen und den Basis-Lebensmitteln.")
            }
            else -> items(local.results, key = { "local_${it.id}" }) { food ->
                FoodListItem(food = food, onClick = { onOpen(food) }, enabled = openingEnabled)
            }
        }

        // ---- Open Food Facts ----
        item(key = "remote_header") {
            ListSectionLabel(
                text = "Open Food Facts",
                trailing = if (remote.status == RemoteStatus.LOADED && remote.results.isNotEmpty() && !remote.isBarcode) {
                    "${remote.results.size}${if (remote.endReached) "" else "+"} Produkte"
                } else {
                    null
                },
            )
        }
        remoteSection(
            query = query,
            remote = remote,
            openingEnabled = openingEnabled,
            onOpen = onOpen,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            onSearchNow = onSearchNow,
            onCreateCustom = onCreateCustom,
        )

        // ---- not found? ----
        item(key = "not_found") {
            NotFoundCard(
                onCreate = {
                    if (BARCODE_LIKE.matches(query)) onCreateCustom(null, query) else onCreateCustom(query, null)
                },
                onScan = onScan,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun LazyListScope.remoteSection(
    query: String,
    remote: RemoteSearchState,
    openingEnabled: Boolean,
    onOpen: (FoodEntity) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSearchNow: () -> Unit,
    onCreateCustom: (name: String?, barcode: String?) -> Unit,
) {
    when (remote.status) {
        RemoteStatus.IDLE -> item(key = "remote_idle") {
            if (query.length >= 2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HintText(
                        text = "Ab 3 Zeichen wird auch Open Food Facts durchsucht.",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSearchNow) { Text("Jetzt suchen") }
                }
            } else {
                HintText("Ab 3 Zeichen wird auch Open Food Facts durchsucht.")
            }
        }
        RemoteStatus.LOADING -> item(key = "remote_loading") {
            InlineProgress(if (remote.isBarcode) "Barcode wird nachgeschlagen …" else "Suche in Open Food Facts …")
        }
        RemoteStatus.ERROR -> item(key = "remote_error") {
            ErrorCard(message = remote.error ?: "Die Suche ist fehlgeschlagen.", onRetry = onRetry)
        }
        RemoteStatus.LOADED -> {
            if (remote.results.isEmpty()) {
                item(key = "remote_empty") {
                    if (remote.isBarcode) {
                        BarcodeNotFoundCard(
                            barcode = remote.query,
                            onCreate = { onCreateCustom(null, remote.query) },
                        )
                    } else {
                        HintText("Keine Produkte zu „${remote.query}“ gefunden. Versuche einen anderen Begriff oder die Marke.")
                    }
                }
            } else {
                itemsIndexed(remote.results, key = { index, food -> "remote_${index}_${food.barcode}" }) { _, food ->
                    FoodListItem(food = food, onClick = { onOpen(food) }, enabled = openingEnabled)
                }
                item(key = "remote_footer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            remote.loadingMore -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            remote.loadMoreError != null -> ErrorCard(message = remote.loadMoreError, onRetry = onLoadMore)
                            !remote.endReached -> OutlinedButton(onClick = onLoadMore) { Text("Mehr laden") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineProgress(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Erneut versuchen")
        }
    }
}

@Composable
private fun BarcodeNotFoundCard(barcode: String, onCreate: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Produkt nicht gefunden", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "Zum Barcode $barcode gibt es weder bei dir noch bei Open Food Facts einen Eintrag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Mit diesem Barcode anlegen")
        }
    }
}

@Composable
private fun NotFoundCard(onCreate: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Nicht das Richtige dabei?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "Scanne den Barcode oder lege das Produkt selbst an – die Nährwerte stehen auf der Verpackung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scannen", maxLines = 1)
            }
            Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Anlegen", maxLines = 1)
            }
        }
    }
}
