package de.kalorienkompass.ui.recipes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.navigation.Routes
import java.time.LocalDate

/**
 * STUB (owned by RECIPES): embeddable recipe list + "Neues Rezept" button (used by FoodsHub and FoodSearch).
 * TODO(RECIPES): implement (see spec §11 RECIPES).
 */
@Composable
fun RecipeListContent(
    navController: NavController,
    date: LocalDate?,
    meal: MealType?,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Filled.Restaurant,
        title = "Noch keine Rezepte",
        text = "Lege dein erstes Rezept an.",
        modifier = modifier,
        actionLabel = "Neues Rezept",
        onAction = { navController.navigate(Routes.recipeEdit()) },
    )
}
