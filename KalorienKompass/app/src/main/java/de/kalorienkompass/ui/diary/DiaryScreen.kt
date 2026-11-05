package de.kalorienkompass.ui.diary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.MacroProgress
import de.kalorienkompass.ui.components.NumberInputDialog
import de.kalorienkompass.ui.components.SectionHeader
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDate
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.formatShortDate
import de.kalorienkompass.util.formatSteps
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** Diary home ("Tagebuch" tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(navController: NavController) {
    val vm: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh steps/weights whenever the diary becomes visible; save a pending weight when it is left.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushPendingWeight() }

    LaunchedEffect(vm, snackbarHostState) {
        vm.events.collect { event ->
            when (event) {
                is DiaryEvent.Message -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(event.text) }
                }
            }
        }
    }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showWeightDialog by rememberSaveable { mutableStateOf(false) }
    var showStepsDialog by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DiaryTopBar(
                date = state.date,
                today = state.today,
                onPrevious = vm::previousDay,
                onNext = vm::nextDay,
                onPickDate = { showDatePicker = true },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !state.isToday,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = vm::goToToday,
                    icon = { Icon(Icons.Filled.Today, contentDescription = null) },
                    text = { Text("Heute") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { vm.refresh(userInitiated = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val errorMessage = state.errorMessage
            when {
                state.isLoading -> LoadingBox(Modifier.fillMaxSize())
                errorMessage != null -> DiaryError(message = errorMessage, onRetry = vm::retry)
                else -> DiaryContent(
                    state = state,
                    onPreviousDay = vm::previousDay,
                    onNextDay = vm::nextDay,
                    onOpenMeal = { meal -> navController.navigateIfResumed(lifecycleOwner, Routes.meal(state.date, meal)) },
                    onAddToMeal = { meal ->
                        navController.navigateIfResumed(lifecycleOwner, Routes.foodSearch(state.date, meal))
                    },
                    onWeightStep = vm::changeWeightBy,
                    onEditWeight = { showWeightDialog = true },
                    onMoreBodyValues = { navController.navigateToTab(Routes.PROGRESS) },
                    onConnectHealth = { navController.navigateIfResumed(lifecycleOwner, Routes.HEALTH_CONNECT) },
                    onEnterSteps = { showStepsDialog = true },
                    onSync = { vm.refresh(userInitiated = true) },
                )
            }
        }
    }

    if (showDatePicker) {
        DiaryDatePickerDialog(
            initial = state.date,
            today = state.today,
            onConfirm = { date ->
                vm.selectDate(date)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showWeightDialog) {
        NumberInputDialog(
            title = "Gewicht eintragen",
            initial = state.weight?.valueKg,
            suffix = "kg",
            onConfirm = vm::setWeight,
            onDismiss = { showWeightDialog = false },
        )
    }
    if (showStepsDialog) {
        NumberInputDialog(
            title = "Schritte eintragen",
            initial = state.activity?.steps?.takeIf { it > 0L }?.toDouble(),
            suffix = "Schritte",
            onConfirm = { vm.setManualSteps(it.roundToLong()) },
            onDismiss = { showStepsDialog = false },
            decimals = false,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryTopBar(
    date: LocalDate,
    today: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    TopAppBar(
        title = {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Datum wählen", onClick = onPickDate)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                AnimatedContent(
                    targetState = date,
                    transitionSpec = {
                        val forward = targetState.isAfter(initialState)
                        (slideInHorizontally { width -> if (forward) width / 3 else -width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> if (forward) -width / 3 else width / 3 } + fadeOut())
                    },
                    label = "dayLabel",
                ) { shown ->
                    val label = dayLabel(shown, today)
                    Text(
                        text = label,
                        style = if (label.length > 12) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = topBarSubtitle(date, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Vorheriger Tag")
            }
            IconButton(onClick = onPickDate) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Datum wählen")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Nächster Tag")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** Full date below "Heute"/"Gestern"/"Morgen", otherwise the distance to today. */
private fun topBarSubtitle(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        abs(days) <= 1L -> fullDateLabel(date, today)
        days < 0L -> "vor ${-days} Tagen"
        else -> "in $days Tagen"
    }
}

// ---------------------------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------------------------

@Composable
private fun DiaryContent(
    state: DiaryUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenMeal: (MealType) -> Unit,
    onAddToMeal: (MealType) -> Unit,
    onWeightStep: (Double) -> Unit,
    onEditWeight: () -> Unit,
    onMoreBodyValues: () -> Unit,
    onConnectHealth: () -> Unit,
    onEnterSteps: () -> Unit,
    onSync: () -> Unit,
) {
    val summary = state.summary ?: return
    val currentOnPrevious by rememberUpdatedState(onPreviousDay)
    val currentOnNext by rememberUpdatedState(onNextDay)
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun settle() {
        val from = dragOffset
        settleJob = scope.launch {
            animate(initialValue = from, targetValue = 0f, animationSpec = spring()) { value, _ -> dragOffset = value }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Swipe horizontally to change the day.
            .pointerInput(thresholdPx) {
                detectHorizontalDragGestures(
                    onDragStart = { settleJob?.cancel() },
                    onDragEnd = {
                        when {
                            dragOffset > thresholdPx -> currentOnPrevious()
                            dragOffset < -thresholdPx -> currentOnNext()
                        }
                        settle()
                    },
                    onDragCancel = { settle() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    },
                )
            }
            .graphicsLayer {
                translationX = dragOffset * 0.35f
                alpha = 1f - (abs(dragOffset) / (thresholdPx * 8f)).coerceIn(0f, 0.25f)
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        OverviewCard(summary)

        SectionHeader(title = "Ernährung")
        MealsCard(meals = state.meals, onOpen = onOpenMeal, onAdd = onAddToMeal)

        SectionHeader(title = "Körperwerte", actionLabel = "Mehr", onAction = onMoreBodyValues)
        state.weight?.let { weight ->
            WeightCard(
                weight = weight,
                date = state.date,
                today = state.today,
                onStep = onWeightStep,
                onEdit = onEditWeight,
            )
        }

        SectionHeader(title = "Aktivitäten")
        state.activity?.let { activity ->
            ActivityCard(
                activity = activity,
                today = state.today,
                onConnect = onConnectHealth,
                onEnterSteps = onEnterSteps,
                onSync = onSync,
            )
        }
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun DiaryError(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Filled.ErrorOutline,
            title = "Etwas ist schiefgelaufen",
            text = message,
            actionLabel = "Erneut versuchen",
            onAction = onRetry,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// "Übersicht"
// ---------------------------------------------------------------------------------------------

@Composable
private fun OverviewCard(summary: DaySummary) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Übersicht",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Ziel ${formatKcal(summary.goalKcal.toDouble())} kcal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewStat(
                icon = Icons.Filled.Restaurant,
                tint = AppColors.kcal,
                value = formatKcal(summary.eatenKcal),
                label = "Gegessen",
                modifier = Modifier.weight(1f),
            )
            CalorieRing(
                progress = summary.ringProgress,
                over = summary.isOver,
                modifier = Modifier.size(150.dp),
            ) {
                RingCenter(summary)
            }
            OverviewStat(
                icon = Icons.Filled.LocalFireDepartment,
                tint = AppColors.burned,
                value = formatKcal(summary.burnedKcal),
                label = "Verbrannt",
                note = if (!summary.burnedIncluded && summary.burnedKcal >= 1.0) "nicht eingerechnet" else null,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroProgress(
                label = "Kohlenhydrate",
                current = summary.carbs,
                goal = summary.carbsGoal.toDouble(),
                color = AppColors.carbs,
                modifier = Modifier.weight(1f),
            )
            MacroProgress(
                label = "Eiweiß",
                current = summary.protein,
                goal = summary.proteinGoal.toDouble(),
                color = AppColors.protein,
                modifier = Modifier.weight(1f),
            )
            MacroProgress(
                label = "Fett",
                current = summary.fat,
                goal = summary.fatGoal.toDouble(),
                color = AppColors.fat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RingCenter(summary: DaySummary) {
    val remaining by animateFloatAsState(
        targetValue = summary.remainingKcal.toFloat(),
        animationSpec = tween(durationMillis = 700),
        label = "remainingKcal",
    )
    val over = summary.isOver
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatKcal(abs(remaining.toDouble())),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = if (over) AppColors.over else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = if (over) "zu viel" else "Übrig",
            style = MaterialTheme.typography.labelLarge,
            color = if (over) AppColors.over else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverviewStat(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "Ernährung"
// ---------------------------------------------------------------------------------------------

@Composable
private fun MealsCard(
    meals: List<MealSummary>,
    onOpen: (MealType) -> Unit,
    onAdd: (MealType) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        meals.forEachIndexed { index, meal ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                )
            }
            MealRow(meal = meal, onClick = { onOpen(meal.meal) }, onAdd = { onAdd(meal.meal) })
        }
    }
}

@Composable
private fun MealRow(meal: MealSummary, onClick: () -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "${meal.meal.label} öffnen", onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = meal.meal.emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meal.meal.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "${formatKcal(meal.kcal)} / ${formatKcal(meal.targetKcal.toDouble())} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = if (meal.isOver) AppColors.over else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = meal.preview.ifEmpty { "Noch nichts eingetragen" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (meal.preview.isEmpty()) 0.6f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            ThinProgressBar(
                progress = meal.progress,
                color = if (meal.isOver) AppColors.over else AppColors.kcal,
                height = 3.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        FilledIconButton(
            onClick = onAdd,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Zu ${meal.meal.label} hinzufügen")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "Körperwerte"
// ---------------------------------------------------------------------------------------------

@Composable
private fun WeightCard(
    weight: WeightCardState,
    date: LocalDate,
    today: LocalDate,
    onStep: (Double) -> Unit,
    onEdit: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.tertiary
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.MonitorWeight, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Gewicht", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = weightEntryHint(weight, date, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Ziel: ${formatDecimal(weight.targetKg)} kg",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RepeatingIconButton(
                icon = Icons.Filled.Remove,
                contentDescription = "100 g weniger",
                onStep = { onStep(-DiaryViewModel.WEIGHT_STEP_KG) },
                enabled = weight.editable,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        enabled = weight.editable,
                        onClickLabel = "Gewicht eingeben",
                        onClick = onEdit,
                    )
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatDecimal(weight.valueKg),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "kg",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            RepeatingIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "100 g mehr",
                onStep = { onStep(DiaryViewModel.WEIGHT_STEP_KG) },
                enabled = weight.editable,
            )
        }
        weightGoalProgress(weight.valueKg, weight.startKg, weight.targetKg)?.let { progress ->
            Spacer(Modifier.height(12.dp))
            ThinProgressBar(progress = progress, color = tint, height = 6.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = weightGoalText(weight.valueKg, weight.startKg, weight.targetKg),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatSignedKg(weight.valueKg - weight.startKg)} kg seit Start",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!weight.editable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Für zukünftige Tage kann noch kein Gewicht eingetragen werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun weightEntryHint(weight: WeightCardState, date: LocalDate, today: LocalDate): String {
    val last = weight.lastEntryDate
    return when {
        weight.hasPendingChange -> "Wird gespeichert …"
        last == null -> if (weight.editable) "Noch kein Eintrag – tippe auf den Wert" else "Noch kein Eintrag"
        last == date && date == today -> "Heute eingetragen"
        last == date && date == today.minusDays(1) -> "Gestern eingetragen"
        last == date -> "Eingetragen am ${shortDate(date, today)}"
        last == today.minusDays(1) -> "Letzter Eintrag: gestern"
        else -> "Letzter Eintrag: ${shortDate(last, today)}"
    }
}

private fun shortDate(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) formatShortDate(date) else formatDate(date)

// ---------------------------------------------------------------------------------------------
// "Aktivitäten"
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActivityCard(
    activity: ActivityCardState,
    today: LocalDate,
    onConnect: () -> Unit,
    onEnterSteps: () -> Unit,
    onSync: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.AutoMirrored.Filled.DirectionsWalk, tint = AppColors.kcal)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Schritte", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = activity.source?.let { "Quelle: ${it.label}" }
                        ?: if (activity.steps == 0L) "Noch keine Schritte erfasst" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (activity.hasAutomaticSource) {
                if (activity.isSyncing) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onSync) {
                        Icon(Icons.Filled.Sync, contentDescription = "Jetzt synchronisieren")
                    }
                }
                if (activity.editable) {
                    IconButton(onClick = onEnterSteps) {
                        Icon(Icons.Filled.Edit, contentDescription = "Schritte selbst eintragen")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatSteps(activity.steps),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = " / ${formatSteps(activity.stepGoal.toLong())}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        ThinProgressBar(
            progress = stepProgress(activity.steps, activity.stepGoal),
            color = AppColors.kcal,
            height = 6.dp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = AppColors.burned,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${formatKcal(activity.burnedKcal)} kcal verbrannt",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (activity.burnedFromHealthConnect) " · laut Health Connect" else " · geschätzt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (activity.healthConnectEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = activity.lastSyncAt?.let { "Zuletzt synchronisiert: ${lastSyncLabel(it, today)}" }
                    ?: "Noch nicht synchronisiert",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        activity.syncError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SyncProblem,
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
                if (activity.healthConnectEnabled) {
                    TextButton(onClick = onConnect) { Text("Einrichten") }
                }
            }
        }
        if (!activity.healthConnectEnabled) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.HealthAndSafety,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Mit Health Connect verbinden")
            }
            // With the step sensor active the edit icon in the header offers manual entry.
            if (!activity.stepSensorEnabled) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onEnterSteps,
                    enabled = activity.editable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Schritte selbst eintragen")
                }
            }
        }
        if (!activity.editable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Für zukünftige Tage können keine Schritte eingetragen werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------------------------

/** Ignores taps while a transition is running (prevents double navigation). */
internal fun NavController.navigateIfResumed(lifecycleOwner: LifecycleOwner, route: String) {
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate(route)
}

/** Same tab navigation as the bottom bar. */
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.DIARY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
