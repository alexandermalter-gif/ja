package de.kalorienkompass.ui.health

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen

/** STUB (owned by HEALTH). TODO(HEALTH): implement (see spec §11). */
@Composable
fun HealthConnectScreen(navController: NavController) {
    PlaceholderScreen(title = "Health Connect & Schritte", onBack = { navController.popBackStack() })
}
