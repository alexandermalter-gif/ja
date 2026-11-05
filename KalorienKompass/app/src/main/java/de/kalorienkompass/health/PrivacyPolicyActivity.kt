package de.kalorienkompass.health

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.di.appContainer
import de.kalorienkompass.ui.health.PrivacyPolicyContent
import de.kalorienkompass.ui.theme.KalorienTheme
import de.kalorienkompass.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Privacy policy shown by Health Connect: as permission rationale (Android 13 and lower,
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`) and as permission usage page (Android 14+,
 * activity-alias `ViewPermissionUsageActivity`).
 */
class PrivacyPolicyActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val openedByHealthConnect = action == ACTION_SHOW_PERMISSIONS_RATIONALE || action == ACTION_VIEW_PERMISSION_USAGE
        val settingsFlow: Flow<AppSettings?> = try {
            appContainer.settingsRepository.settings
        } catch (e: Exception) {
            flowOf(null)
        }

        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val themeMode = settings?.themeMode ?: ThemeMode.DARK
            val dark = isAppInDarkTheme(themeMode)

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
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text("Datenschutz") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Schließen")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                            ),
                        )
                    },
                ) { padding ->
                    PrivacyPolicyContent(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding,
                        healthConnectFirst = openedByHealthConnect,
                    )
                }
            }
        }
    }

    private companion object {
        const val ACTION_SHOW_PERMISSIONS_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        const val ACTION_VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"
    }
}
