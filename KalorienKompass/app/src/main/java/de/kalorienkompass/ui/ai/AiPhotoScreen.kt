package de.kalorienkompass.ui.ai

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen
import de.kalorienkompass.data.model.MealType
import java.time.LocalDate

/** STUB (owned by AI). TODO(AI): implement (see spec §11). */
@Composable
fun AiPhotoScreen(navController: NavController, date: LocalDate, meal: MealType) {
    PlaceholderScreen(title = "Foto-Erkennung (KI)", onBack = { navController.popBackStack() })
}
