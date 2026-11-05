package de.kalorienkompass.ui.profile

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.PlaceholderScreen

/** STUB (owned by PROFILE). TODO(PROFILE): implement (see spec §11). */
@Composable
fun SettingsScreen(navController: NavController) {
    PlaceholderScreen(title = "Einstellungen", onBack = { navController.popBackStack() })
}
