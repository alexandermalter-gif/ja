package de.kalorienkompass.ui.food

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen
import de.kalorienkompass.data.model.MealType
import java.time.LocalDate

/** STUB (owned by FOOD). TODO(FOOD): implement (see spec §11). */
@Composable
fun CustomFoodEditScreen(navController: NavController, foodId: Long?, barcode: String?, date: LocalDate?, meal: MealType?, pickMode: Boolean) {
    PlaceholderScreen(title = "Eigenes Lebensmittel", onBack = { navController.popBackStack() })
}
