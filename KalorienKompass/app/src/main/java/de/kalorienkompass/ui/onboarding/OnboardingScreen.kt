package de.kalorienkompass.ui.onboarding

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.backup.BackupManager
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.navigation.appViewModelFactory
import de.kalorienkompass.ui.profile.ActivityLevelChoice
import de.kalorienkompass.ui.profile.BackupImportConfirmDialog
import de.kalorienkompass.ui.profile.DecimalInputField
import de.kalorienkompass.ui.profile.GoalTypeChoice
import de.kalorienkompass.ui.profile.IconBadge
import de.kalorienkompass.ui.profile.InfoBox
import de.kalorienkompass.ui.profile.IntegerTextField
import de.kalorienkompass.ui.profile.ProfileRules
import de.kalorienkompass.ui.profile.SexChoice
import de.kalorienkompass.ui.profile.StatTile
import de.kalorienkompass.ui.profile.ThinProgressBar
import de.kalorienkompass.ui.profile.WeeklyRateChoice
import de.kalorienkompass.ui.profile.bmiColor
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatKcal
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun OnboardingScreen(navController: NavController) {
    val vm: OnboardingViewModel = viewModel(
        factory = appViewModelFactory {
            OnboardingViewModel(it.settingsRepository, it.measurementRepository, it.backupManager)
        },
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onBackupPicked(uri)
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                OnboardingEvent.Finished -> navController.navigate(Routes.DIARY) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
                is OnboardingEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    val step = vm.step
    val draft = vm.draft
    BackHandler(enabled = !step.isFirst) {
        focusManager.clearFocus()
        vm.back()
    }

    val goNext: () -> Unit = {
        focusManager.clearFocus()
        if (step.isLast) vm.finish() else vm.next()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .safeDrawingPadding(),
        ) {
            ProgressHeader(
                step = step,
                onBack = {
                    focusManager.clearFocus()
                    vm.back()
                },
            )
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val enter = slideInHorizontally(tween(320)) { w -> if (forward) w / 3 else -w / 3 } +
                        fadeIn(tween(320))
                    val exit = slideOutHorizontally(tween(260)) { w -> if (forward) -w / 3 else w / 3 } +
                        fadeOut(tween(200))
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "onboardingStep",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    when (page) {
                        OnboardingStep.WELCOME -> WelcomePage(
                            name = draft.name,
                            onName = { v -> vm.update { it.copy(name = v) } },
                            restoring = vm.restoring,
                            onRestore = {
                                try {
                                    restoreLauncher.launch(BackupManager.IMPORT_MIME_TYPES)
                                } catch (e: ActivityNotFoundException) {
                                    vm.showMessage("Auf diesem Gerät ist keine Dateiauswahl verfügbar.")
                                }
                            },
                        )
                        OnboardingStep.ABOUT -> AboutPage(draft = draft, onChange = vm::update)
                        OnboardingStep.BODY -> BodyPage(draft = draft, onChange = vm::update, onDone = goNext)
                        OnboardingStep.ACTIVITY -> ActivityPage(draft = draft, onChange = vm::update)
                        OnboardingStep.GOAL -> GoalPage(draft = draft, onChange = vm::update, onSelectGoal = vm::selectGoal)
                        OnboardingStep.RESULT -> ResultPage(draft = draft, result = vm.result())
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Button(
                onClick = goNext,
                enabled = draft.isStepValid(step) && !vm.saving && !vm.restoring,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .height(56.dp),
            ) {
                if (vm.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = if (step.isLast) "Los geht's" else "Weiter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    vm.pendingRestore?.let { pending ->
        BackupImportConfirmDialog(
            summary = pending.summary,
            onConfirm = vm::confirmRestore,
            onDismiss = vm::dismissRestore,
        )
    }
}

@Composable
private fun ProgressHeader(step: OnboardingStep, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step.isFirst) {
            Spacer(Modifier.width(48.dp))
        } else {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
        }
        if (!step.isFirst) {
            val total = OnboardingStep.entries.size - 1
            ThinProgressBar(
                fraction = step.ordinal.toFloat() / total,
                height = 6,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${step.ordinal} / $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    if (subtitle != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(24.dp))
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun WelcomePage(
    name: String,
    onName: (String) -> Unit,
    restoring: Boolean,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(54.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Willkommen bei KalorienKompass",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Dein persönlicher Begleiter für Kalorien, Makros, Gewicht und Schritte.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FeatureRow(Icons.Filled.Restaurant, "Essen erfassen", "Suche, Barcode-Scan oder Foto mit KI")
            FeatureRow(Icons.Filled.MonitorWeight, "Körperwerte im Blick", "Gewicht und Körpermaße als Verlauf")
            FeatureRow(
                Icons.AutoMirrored.Filled.DirectionsWalk,
                "Schritte & Aktivität",
                "Über Health Connect, z. B. von Honor Health",
            )
            FeatureRow(Icons.Filled.Lock, "Privat", "Alle Daten bleiben auf deinem Gerät")
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { v -> if (v.length <= ProfileRules.MAX_NAME_LENGTH) onName(v) },
            label = { Text("Wie dürfen wir dich nennen? (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRestore, enabled = !restoring) {
            if (restoring) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Ich habe ein Backup – wiederherstellen")
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBadge(icon = icon, size = 44)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutPage(draft: OnboardingDraft, onChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    StepHeader(
        title = "Erzähl uns etwas über dich",
        subtitle = "Geschlecht und Alter fließen in die Berechnung deines Grundumsatzes ein.",
    )
    Text(
        text = "Geschlecht",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    SexChoice(selected = draft.sex, onSelect = { sex -> onChange { it.copy(sex = sex) } })
    Spacer(Modifier.height(24.dp))
    val error = if (draft.birthYear.length >= 4) ProfileRules.birthYearError(draft.birthYear) else null
    val year = ProfileRules.parseBirthYear(draft.birthYear)
    IntegerTextField(
        value = draft.birthYear,
        onValueChange = { v -> onChange { it.copy(birthYear = v) } },
        label = "Geburtsjahr",
        maxLength = 4,
        isError = error != null,
        supportingText = error
            ?: year?.let { "${GoalCalculator.age(it, LocalDate.now())} Jahre" }
            ?: "z. B. 1990",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BodyPage(
    draft: OnboardingDraft,
    onChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit,
    onDone: () -> Unit,
) {
    StepHeader(
        title = "Größe und Gewicht",
        subtitle = "Dein aktuelles Gewicht ist der Startpunkt für deinen Fortschritt.",
    )
    val heightError = if (draft.height.isNotBlank()) ProfileRules.heightError(draft.height) else null
    DecimalInputField(
        value = draft.height,
        onValueChange = { v -> onChange { it.copy(height = v) } },
        label = "Größe",
        suffix = "cm",
        isError = heightError != null,
        supportingText = heightError ?: "z. B. 178",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    val weightError = if (draft.weight.isNotBlank()) ProfileRules.weightError(draft.weight) else null
    DecimalInputField(
        value = draft.weight,
        onValueChange = { v -> onChange { it.copy(weight = v) } },
        label = "Aktuelles Gewicht",
        suffix = "kg",
        isError = weightError != null,
        supportingText = weightError ?: "z. B. 82,5",
        imeAction = ImeAction.Done,
        onImeAction = { if (draft.isStepValid(OnboardingStep.BODY)) onDone() },
        modifier = Modifier.fillMaxWidth(),
    )
    val h = draft.heightCm
    val w = draft.weightKg
    if (h != null && w != null) {
        val bmi = GoalCalculator.bmi(w, h)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(bmiColor(bmi).copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(bmiColor(bmi)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "BMI ${formatDecimal(bmi)} · ${GoalCalculator.bmiCategory(bmi)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ActivityPage(draft: OnboardingDraft, onChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit) {
    StepHeader(
        title = "Wie aktiv bist du?",
        subtitle = "Gemeint ist dein normaler Alltag. Schritte aus Health Connect kannst du später zusätzlich " +
            "zum Tagesbudget addieren lassen.",
    )
    ActivityLevelChoice(selected = draft.activity, onSelect = { level -> onChange { it.copy(activity = level) } })
}

@Composable
private fun GoalPage(
    draft: OnboardingDraft,
    onChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit,
    onSelectGoal: (GoalType) -> Unit,
) {
    StepHeader(title = "Was ist dein Ziel?")
    GoalTypeChoice(selected = draft.goalType, onSelect = onSelectGoal)
    val goal = draft.goalType ?: return
    Spacer(Modifier.height(24.dp))
    if (goal == GoalType.MAINTAIN) {
        InfoBox(
            icon = Icons.Filled.Info,
            text = "Wir berechnen deinen Erhaltungsbedarf. Als Zielgewicht wird dein aktuelles Gewicht " +
                "(${draft.weightKg?.let { formatDecimal(it) } ?: "–"} kg) gespeichert.",
        )
        return
    }
    val showError = draft.targetWeight.isNotBlank()
    val error = if (showError) draft.targetError() else null
    DecimalInputField(
        value = draft.targetWeight,
        onValueChange = { v -> onChange { it.copy(targetWeight = v) } },
        label = "Zielgewicht",
        suffix = "kg",
        isError = error != null,
        supportingText = error ?: "Aktuell: ${draft.weightKg?.let { formatDecimal(it) } ?: "–"} kg",
        imeAction = ImeAction.Done,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    WeeklyRateChoice(
        goal = goal,
        rate = draft.weeklyRate,
        onSelect = { rate -> onChange { it.copy(weeklyRate = rate) } },
    )
    val eta = ProfileRules.estimatedArrival(goal, draft.weightKg, draft.targetKg, draft.weeklyRate)
    if (eta != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Zielgewicht voraussichtlich erreicht: $eta",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ResultPage(draft: OnboardingDraft, result: OnboardingResult?) {
    StepHeader(
        title = "Dein Tagesziel",
        subtitle = "Berechnet mit der Mifflin-St-Jeor-Formel und deinem Aktivitätsfaktor.",
    )
    if (result == null) {
        Text(
            text = "Bitte gehe zurück und vervollständige deine Angaben.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CalorieRing(calories = result.calories)
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = "Kohlenhydrate",
            value = "${result.targets.carbsG} g",
            accent = AppColors.carbs,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Eiweiß",
            value = "${result.targets.proteinG} g",
            accent = AppColors.protein,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Fett",
            value = "${result.targets.fatG} g",
            accent = AppColors.fat,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(16.dp))
    AppCard(modifier = Modifier.fillMaxWidth()) {
        BreakdownRow("Grundumsatz", "${formatKcal(result.bmr)} kcal")
        BreakdownRow(
            "Gesamtumsatz (× ${formatDecimal(draft.activity?.pal ?: 1.0, 2)})",
            "${formatKcal(result.tdee)} kcal",
        )
        if (draft.goalType != GoalType.MAINTAIN) {
            BreakdownRow(
                "Anpassung für „${draft.goalType?.label.orEmpty()}“",
                "${if (result.adjustment < 0) "−" else "+"}${formatKcal(abs(result.adjustment))} kcal",
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        BreakdownRow("Dein Tagesziel", "${formatKcal(result.calories.toDouble())} kcal", bold = true)
    }
    if (result.minimumApplied) {
        Spacer(Modifier.height(12.dp))
        InfoBox(
            icon = Icons.Filled.Warning,
            tint = MaterialTheme.colorScheme.secondary,
            text = "Aus gesundheitlichen Gründen planen wir nicht unter ${formatKcal(result.calories.toDouble())} kcal. " +
                "Ein langsameres Tempo ist leichter durchzuhalten.",
        )
    }
    val profile = draft.toProfile()
    val eta = profile?.let {
        ProfileRules.estimatedArrival(it.goalType, it.startWeightKg, it.targetWeightKg, it.weeklyRateKg)
    }
    if (profile != null && eta != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Bei diesem Tempo erreichst du dein Ziel von ${formatDecimal(profile.targetWeightKg)} kg $eta.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Du kannst alles später unter Profil → Ziele anpassen – auch Makro- und Mahlzeitenverteilung.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BreakdownRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Big animated ring with the daily calorie goal in the middle. */
@Composable
private fun CalorieRing(calories: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(calories) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val start = MaterialTheme.colorScheme.primary
    val end = MaterialTheme.colorScheme.tertiary
    Box(modifier = modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(start, end, start)),
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatKcal(calories.toDouble()),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "kcal pro Tag",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
