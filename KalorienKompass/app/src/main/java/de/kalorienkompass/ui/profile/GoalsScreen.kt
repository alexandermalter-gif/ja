package de.kalorienkompass.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.appViewModelFactory
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.formatSteps
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalsScreen(navController: NavController) {
    val vm: GoalsViewModel = viewModel(
        factory = appViewModelFactory { GoalsViewModel(it.settingsRepository, it.measurementRepository) },
    )
    val draft = vm.draft
    val latestWeight by vm.latestWeight.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val dirty = vm.isDirty
    val errors = remember(draft) { draft?.validate() ?: GoalsErrors() }
    val canSave = draft != null && dirty && !errors.hasErrors && !vm.saving

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                GoalsEvent.Saved -> navController.popBackStackSafely()
                is GoalsEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val onBack: () -> Unit = {
        if (vm.isDirty) confirmDiscard = true else navController.popBackStackSafely()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ziele & Körperdaten", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    TextButton(onClick = vm::save, enabled = canSave) {
                        Text("Speichern", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (draft == null) {
            LoadingBox(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            GoalsForm(
                draft = draft,
                errors = errors,
                latestWeight = latestWeight,
                padding = padding,
                canSave = canSave,
                saving = vm.saving,
                dirty = dirty,
                onChange = vm::update,
                onSave = vm::save,
            )
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Änderungen verwerfen?",
            text = "Deine Änderungen an Profil und Zielen wurden noch nicht gespeichert.",
            confirmLabel = "Verwerfen",
            destructive = true,
            onConfirm = { navController.popBackStackSafely() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalsForm(
    draft: GoalsDraft,
    errors: GoalsErrors,
    latestWeight: Double?,
    padding: PaddingValues,
    canSave: Boolean,
    saving: Boolean,
    dirty: Boolean,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
    onSave: () -> Unit,
) {
    val preview = remember(draft, latestWeight) { draft.preview(latestWeight) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PersonalSection(draft, errors, onChange)
        WeightSection(draft, errors, latestWeight, onChange)
        ActivitySection(draft.activity) { level -> onChange { it.copy(activity = level) } }
        GoalSection(draft, latestWeight, onChange)
        EnergySection(draft, errors, preview, onChange)
        MacroSection(draft, errors, preview, onChange)
        MealSection(draft, errors, preview, onChange)
        StepSection(draft, errors, onChange)

        if (errors.hasErrors) {
            Text(
                text = "Bitte korrigiere die markierten Angaben, um zu speichern.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = onSave,
            enabled = canSave,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(if (dirty) "Speichern" else "Keine Änderungen", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalSection(
    draft: GoalsDraft,
    errors: GoalsErrors,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    SectionCard(title = "Persönliche Daten", icon = Icons.Filled.Person) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { v -> if (v.length <= ProfileRules.MAX_NAME_LENGTH) onChange { it.copy(name = v) } },
            label = { Text("Name (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Geschlecht",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Sex.entries.forEachIndexed { index, sex ->
                SegmentedButton(
                    selected = draft.sex == sex,
                    onClick = { onChange { it.copy(sex = sex) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Sex.entries.size),
                    colors = profileSegmentedColors(),
                    icon = {
                        Icon(sex.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    label = { Text(sex.label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val year = ProfileRules.parseBirthYear(draft.birthYear)
            IntegerTextField(
                value = draft.birthYear,
                onValueChange = { v -> onChange { it.copy(birthYear = v) } },
                label = "Geburtsjahr",
                maxLength = 4,
                isError = errors.birthYear != null,
                supportingText = errors.birthYear
                    ?: year?.let { "${GoalCalculator.age(it, LocalDate.now())} Jahre" },
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f),
            )
            DecimalInputField(
                value = draft.height,
                onValueChange = { v -> onChange { it.copy(height = v) } },
                label = "Größe",
                suffix = "cm",
                isError = errors.height != null,
                supportingText = errors.height,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WeightSection(
    draft: GoalsDraft,
    errors: GoalsErrors,
    latestWeight: Double?,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    SectionCard(title = "Gewicht", icon = Icons.Filled.MonitorWeight) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DecimalInputField(
                value = draft.startWeight,
                onValueChange = { v -> onChange { it.copy(startWeight = v) } },
                label = "Startgewicht",
                suffix = "kg",
                isError = errors.startWeight != null,
                supportingText = errors.startWeight,
                modifier = Modifier.weight(1f),
            )
            DecimalInputField(
                value = draft.targetWeight,
                onValueChange = { v -> onChange { it.copy(targetWeight = v) } },
                label = "Zielgewicht",
                suffix = "kg",
                isError = errors.targetWeight != null,
                supportingText = errors.targetWeight,
                imeAction = ImeAction.Done,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        InfoBox(
            icon = Icons.Filled.Info,
            text = if (latestWeight != null) {
                "Aktuelles Gewicht laut Tagebuch: ${formatDecimal(latestWeight)} kg. " +
                    "Es wird für die Berechnung verwendet. Neue Werte trägst du im Tagebuch oder unter Fortschritt ein."
            } else {
                "Noch kein Gewicht eingetragen – für die Berechnung wird dein Startgewicht verwendet."
            },
        )
        val current = latestWeight ?: ProfileRules.parseWeight(draft.startWeight)
        val problem = ProfileRules.targetDirectionProblem(
            draft.goalType,
            current,
            ProfileRules.parseWeight(draft.targetWeight),
        )
        if (problem != null) {
            Spacer(Modifier.height(8.dp))
            InfoBox(icon = Icons.Filled.Warning, text = problem, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ActivitySection(selected: ActivityLevel, onSelect: (ActivityLevel) -> Unit) {
    SectionCard(title = "Aktivität im Alltag", icon = Icons.AutoMirrored.Filled.DirectionsRun) {
        ActivityLevel.entries.forEach { level ->
            RadioRow(
                selected = level == selected,
                title = level.label,
                subtitle = "${level.description} · Faktor ${formatDecimal(level.pal, 2)}",
                icon = level.icon(),
                onClick = { onSelect(level) },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sport, den du über Schritte oder Health Connect erfasst, kann zusätzlich zum Tagesbudget addiert werden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSection(
    draft: GoalsDraft,
    latestWeight: Double?,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    SectionCard(title = "Ziel", icon = Icons.Filled.Flag) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            GoalType.entries.forEachIndexed { index, goal ->
                SegmentedButton(
                    selected = draft.goalType == goal,
                    onClick = { onChange { it.copy(goalType = goal) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = GoalType.entries.size),
                    colors = profileSegmentedColors(),
                    icon = {},
                    label = {
                        Text(
                            text = when (goal) {
                                GoalType.LOSE -> "Abnehmen"
                                GoalType.MAINTAIN -> "Halten"
                                GoalType.GAIN -> "Zunehmen"
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        if (draft.goalType != GoalType.MAINTAIN) {
            Spacer(Modifier.height(14.dp))
            WeeklyRateChoice(
                goal = draft.goalType,
                rate = draft.weeklyRate,
                onSelect = { rate -> onChange { it.copy(weeklyRate = rate) } },
            )
            val eta = ProfileRules.estimatedArrival(
                goal = draft.goalType,
                currentKg = latestWeight ?: ProfileRules.parseWeight(draft.startWeight),
                targetKg = ProfileRules.parseWeight(draft.targetWeight),
                rate = draft.weeklyRate,
            )
            if (eta != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Zielgewicht voraussichtlich erreicht: $eta",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Dein Tagesziel entspricht deinem Gesamtumsatz – so bleibt dein Gewicht stabil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EnergySection(
    draft: GoalsDraft,
    errors: GoalsErrors,
    preview: GoalsPreview?,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    SectionCard(title = "Kalorienbedarf", icon = Icons.Filled.LocalFireDepartment) {
        if (preview == null) {
            Text(
                text = "Bitte vervollständige Geburtsjahr, Größe und Gewicht, um deinen Bedarf zu berechnen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EnergyRow("Grundumsatz (Mifflin-St Jeor)", "${formatKcal(preview.bmr)} kcal")
            EnergyRow(
                "Gesamtumsatz (× ${formatDecimal(draft.activity.pal, 2)})",
                "${formatKcal(preview.tdee)} kcal",
            )
            if (draft.goalType != GoalType.MAINTAIN) {
                val adj = preview.adjustment
                EnergyRow(
                    "Anpassung für „${draft.goalType.label}“",
                    "${if (adj < 0) "−" else "+"}${formatKcal(abs(adj))} kcal",
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            EnergyRow(
                label = "Berechnetes Tagesziel",
                value = "${formatKcal(preview.computedCalories.toDouble())} kcal",
                bold = !draft.customEnabled,
            )
            if (preview.minimumApplied) {
                Spacer(Modifier.height(6.dp))
                InfoBox(
                    icon = Icons.Filled.Warning,
                    tint = MaterialTheme.colorScheme.secondary,
                    text = "Aus gesundheitlichen Gründen setzen wir mindestens " +
                        "${formatKcal(GoalCalculator.minCalories(draft.sex).toDouble())} kcal an. " +
                        "Wähle ein langsameres Tempo für ein realistisches Ziel.",
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow(
            title = "Eigenes Kalorienziel",
            subtitle = "Überschreibt den berechneten Wert",
            checked = draft.customEnabled,
            onCheckedChange = { enabled ->
                onChange {
                    it.copy(
                        customEnabled = enabled,
                        customCalories = if (enabled && it.customCalories.isBlank()) {
                            preview?.computedCalories?.toString().orEmpty()
                        } else {
                            it.customCalories
                        },
                    )
                }
            },
        )
        if (draft.customEnabled) {
            Spacer(Modifier.height(6.dp))
            IntegerTextField(
                value = draft.customCalories,
                onValueChange = { v -> onChange { it.copy(customCalories = v) } },
                label = "Tagesziel",
                suffix = "kcal",
                maxLength = 4,
                isError = errors.customCalories != null,
                supportingText = errors.customCalories,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (preview != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Dein Tagesziel",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatKcal(preview.effectiveCalories.toDouble())} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EnergyRow(label: String, value: String, bold: Boolean = false) {
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
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

private data class MacroPreset(val label: String, val carbs: Int, val protein: Int, val fat: Int)

private val MACRO_PRESETS: List<MacroPreset> = listOf(
    MacroPreset("Ausgewogen", 50, 20, 30),
    MacroPreset("Proteinreich", 40, 30, 30),
    MacroPreset("Low Carb", 25, 35, 40),
    MacroPreset("Ausdauer", 55, 20, 25),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroSection(
    draft: GoalsDraft,
    errors: GoalsErrors,
    preview: GoalsPreview?,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    val kcal = preview?.effectiveCalories?.toDouble()
    SectionCard(title = "Makronährstoffe", icon = Icons.Filled.PieChart) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MACRO_PRESETS.forEach { preset ->
                val selected = draft.carbs == preset.carbs && draft.protein == preset.protein && draft.fat == preset.fat
                FilterChip(
                    selected = selected,
                    onClick = {
                        onChange { it.copy(carbs = preset.carbs, protein = preset.protein, fat = preset.fat) }
                    },
                    label = { Text("${preset.label} ${preset.carbs}/${preset.protein}/${preset.fat}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PercentSlider(
            label = "Kohlenhydrate",
            value = draft.carbs,
            color = AppColors.carbs,
            detail = kcal?.let { "${(it * draft.carbs / 100.0 / 4.0).roundToInt()} g" },
            onChange = { v -> onChange { it.copy(carbs = v) } },
        )
        PercentSlider(
            label = "Eiweiß",
            value = draft.protein,
            color = AppColors.protein,
            detail = kcal?.let { "${(it * draft.protein / 100.0 / 4.0).roundToInt()} g" },
            onChange = { v -> onChange { it.copy(protein = v) } },
        )
        PercentSlider(
            label = "Fett",
            value = draft.fat,
            color = AppColors.fat,
            detail = kcal?.let { "${(it * draft.fat / 100.0 / 9.0).roundToInt()} g" },
            onChange = { v -> onChange { it.copy(fat = v) } },
        )
        SumLine(
            sum = draft.macroSum,
            error = errors.macros,
            onBalance = {
                onChange {
                    val b = balanceTo100(listOf(it.carbs, it.protein, it.fat), preferIndex = 2)
                    it.copy(carbs = b[0], protein = b[1], fat = b[2])
                }
            },
        )
        if (preview != null && preview.weightKg > 0.0) {
            val proteinG = preview.effectiveCalories * draft.protein / 100.0 / 4.0
            Text(
                text = "Eiweiß: ${formatDecimal(proteinG / preview.weightKg)} g pro kg Körpergewicht " +
                    "(Richtwert: mindestens 0,8 g/kg, beim Abnehmen oder Muskelaufbau eher 1,2–2,0 g/kg)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MealSection(
    draft: GoalsDraft,
    errors: GoalsErrors,
    preview: GoalsPreview?,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    val kcal = preview?.effectiveCalories?.toDouble()
    SectionCard(
        title = "Verteilung auf Mahlzeiten",
        icon = Icons.Filled.Restaurant,
        trailing = {
            TextButton(
                onClick = { onChange { it.copy(breakfast = 30, lunch = 40, dinner = 25, snack = 5) } },
            ) {
                Text("Standard")
            }
        },
    ) {
        val meals = listOf(
            MealType.BREAKFAST to draft.breakfast,
            MealType.LUNCH to draft.lunch,
            MealType.DINNER to draft.dinner,
            MealType.SNACK to draft.snack,
        )
        meals.forEach { (meal, value) ->
            PercentSlider(
                label = "${meal.emoji}  ${meal.label}",
                value = value,
                color = MaterialTheme.colorScheme.primary,
                detail = kcal?.let { "${formatKcal(it * value / 100.0)} kcal" },
                onChange = { v ->
                    onChange {
                        when (meal) {
                            MealType.BREAKFAST -> it.copy(breakfast = v)
                            MealType.LUNCH -> it.copy(lunch = v)
                            MealType.DINNER -> it.copy(dinner = v)
                            MealType.SNACK -> it.copy(snack = v)
                        }
                    }
                },
            )
        }
        SumLine(
            sum = draft.mealSum,
            error = errors.meals,
            onBalance = {
                onChange {
                    val b = balanceTo100(listOf(it.breakfast, it.lunch, it.dinner, it.snack), preferIndex = 3)
                    it.copy(breakfast = b[0], lunch = b[1], dinner = b[2], snack = b[3])
                }
            },
        )
    }
}

@Composable
private fun StepSection(
    draft: GoalsDraft,
    errors: GoalsErrors,
    onChange: ((GoalsDraft) -> GoalsDraft) -> Unit,
) {
    SectionCard(title = "Schrittziel", icon = Icons.AutoMirrored.Filled.DirectionsWalk) {
        val current = draft.stepGoal.toIntOrNull()
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = {
                    val base = current ?: 10_000
                    val next = (base - ProfileRules.STEP_GOAL_STEP).coerceAtLeast(ProfileRules.MIN_STEP_GOAL)
                    onChange { it.copy(stepGoal = next.toString()) }
                },
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "500 Schritte weniger")
            }
            Spacer(Modifier.width(12.dp))
            IntegerTextField(
                value = draft.stepGoal,
                onValueChange = { v -> onChange { it.copy(stepGoal = v) } },
                label = "Schritte pro Tag",
                maxLength = 6,
                isError = errors.stepGoal != null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalIconButton(
                onClick = {
                    val base = current ?: 10_000
                    val next = (base + ProfileRules.STEP_GOAL_STEP).coerceAtMost(ProfileRules.MAX_STEP_GOAL)
                    onChange { it.copy(stepGoal = next.toString()) }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "500 Schritte mehr")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = errors.stepGoal ?: (current?.let { "≈ ${formatDecimal(it * 0.00075, 1)} km Gehstrecke · Ziel ${formatSteps(it.toLong())} Schritte" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (errors.stepGoal != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun PercentSlider(
    label: String,
    value: Int,
    color: Color,
    detail: String?,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$value %",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            if (detail != null) {
                Text(
                    text = " · $detail",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value.toFloat().coerceIn(0f, 100f),
            onValueChange = { v -> onChange(v.roundToInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = color.copy(alpha = 0.2f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SumLine(sum: Int, error: String?, onBalance: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error == null) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Summe: $sum %",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onBalance,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Ausgleichen")
            }
        }
    }
}
