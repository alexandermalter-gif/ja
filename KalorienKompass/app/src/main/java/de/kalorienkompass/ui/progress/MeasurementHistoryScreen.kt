package de.kalorienkompass.ui.progress

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen
import de.kalorienkompass.data.model.MeasurementType

/** STUB (owned by PROGRESS). TODO(PROGRESS): implement (see spec §11). */
@Composable
fun MeasurementHistoryScreen(navController: NavController, type: MeasurementType) {
    PlaceholderScreen(title = "Verlauf", onBack = { navController.popBackStack() })
}
