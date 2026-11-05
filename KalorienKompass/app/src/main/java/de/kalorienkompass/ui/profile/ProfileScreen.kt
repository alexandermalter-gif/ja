package de.kalorienkompass.ui.profile

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.BuildConfig
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.navigation.appViewModelFactory
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.formatSteps
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavController) {
    val vm: ProfileViewModel = viewModel(
        factory = appViewModelFactory {
            ProfileViewModel(it.settingsRepository, it.targetsUseCase, it.measurementRepository)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val open: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Profil", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { open(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            LoadingBox(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            ProfileContent(
                state = current,
                padding = padding,
                onOpen = open,
            )
        }
    }
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderCard(state = state, onEdit = { onOpen(Routes.GOALS) })
        GoalsCard(state = state, onClick = { onOpen(Routes.GOALS) })
        MenuCard(state = state, onOpen = onOpen)
        Text(
            text = "KalorienKompass ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun HeaderCard(state: ProfileUiState, onEdit: () -> Unit) {
    val profile = state.profile
    val name = profile.name.trim()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (name.isNotEmpty()) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.ifEmpty { "Mein Profil" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.age} Jahre · ${formatGrams(profile.heightCm)} cm · ${profile.sex.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Profil bearbeiten",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        val bmi = state.bmi
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Gewicht",
                value = "${formatDecimal(state.currentWeightKg)} kg",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "BMI",
                value = if (bmi > 0.0) formatDecimal(bmi) else "–",
                valueColor = bmiColor(bmi),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Zielgewicht",
                value = "${formatDecimal(profile.targetWeightKg)} kg",
                modifier = Modifier.weight(1f),
            )
        }

        if (bmi > 0.0) {
            Spacer(Modifier.height(14.dp))
            BmiScale(bmi = bmi)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "BMI ${formatDecimal(bmi)}: ${GoalCalculator.bmiCategory(bmi)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = bmiColor(bmi),
            )
        }
        if (!state.hasWeightEntry) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Noch kein Gewicht eingetragen – es wird dein Startgewicht verwendet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Colored BMI scale (15–40) with a marker at the current value. */
@Composable
private fun BmiScale(bmi: Double, modifier: Modifier = Modifier) {
    val markerOuter = MaterialTheme.colorScheme.onSurface
    val markerInner = bmiColor(bmi)
    val segments = listOf(
        18.5 to AppColors.protein,
        25.0 to AppColors.kcal,
        30.0 to AppColors.carbs,
        40.0 to AppColors.over,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        val min = 15.0
        val max = 40.0
        val barHeight = 8.dp.toPx()
        val gap = 3.dp.toPx()
        val y = (size.height - barHeight) / 2f
        fun xOf(v: Double): Float = (((v.coerceIn(min, max) - min) / (max - min)) * size.width).toFloat()
        var start = min
        segments.forEach { (end, color) ->
            val x0 = xOf(start)
            val x1 = xOf(end)
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(x0 + gap / 2f, y),
                size = Size((x1 - x0 - gap).coerceAtLeast(0f), barHeight),
                cornerRadius = CornerRadius(barHeight / 2f),
            )
            start = end
        }
        val pos = xOf(bmi).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
        val center = Offset(pos, size.height / 2f)
        drawCircle(color = markerOuter, radius = 7.dp.toPx(), center = center)
        drawCircle(color = markerInner, radius = 4.5.dp.toPx(), center = center)
    }
}

@Composable
private fun GoalsCard(state: ProfileUiState, onClick: () -> Unit) {
    val profile = state.profile
    val targets = state.targets
    AppCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Ziele",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Bearbeiten",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatKcal(targets.calories.toDouble()),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "kcal pro Tag",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = if (state.usesCustomGoal) "Eigenes Kalorienziel" else "Berechnet aus Profil, Aktivität und Ziel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Kohlenhydrate",
                value = "${targets.carbsG} g",
                accent = AppColors.carbs,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Eiweiß",
                value = "${targets.proteinG} g",
                accent = AppColors.protein,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Fett",
                value = "${targets.fatG} g",
                accent = AppColors.fat,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = profile.goalType.icon(), size = 36)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.goalType.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (profile.goalType == GoalType.MAINTAIN) {
                        "Zielgewicht ${formatDecimal(profile.targetWeightKg)} kg"
                    } else {
                        "${formatGrams(profile.weeklyRateKg)} kg pro Woche · Ziel ${formatDecimal(profile.targetWeightKg)} kg"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        WeightProgress(state = state)
    }
}

@Composable
private fun WeightProgress(state: ProfileUiState) {
    val profile = state.profile
    val start = profile.startWeightKg
    val current = state.currentWeightKg
    val target = profile.targetWeightKg
    when (profile.goalType) {
        GoalType.MAINTAIN -> {
            val diff = current - target
            Text(
                text = if (abs(diff) < 0.05) {
                    "Du bist genau auf deinem Zielgewicht."
                } else {
                    "Abweichung vom Zielgewicht: ${if (diff > 0) "+" else "−"}${formatDecimal(abs(diff))} kg"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        GoalType.LOSE, GoalType.GAIN -> {
            val losing = profile.goalType == GoalType.LOSE
            val total = if (losing) start - target else target - start
            val done = if (losing) start - current else current - start
            val reached = if (losing) current <= target else current >= target
            val fraction = when {
                reached -> 1f
                total <= 0.0 -> 0f
                else -> (done / total).toFloat().coerceIn(0f, 1f)
            }
            if (reached) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Zielgewicht erreicht – stark!",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    text = "Noch ${formatDecimal(abs(current - target))} kg bis zum Ziel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            ThinProgressBar(fraction = fraction)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    text = "Start ${formatDecimal(start)} kg",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Ziel ${formatDecimal(target)} kg",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MenuCard(state: ProfileUiState, onOpen: (String) -> Unit) {
    val settings = state.settings
    val stepGoal = "Schrittziel ${formatSteps(settings.stepGoal.toLong())}"
    val healthSubtitle = when {
        settings.healthConnectEnabled -> "Health Connect verbunden · $stepGoal"
        settings.stepSensorEnabled -> "Schrittsensor aktiv · $stepGoal"
        else -> "Schritte & Gewicht z. B. von Honor Health übernehmen"
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = "Health Connect & Schritte",
            subtitle = healthSubtitle,
            onClick = { onOpen(Routes.HEALTH_CONNECT) },
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 58.dp),
        )
        NavRow(
            icon = Icons.Filled.Settings,
            title = "Einstellungen",
            subtitle = "Darstellung, KI-Fotoerkennung, Backup & Export",
            onClick = { onOpen(Routes.SETTINGS) },
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 58.dp),
        )
        NavRow(
            icon = Icons.Filled.PrivacyTip,
            title = "Datenschutz",
            subtitle = "Deine Daten bleiben auf deinem Gerät",
            onClick = { onOpen(Routes.PRIVACY) },
        )
    }
}

