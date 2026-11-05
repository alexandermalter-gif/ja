package de.kalorienkompass.ui.scan

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen
import de.kalorienkompass.data.model.MealType
import java.time.LocalDate

/** STUB (owned by SCAN). TODO(SCAN): implement (see spec §11). */
@Composable
fun BarcodeScanScreen(navController: NavController, date: LocalDate?, meal: MealType?, pickMode: Boolean) {
    PlaceholderScreen(title = "Barcode scannen", onBack = { navController.popBackStack() })
}
