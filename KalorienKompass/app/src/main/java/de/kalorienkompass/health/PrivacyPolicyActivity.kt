package de.kalorienkompass.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.ui.theme.KalorienTheme

/**
 * STUB (owned by HEALTH): shown by Health Connect as permission rationale / privacy policy.
 * TODO(HEALTH): full German privacy text.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KalorienTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("Datenschutz", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "KalorienKompass speichert alle Daten ausschließlich auf deinem Gerät.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
