package de.kalorienkompass.ui.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.SectionHeader
import de.kalorienkompass.ui.components.charts.BarChart
import de.kalorienkompass.ui.components.charts.LineChart
import de.kalorienkompass.ui.components.charts.Sparkline
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDate
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.formatSteps
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the add dialog should open with. */
private data class AddMeasurementRequest(val type: MeasurementType, val typeSelectable: Boolean)

/** Tab "Fortschritt": period selector, weight / calories / macros / steps cards and body measurements. */
@Composable
fun ProgressScreen(navController: NavController) {
    val viewModel: ProgressViewModel = viewModel(
        factory = appViewModelFactory { c ->
            ProgressViewModel(
                diaryRepository = c.diaryRepository,
                measurementRepository = c.measurementRepository,
                activityRepository = c.activityRepository,
                settingsRepository = c.settingsRepository,
                targetsUseCase = c.targetsUseCase,
            )
        },
    )
    ProgressScreen(navController = navController, viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressScreen(navController: NavController, viewModel: ProgressViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var addRequest by remember { mutableStateOf<AddMeasurementRequest?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Fortschritt", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { addRequest = AddMeasurementRequest(MeasurementType.WEIGHT, typeSelectable = true) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Messwert hinzufügen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RangeSelector(
                selected = range,
                onSelect = viewModel::selectRange,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
            when (val current = state) {
                ProgressUiState.Loading -> LoadingBox(Modifier.fillMaxSize())
                is ProgressUiState.Error -> ErrorContent(message = current.message, onRetry = viewModel::retry)
                is ProgressUiState.Ready -> ProgressList(
                    content = current.content,
                    onAddMeasurement = { type, selectable -> addRequest = AddMeasurementRequest(type, selectable) },
                    onOpenHistory = { type -> navController.navigate(Routes.measurementHistory(type)) },
                    onOpenDiary = {
                        navController.navigate(Routes.DIARY) {
                            popUpTo(Routes.DIARY) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenHealthConnect = { navController.navigate(Routes.HEALTH_CONNECT) },
                )
            }
        }
    }

    val request = addRequest
    if (request != null) {
        val content = (state as? ProgressUiState.Ready)?.content
        MeasurementDialog(
            title = if (request.typeSelectable) "Messwert hinzufügen" else "${request.type.label} eintragen",
            initialType = request.type,
            typeSelectable = request.typeSelectable,
            initialDate = content?.today ?: LocalDate.now(),
            initialValue = content?.latestValues?.get(request.type),
            prefillFor = { type -> content?.latestValues?.get(type) },
            existingManualValue = { type, date -> content?.manualValues?.get(type)?.get(date) },
            onSave = { type, date, value -> viewModel.saveMeasurement(type, date, value) },
            onDismiss = { addRequest = null },
        )
    }
}

@Composable
internal fun ProgressList(
    content: ProgressContent,
    onAddMeasurement: (MeasurementType, Boolean) -> Unit,
    onOpenHistory: (MeasurementType) -> Unit,
    onOpenDiary: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "weight") {
            WeightCard(
                state = content.weight,
                range = content.range,
                today = content.today,
                onAdd = { onAddMeasurement(MeasurementType.WEIGHT, false) },
                onOpen = { onOpenHistory(MeasurementType.WEIGHT) },
            )
        }
        item(key = "calories") {
            CaloriesCard(state = content.calories, today = content.today, onOpenDiary = onOpenDiary)
        }
        item(key = "macros") {
            MacrosCard(state = content.macros)
        }
        item(key = "steps") {
            StepsCard(state = content.steps, today = content.today, onConnect = onOpenHealthConnect)
        }
        item(key = "body_header") {
            SectionHeader(
                title = "Körpermaße",
                modifier = Modifier.padding(top = 4.dp),
                actionLabel = "Hinzufügen",
                onAction = { onAddMeasurement(MeasurementType.WAIST, true) },
            )
        }
        if (content.body.isEmpty()) {
            item(key = "body_empty") {
                BodyEmptyCard(onAdd = { onAddMeasurement(MeasurementType.WAIST, true) })
            }
        } else {
            items(content.body, key = { "body_${it.type.name}" }) { measurement ->
                BodyMeasurementCard(
                    state = measurement,
                    range = content.range,
                    today = content.today,
                    onOpen = { onOpenHistory(measurement.type) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Weight
// ---------------------------------------------------------------------------------------------

@Composable
private fun WeightCard(
    state: WeightCardState,
    range: ProgressRange,
    today: LocalDate,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        CardHeader(
            icon = Icons.Filled.MonitorWeight,
            title = "Gewicht",
            tint = colors.primary,
            subtitle = "Ziel: ${formatMeasurement(state.target, MeasurementType.WEIGHT)} · ${state.goalType.label}",
            trailing = {
                FilledTonalButton(
                    onClick = onAdd,
                    contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Eintrag")
                }
            },
        )
        val current = state.current
        if (!state.hasAnyData || current == null) {
            InlineEmpty(
                text = "Trage dein Gewicht ein – dann siehst du hier deinen Verlauf und wie weit es noch bis zum Ziel ist.",
                actionLabel = "Gewicht eintragen",
                onAction = onAdd,
            )
            return@AppCard
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatDecimal(current, 1),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = " kg",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            val change = state.stats.change
            if (change != null) {
                TrendChip(
                    text = "${formatSigned(change)} kg",
                    change = change,
                    trend = weightTrend(change, state.goalType),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        val changeCaption = if (state.stats.change != null) " · Veränderung ${range.inPeriod}" else ""
        Text(
            text = "Stand: ${state.currentDate?.let { dayLabel(it, today) } ?: "–"}$changeCaption",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        if (state.points.isEmpty()) {
            NoDataInPeriodBox(
                text = "Keine Messung in diesem Zeitraum – zuletzt am ${state.currentDate?.let { formatDate(it) } ?: "–"}.",
            )
        } else {
            LineChart(
                points = state.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                lineColor = colors.primary,
                goalValue = state.target.takeIf { it > 0.0 },
                valueFormatter = { formatMeasurement(it, MeasurementType.WEIGHT) },
                showDots = state.points.size <= 40,
                dateRange = state.chartFrom..today,
                tooltipDateFormatter = { date ->
                    if (state.granularity == Granularity.DAY) {
                        dayLabel(date, today)
                    } else {
                        "Ø " + bucketTitle(date, state.granularity, today)
                    }
                },
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            StatItem(
                label = "Start",
                value = state.stats.start?.let { formatMeasurement(it, MeasurementType.WEIGHT) } ?: "–",
                modifier = Modifier.weight(1f),
            )
            StatItem(
                label = "Aktuell",
                value = formatMeasurement(current, MeasurementType.WEIGHT),
                modifier = Modifier.weight(1f),
            )
            val change = state.stats.change
            StatItem(
                label = "Veränderung",
                value = change?.let { "${formatSigned(it)} kg" } ?: "–",
                modifier = Modifier.weight(1f),
                valueColor = change?.let { trendColor(weightTrend(it, state.goalType)) } ?: Color.Unspecified,
            )
            val goalStatus = state.goalStatus
            StatItem(
                label = "bis zum Ziel",
                value = when (goalStatus) {
                    GoalStatus.Reached -> if (state.goalType == GoalType.MAINTAIN) "Im Ziel" else "Erreicht"
                    is GoalStatus.Remaining -> formatMeasurement(goalStatus.kg, MeasurementType.WEIGHT)
                    is GoalStatus.OffTarget -> "${formatSigned(goalStatus.diff)} kg"
                    null -> "–"
                },
                modifier = Modifier.weight(1f),
                valueColor = if (goalStatus == GoalStatus.Reached) colors.primary else Color.Unspecified,
            )
        }

        val progress = state.goalProgress
        if (progress != null) {
            Spacer(Modifier.height(14.dp))
            val animated by animateFloatAsState(
                targetValue = progress.toFloat(),
                animationSpec = tween(durationMillis = 800),
                label = "goalProgress",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Weg zum Zielgewicht",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(progress * 100).roundToInt()} % geschafft",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = colors.primary,
                trackColor = colors.primary.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun NoDataInPeriodBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Calories & macros
// ---------------------------------------------------------------------------------------------

@Composable
private fun CaloriesCard(state: CaloriesCardState, today: LocalDate, onOpenDiary: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
            icon = Icons.Filled.LocalFireDepartment,
            title = "Kalorien",
            tint = AppColors.kcal,
            subtitle = if (state.goal > 0) "Ziel: ${formatKcal(state.goal.toDouble())} kcal pro Tag" else null,
        )
        if (!state.hasData) {
            InlineEmpty(
                text = "In diesem Zeitraum gibt es noch keine Einträge im Tagebuch.",
                actionLabel = "Zum Tagebuch",
                onAction = onOpenDiary,
            )
            return@AppCard
        }
        val average = state.average.average
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Ø ${formatKcal(average ?: 0.0)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = " kcal / Tag",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (average != null && state.goal > 0) {
                val diff = average - state.goal
                val diffColor = when {
                    diff > 0 && state.goalType != GoalType.GAIN -> AppColors.over
                    diff < 0 && state.goalType == GoalType.GAIN -> colors.secondary
                    else -> colors.primary
                }
                Text(
                    text = "${formatSignedKcal(diff)} kcal",
                    style = MaterialTheme.typography.labelLarge,
                    color = diffColor,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(diffColor.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            text = averageCaption(state.average, "Einträgen"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        BarChart(
            bars = state.bars,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            barColor = AppColors.kcal,
            overColor = if (state.goalType == GoalType.GAIN) AppColors.kcal else AppColors.over,
            goalValue = state.goal.toDouble().takeIf { it > 0.0 },
            valueFormatter = { value ->
                if (state.granularity == Granularity.DAY) "${formatKcal(value)} kcal" else "Ø ${formatKcal(value)} kcal/Tag"
            },
            labelFormatter = { date -> bucketAxisLabel(date, state.granularity, state.bars.size, today) },
            tooltipTitle = { bar -> bucketTitle(bar.date, state.granularity, today) },
            noDataText = "Kein Eintrag",
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            StatItem(
                label = "Unter dem Ziel",
                value = daysText(state.daysUnderGoal),
                modifier = Modifier.weight(1f),
            )
            StatItem(
                label = "Über dem Ziel",
                value = daysText(state.daysOverGoal),
                modifier = Modifier.weight(1f),
                valueColor = if (state.daysOverGoal > 0 && state.goalType != GoalType.GAIN) AppColors.over else Color.Unspecified,
            )
            StatItem(
                label = "Gesamt",
                value = "${formatKcal(state.total)} kcal",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MacrosCard(state: MacrosCardState?) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
            icon = Icons.Filled.PieChart,
            title = "Makronährstoffe",
            tint = AppColors.carbs,
            subtitle = "Ø pro Tag · Anteil an den Kalorien",
        )
        if (state == null) {
            InlineEmpty(
                text = "Sobald du Mahlzeiten einträgst, siehst du hier deine Verteilung von Kohlenhydraten, Eiweiß und Fett.",
            )
            return@AppCard
        }
        Spacer(Modifier.height(16.dp))
        StackedMacroBar(state.split)
        Spacer(Modifier.height(10.dp))
        MacroRow("Kohlenhydrate", AppColors.carbs, state.carbsG, state.split.carbs, state.goalSplit.carbs, state.goalCarbsG)
        MacroRow("Eiweiß", AppColors.protein, state.proteinG, state.split.protein, state.goalSplit.protein, state.goalProteinG)
        MacroRow("Fett", AppColors.fat, state.fatG, state.split.fat, state.goalSplit.fat, state.goalFatG)
    }
}

@Composable
private fun StackedMacroBar(split: MacroSplit) {
    val carbs by animateFloatAsState(split.carbs / 100f, animationSpec = tween(600), label = "carbsShare")
    val protein by animateFloatAsState(split.protein / 100f, animationSpec = tween(600), label = "proteinShare")
    val fat by animateFloatAsState(split.fat / 100f, animationSpec = tween(600), label = "fatShare")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (carbs > 0.001f) Box(Modifier.weight(carbs).fillMaxHeight().background(AppColors.carbs))
        if (protein > 0.001f) Box(Modifier.weight(protein).fillMaxHeight().background(AppColors.protein))
        if (fat > 0.001f) Box(Modifier.weight(fat).fillMaxHeight().background(AppColors.fat))
    }
}

@Composable
private fun MacroRow(label: String, color: Color, grams: Double, percent: Int, goalPercent: Int, goalGrams: Int) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Text(
                text = "Ziel: $goalPercent % · $goalGrams g",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = "${formatGrams(grams.roundToLong().toDouble())} g",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = "$percent %",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------------------------

@Composable
private fun StepsCard(state: StepsCardState, today: LocalDate, onConnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = "Schritte",
            tint = colors.tertiary,
            subtitle = if (state.goal > 0) "Ziel: ${formatSteps(state.goal.toLong())} pro Tag" else null,
        )
        if (!state.hasData) {
            if (state.healthConnectEnabled) {
                InlineEmpty(text = "Für diesen Zeitraum sind noch keine Schritte vorhanden.")
            } else {
                InlineEmpty(
                    text = "Verbinde Health Connect (z. B. mit Honor Health), damit deine Schritte automatisch " +
                        "erscheinen – oder trage sie im Tagebuch selbst ein.",
                    actionLabel = "Health Connect verbinden",
                    onAction = onConnect,
                )
            }
            return@AppCard
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Ø ${formatSteps((state.average.average ?: 0.0).roundToLong())}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = " Schritte / Tag",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = averageCaption(state.average, "Schritten"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        BarChart(
            bars = state.bars,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            barColor = colors.tertiary,
            overColor = colors.primary,
            goalValue = state.goal.toDouble().takeIf { it > 0.0 },
            valueFormatter = { value ->
                val steps = formatSteps(value.roundToLong())
                if (state.granularity == Granularity.DAY) "$steps Schritte" else "Ø $steps Schritte/Tag"
            },
            labelFormatter = { date -> bucketAxisLabel(date, state.granularity, state.bars.size, today) },
            tooltipTitle = { bar -> bucketTitle(bar.date, state.granularity, today) },
            goalColor = colors.secondary,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            StatItem(label = "Gesamt", value = formatSteps(state.total), modifier = Modifier.weight(1f))
            StatItem(
                label = "Ziel erreicht",
                value = daysText(state.daysGoalReached),
                modifier = Modifier.weight(1f),
                valueColor = if (state.daysGoalReached > 0) colors.primary else Color.Unspecified,
            )
            StatItem(
                label = "Bester Tag",
                value = state.bestDay?.let { formatSteps(it.value.roundToLong()) } ?: "–",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Body measurements
// ---------------------------------------------------------------------------------------------

@Composable
private fun BodyMeasurementCard(
    state: BodyMeasurementState,
    range: ProgressRange,
    today: LocalDate,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon = measurementIcon(state.type), tint = colors.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.type.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatDecimal(state.latest, 1),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                    Text(
                        text = " ${state.type.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                val change = state.change
                Text(
                    text = if (change != null) {
                        "${formatSigned(change)} ${state.type.unit} ${range.inPeriod}"
                    } else {
                        "zuletzt: ${dayLabel(state.latestDate, today)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (state.points.size >= 2) {
                Sparkline(
                    points = state.points,
                    modifier = Modifier.size(width = 96.dp, height = 40.dp),
                    lineColor = colors.tertiary,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun BodyEmptyCard(onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon = Icons.Filled.Straighten, tint = colors.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Noch keine Körpermaße",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Text(
                    text = "Miss z. B. Bauch- und Hüftumfang – so siehst du Fortschritte, die die Waage nicht zeigt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Körpermaß hinzufügen")
        }
    }
}
