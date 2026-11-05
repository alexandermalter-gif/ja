package de.kalorienkompass.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(navController: NavController) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Datenschutz") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PrivacyPolicyContent(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        )
    }
}

private enum class SectionTint { PRIMARY, SECONDARY, TERTIARY, BURNED, ERROR }

/** One topic of the privacy policy. */
private data class PrivacySection(
    val icon: ImageVector,
    val title: String,
    val text: String,
    val tint: SectionTint,
)

private val HEALTH_CONNECT_SECTION = PrivacySection(
    icon = Icons.Filled.MonitorHeart,
    title = "Health Connect",
    text = "Nur wenn du es aktivierst, liest KalorienKompass aus Health Connect deine Schritte, aktiven " +
        "Kalorien, dein Gewicht und deinen Körperfettanteil – um dein Kalorienbudget zu berechnen und deinen " +
        "Fortschritt anzuzeigen. Auf Wunsch schreibt die App deine selbst eingetragenen Gewichtswerte in " +
        "Health Connect. Diese Daten werden ausschließlich auf deinem Gerät verarbeitet, nicht an Dritte " +
        "weitergegeben und nicht für Werbung genutzt – auch nicht an Anthropic oder Open Food Facts gesendet. " +
        "Die Berechtigungen kannst du jederzeit in den Health-Connect-Einstellungen widerrufen.",
    tint = SectionTint.PRIMARY,
)

private val OTHER_SECTIONS = listOf(
    PrivacySection(
        icon = Icons.Filled.Lock,
        title = "Alles bleibt auf deinem Gerät",
        text = "Tagebuch, Lebensmittel, Rezepte, Körperwerte, Schritte und Einstellungen werden ausschließlich " +
            "lokal auf deinem Gerät gespeichert. Es gibt kein Benutzerkonto, keine Cloud, keine Werbung und " +
            "keine Analyse- oder Tracking-Dienste.",
        tint = SectionTint.PRIMARY,
    ),
    PrivacySection(
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
        title = "Schrittsensor",
        text = "Wenn du den Schrittsensor aktivierst, liest die App mit der Berechtigung „Körperliche " +
            "Aktivitäten“ den Schrittzähler deines Handys. Die Werte werden nur lokal gespeichert.",
        tint = SectionTint.BURNED,
    ),
    PrivacySection(
        icon = Icons.Filled.AutoAwesome,
        title = "KI-Funktionen (optional)",
        text = "Nur wenn du eine KI-Funktion mit deinem eigenen API-Schlüssel nutzt, werden die dafür nötigen " +
            "Daten an Anthropic (Claude) übertragen: das Foto deiner Mahlzeit oder einer Nährwerttabelle, dein " +
            "optionaler Hinweistext bzw. bei der KI-Websuche der Barcode und Produktname. Gesundheitsdaten wie " +
            "Gewicht oder Schritte werden dabei nicht übertragen. Dein API-Schlüssel wird nur auf deinem Gerät " +
            "gespeichert und ist in keinem Backup enthalten. Es gelten die Datenschutzbestimmungen von Anthropic.",
        tint = SectionTint.TERTIARY,
    ),
    PrivacySection(
        icon = Icons.Filled.Search,
        title = "Open Food Facts",
        text = "Bei der Online-Suche und beim Scannen eines Barcodes wird der Suchbegriff bzw. der Barcode an " +
            "die offene Lebensmitteldatenbank Open Food Facts (openfoodfacts.org) gesendet. Persönliche Daten " +
            "werden dabei nicht übertragen.",
        tint = SectionTint.SECONDARY,
    ),
    PrivacySection(
        icon = Icons.Filled.PhotoCamera,
        title = "Kamera & Fotos",
        text = "Die Kamera wird nur für den Barcode-Scan und für Fotos verwendet, die du selbst aufnimmst. " +
            "Fotos für die KI-Erkennung werden nur vorübergehend im App-Cache abgelegt.",
        tint = SectionTint.TERTIARY,
    ),
    PrivacySection(
        icon = Icons.Filled.Backup,
        title = "Backups & Export",
        text = "Backups und CSV-Exporte erstellst du selbst; sie liegen dort, wo du sie speicherst. Die " +
            "automatische Android-Sicherung kann die App-Daten – ohne API-Schlüssel – in deinem Google-Konto " +
            "sichern, sofern du sie in den Android-Einstellungen aktiviert hast.",
        tint = SectionTint.SECONDARY,
    ),
    PrivacySection(
        icon = Icons.Filled.DeleteForever,
        title = "Daten löschen",
        text = "Wenn du die App deinstallierst oder in den Android-Einstellungen ihre Daten löschst, werden alle " +
            "lokal gespeicherten Daten entfernt. Daten in Health Connect verwaltest und löschst du direkt in " +
            "Health Connect.",
        tint = SectionTint.ERROR,
    ),
)

/**
 * The privacy policy (German). Used by [PrivacyScreen] and by the Health Connect rationale activity
 * ([de.kalorienkompass.health.PrivacyPolicyActivity]); [healthConnectFirst] puts the Health Connect
 * section at the top when Health Connect opened the policy.
 */
@Composable
fun PrivacyPolicyContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    healthConnectFirst: Boolean = false,
) {
    val sections = if (healthConnectFirst) {
        listOf(HEALTH_CONNECT_SECTION) + OTHER_SECTIONS
    } else {
        listOf(OTHER_SECTIONS.first(), HEALTH_CONNECT_SECTION) + OTHER_SECTIONS.drop(1)
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            PrivacyIntro()
        }
        items(sections, key = { it.title }) { section ->
            PrivacySectionCard(section)
        }
        item(key = "footer") {
            Text(
                text = "KalorienKompass ist eine private App ohne eigene Server.\nStand: November 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PrivacyIntro() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Kurz gesagt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Deine Daten bleiben auf deinem Gerät. Nur für Online-Suche, Barcode-Abfrage und die " +
                    "optionalen KI-Funktionen werden die dafür nötigen Angaben übertragen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PrivacySectionCard(section: PrivacySection) {
    val tint = when (section.tint) {
        SectionTint.PRIMARY -> MaterialTheme.colorScheme.primary
        SectionTint.SECONDARY -> MaterialTheme.colorScheme.secondary
        SectionTint.TERTIARY -> MaterialTheme.colorScheme.tertiary
        SectionTint.BURNED -> AppColors.burned
        SectionTint.ERROR -> MaterialTheme.colorScheme.error
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(section.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = section.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
