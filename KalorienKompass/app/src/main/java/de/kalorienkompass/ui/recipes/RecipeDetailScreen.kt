package de.kalorienkompass.ui.recipes

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen
import de.kalorienkompass.data.model.MealType
import java.time.LocalDate

/** STUB (owned by RECIPES). TODO(RECIPES): implement (see spec §11). */
@Composable
fun RecipeDetailScreen(navController: NavController, recipeId: Long, date: LocalDate?, meal: MealType?) {
    PlaceholderScreen(title = "Rezept", onBack = { navController.popBackStack() })
}
