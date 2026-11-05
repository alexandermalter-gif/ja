package de.kalorienkompass.ui.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.recipes.RecipeListContent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

internal class FoodsHubViewModel(foodRepository: FoodRepository) : ViewModel() {
    /** null while loading */
    val favorites: StateFlow<List<FoodEntity>?> = foodRepository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val customFoods: StateFlow<List<FoodEntity>?> = foodRepository.observeCustomFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recent: StateFlow<List<FoodEntity>?> = foodRepository.observeRecent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private enum class HubTab(val label: String) {
    FAVORITES("Favoriten"),
    CUSTOM("Eigene"),
    RECIPES("Rezepte"),
    RECENT("Zuletzt"),
}

/** Bottom tab "Lebensmittel": favorites, own foods, recipes and recently used foods. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodsHubScreen(navController: NavController) {
    val vm: FoodsHubViewModel = viewModel(factory = appViewModelFactory { FoodsHubViewModel(it.foodRepository) })
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val customFoods by vm.customFoods.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = HubTab.entries[selectedIndex.coerceIn(0, HubTab.entries.lastIndex)]
    var showNewMenu by remember { mutableStateOf(false) }

    val openSearch = { navController.navigate(Routes.foodSearch(LocalDate.now(), MealType.forTimeOfDay())) }
    val newFood = { navController.navigate(Routes.customFood()) }
    val newRecipe = { navController.navigate(Routes.recipeEdit()) }
    val openFood: (FoodEntity) -> Unit = { navController.navigate(Routes.foodDetail(it.id, null, null)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Lebensmittel") },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.barcode(null, null)) }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Barcode scannen")
                    }
                    IconButton(onClick = openSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Lebensmittel suchen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = {
                        when (selected) {
                            HubTab.CUSTOM -> newFood()
                            HubTab.RECIPES -> newRecipe()
                            else -> showNewMenu = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Neu") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                DropdownMenu(
                    expanded = showNewMenu,
                    onDismissRequest = { showNewMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Eigenes Lebensmittel") },
                        leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                        onClick = {
                            showNewMenu = false
                            newFood()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rezept") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                        onClick = {
                            showNewMenu = false
                            newRecipe()
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            SearchLauncher(
                onClick = openSearch,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
            FoodTabsRow(
                labels = HubTab.entries.map { it.label },
                selectedIndex = selected.ordinal,
                onSelect = { selectedIndex = it },
            )
            when (selected) {
                HubTab.FAVORITES -> FoodList(
                    foods = favorites,
                    onOpen = openFood,
                    bottomPadding = FAB_CLEARANCE,
                    empty = {
                        EmptyState(
                            icon = Icons.Filled.StarBorder,
                            title = "Noch keine Favoriten",
                            text = "Markiere Lebensmittel in der Detailansicht mit dem Stern – dann findest du sie hier sofort.",
                            actionLabel = "Lebensmittel suchen",
                            onAction = openSearch,
                        )
                    },
                )
                HubTab.CUSTOM -> FoodList(
                    foods = customFoods,
                    onOpen = openFood,
                    bottomPadding = FAB_CLEARANCE,
                    empty = {
                        EmptyState(
                            icon = Icons.Filled.EditNote,
                            title = "Keine eigenen Lebensmittel",
                            text = "Ein Produkt ist in keiner Datenbank? Lege es mit den Werten von der Verpackung selbst an.",
                            actionLabel = "Eigenes Lebensmittel anlegen",
                            onAction = newFood,
                        )
                    },
                )
                HubTab.RECIPES -> RecipeListContent(
                    navController = navController,
                    date = null,
                    meal = null,
                    modifier = Modifier.fillMaxSize(),
                )
                HubTab.RECENT -> FoodList(
                    foods = recent,
                    onOpen = openFood,
                    bottomPadding = FAB_CLEARANCE,
                    empty = {
                        EmptyState(
                            icon = Icons.Filled.History,
                            title = "Noch nichts eingetragen",
                            text = "Hier erscheinen die Lebensmittel, die du zuletzt verwendet hast.",
                            actionLabel = "Lebensmittel suchen",
                            onAction = openSearch,
                        )
                    },
                )
            }
        }
    }
}

/** Space below the last list item so that the FAB does not cover it. */
private const val FAB_CLEARANCE = 96

/** Looks like a search field; opens the real search screen. */
@Composable
private fun SearchLauncher(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Lebensmittel, Marke oder Barcode suchen",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
