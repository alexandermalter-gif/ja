package de.kalorienkompass

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.di.appContainer
import de.kalorienkompass.ui.navigation.AppRoot
import de.kalorienkompass.ui.theme.KalorienTheme
import de.kalorienkompass.ui.theme.isAppInDarkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val settingsFlow = appContainer.settingsRepository.settings

        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
            val themeMode = settings?.themeMode ?: ThemeMode.DARK
            val dark = isAppInDarkTheme(themeMode)

            // Light/dark status and navigation bar icons that follow the in-app theme.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            KalorienTheme(themeMode = themeMode) {
                AppRoot()
            }
        }
    }
}
