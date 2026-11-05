package de.kalorienkompass.ui.diary

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen
import de.kalorienkompass.data.model.MealType
import java.time.LocalDate

/** STUB (owned by DIARY). TODO(DIARY): implement (see spec §11). */
@Composable
fun MealDetailScreen(navController: NavController, date: LocalDate, meal: MealType) {
    PlaceholderScreen(title = "Mahlzeit", onBack = { navController.popBackStack() })
}
