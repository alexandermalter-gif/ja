package de.kalorienkompass.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import de.kalorienkompass.di.appContainer
import de.kalorienkompass.ui.navigation.Routes
import kotlinx.coroutines.launch

/** STUB (owned by PROFILE). TODO(PROFILE): multi-step onboarding (see spec §11 PROFILE). */
@Composable
fun OnboardingScreen(navController: NavController) {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Willkommen bei KalorienKompass", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    container.settingsRepository.updateProfile { it.copy(onboardingDone = true) }
                    navController.navigate(Routes.DIARY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            },
        ) {
            Text("Los geht's")
        }
    }
}
