package de.kalorienkompass.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.util.formatKcal
import java.time.LocalDate

/**
 * Embeddable recipe list with a "Neues Rezept" button (used by FoodsHub and FoodSearch).
 * Tapping a recipe opens its detail page with the given diary context ([date]/[meal], may be null).
 *
 * Scrolls lazily when it gets a bounded height (e.g. `Modifier.fillMaxSize()` / `weight(1f)`);
 * inside an outer vertical scroll container it falls back to a plain column.
 */
@Composable
fun RecipeListContent(
    navController: NavController,
    date: LocalDate?,
    meal: MealType?,
    modifier: Modifier = Modifier,
) {
    val dependencies = LocalRecipeDependencies.current
    val vm: RecipeListViewModel = viewModel(
        key = "recipe_list",
        factory = viewModelFactory {
            initializer { RecipeListViewModel(recipeDependencies(dependencies).recipes) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val onOpen: (RecipeListItem) -> Unit = { item ->
        navController.navigateIfResumed(lifecycleOwner, Routes.recipeDetail(item.id, date, meal))
    }
    val onNew: () -> Unit = {
        navController.navigateIfResumed(lifecycleOwner, Routes.recipeEdit())
    }

    BoxWithConstraints(modifier = modifier) {
        val bounded = constraints.hasBoundedHeight
        when (val s = state) {
            RecipeListUiState.Loading -> LoadingBox()
            RecipeListUiState.Error -> EmptyState(
                icon = Icons.Filled.ErrorOutline,
                title = "Rezepte nicht verfügbar",
                text = "Die Rezepte konnten nicht geladen werden.",
            )
            is RecipeListUiState.Loaded -> if (s.items.isEmpty()) {
                val emptyModifier = if (bounded) Modifier.verticalScroll(rememberScrollState()) else Modifier
                Box(emptyModifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = "Noch keine Rezepte",
                        text = "Lege Gerichte an, die du öfter kochst – die Nährwerte pro Portion " +
                            "werden automatisch aus den Zutaten berechnet.",
                        actionLabel = "Neues Rezept",
                        onAction = onNew,
                    )
                }
            } else if (bounded) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "new_recipe", contentType = "header") {
                        NewRecipeButton(onClick = onNew)
                    }
                    items(s.items, key = { it.id }, contentType = { "recipe" }) { item ->
                        RecipeRow(
                            item = item,
                            onClick = { onOpen(item) },
                            onToggleFavorite = { vm.toggleFavorite(item) },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(ListPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NewRecipeButton(onClick = onNew)
                    s.items.forEach { item ->
                        RecipeRow(
                            item = item,
                            onClick = { onOpen(item) },
                            onToggleFavorite = { vm.toggleFavorite(item) },
                        )
                    }
                }
            }
        }
    }
}

/** Extra bottom space so the last row is not hidden behind a floating action button. */
private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)

@Composable
private fun NewRecipeButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Neues Rezept")
    }
}

@Composable
private fun RecipeRow(
    item: RecipeListItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.RestaurantMenu,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${portionsLabel(item.portions)} · ${ingredientsLabel(item.ingredientCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.missingCount > 0) {
                    Text(
                        text = if (item.missingCount == 1) {
                            "1 Zutat fehlt"
                        } else {
                            "${item.missingCount} Zutaten fehlen"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${formatKcal(item.kcalPerPortion)} kcal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "pro Portion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (item.isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                    tint = if (item.isFavorite) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
