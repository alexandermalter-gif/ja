package de.kalorienkompass.ui.recipes

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen

/** STUB (owned by RECIPES). TODO(RECIPES): implement (see spec §11). */
@Composable
fun RecipeEditScreen(navController: NavController, recipeId: Long?) {
    PlaceholderScreen(title = "Rezept bearbeiten", onBack = { navController.popBackStack() })
}
