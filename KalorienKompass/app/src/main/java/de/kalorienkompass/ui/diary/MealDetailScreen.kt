package de.kalorienkompass.ui.diary

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.MacroProgress
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatKcal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Entries of one meal on one day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDetailScreen(navController: NavController, date: LocalDate, meal: MealType) {
    val vm: MealDetailViewModel = viewModel(
        key = "meal_detail_${date.toEpochDay()}_${meal.name}",
        factory = appViewModelFactory { container ->
            MealDetailViewModel(
                date = date,
                meal = meal,
                diaryRepository = container.diaryRepository,
                targets = container.targetsUseCase.observeTargets(date),
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val today = remember { LocalDate.now() }
    val fromPreviousDay = if (date == today) "von gestern" else "vom Vortag"
    val copyLabel = if (date == today) "Von gestern kopieren" else "Vom Vortag kopieren"

    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val addFood = { navController.navigateIfResumed(lifecycleOwner, Routes.foodSearch(date, meal)) }
    val goBack = {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.popBackStack()
    }

    LaunchedEffect(vm, snackbarHostState) {
        vm.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                is MealDetailEvent.Deleted -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "„${event.entry.name}“ gelöscht",
                        actionLabel = "Rückgängig",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoDelete(event.entry)
                }
                is MealDetailEvent.Copied -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "${entryCountLabel(event.count)} $fromPreviousDay kopiert",
                        actionLabel = "Rückgängig",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoCopy(event.entryIds)
                }
                is MealDetailEvent.Moved -> launch {
                    snackbarHostState.showSnackbar("„${event.name}“ nach ${event.meal.label} verschoben")
                }
                is MealDetailEvent.Message -> launch {
                    snackbarHostState.showSnackbar(event.text)
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${meal.emoji} ${meal.label}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = dayLabel(date, today),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Optionen")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(copyLabel) },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                enabled = !state.isCopying,
                                onClick = {
                                    menuOpen = false
                                    vm.copyFromPreviousDay()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Lebensmittel hinzufügen") },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    addFood()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addFood() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Hinzufügen") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val errorMessage = state.errorMessage
        when {
            state.isLoading -> LoadingBox(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            errorMessage != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    title = "Etwas ist schiefgelaufen",
                    text = errorMessage,
                    actionLabel = "Erneut versuchen",
                    onAction = vm::retry,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") {
                    MealSummaryCard(state = state)
                }
                if (state.entries.isEmpty()) {
                    item(key = "empty") {
                        EmptyMeal(
                            meal = meal,
                            copyLabel = copyLabel,
                            previousDayCount = state.previousDayCount,
                            previousDayKcal = state.previousDayKcal,
                            isCopying = state.isCopying,
                            onAdd = { addFood() },
                            onCopy = vm::copyFromPreviousDay,
                        )
                    }
                } else {
                    item(key = "header") {
                        Text(
                            text = "${entryCountLabel(state.entries.size)} · zum Bearbeiten antippen, zum Löschen nach links wischen",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                    items(items = state.entries, key = { it.id }) { entry ->
                        SwipeableEntryRow(
                            entry = entry,
                            onClick = { editingId = entry.id },
                            onDelete = { vm.delete(entry) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    // Forget dialogs whose entry no longer exists (deleted or moved meanwhile).
    LaunchedEffect(state.entries, state.isLoading) {
        if (!state.isLoading) {
            if (editingId != null && state.entries.none { it.id == editingId }) editingId = null
            if (confirmDeleteId != null && state.entries.none { it.id == confirmDeleteId }) confirmDeleteId = null
        }
    }

    state.entries.firstOrNull { it.id == editingId }?.let { entry ->
        EntryEditDialog(
            entry = entry,
            onDismiss = { editingId = null },
            onSaveAmount = { amount, newMeal -> vm.updateEntry(entry, amount, newMeal) },
            onSaveQuickKcal = { kcal, newMeal -> vm.updateQuickEntry(entry, kcal, newMeal) },
            onDelete = {
                editingId = null
                confirmDeleteId = entry.id
            },
        )
    }

    state.entries.firstOrNull { it.id == confirmDeleteId }?.let { entry ->
        ConfirmDialog(
            title = "Eintrag löschen?",
            text = "„${entry.name}“ wird aus ${meal.label} entfernt.",
            confirmLabel = "Löschen",
            onConfirm = { vm.delete(entry) },
            onDismiss = { confirmDeleteId = null },
            destructive = true,
        )
    }
}

@Composable
private fun MealSummaryCard(state: MealDetailUiState) {
    val eaten = state.total.kcal
    val target = state.targetKcal
    val over = target > 0 && eaten > target * 1.1
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatKcal(eaten),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (over) AppColors.over else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (target > 0) " / ${formatKcal(target.toDouble())} kcal" else " kcal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        if (target > 0) {
            Spacer(Modifier.height(8.dp))
            ThinProgressBar(
                progress = (eaten / target).toFloat(),
                color = if (over) AppColors.over else AppColors.kcal,
                height = 6.dp,
            )
            Spacer(Modifier.height(6.dp))
            val diff = target - eaten
            Text(
                text = if (diff >= 0.0) {
                    "Noch ${formatKcal(diff)} kcal übrig"
                } else {
                    "${formatKcal(-diff)} kcal mehr als geplant"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroProgress(
                label = "Kohlenhydrate",
                current = state.total.carbs,
                goal = state.carbsGoal,
                color = AppColors.carbs,
                modifier = Modifier.weight(1f),
            )
            MacroProgress(
                label = "Eiweiß",
                current = state.total.protein,
                goal = state.proteinGoal,
                color = AppColors.protein,
                modifier = Modifier.weight(1f),
            )
            MacroProgress(
                label = "Fett",
                current = state.total.fat,
                goal = state.fatGoal,
                color = AppColors.fat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmptyMeal(
    meal: MealType,
    copyLabel: String,
    previousDayCount: Int,
    previousDayKcal: Double,
    isCopying: Boolean,
    onAdd: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            icon = Icons.Filled.Restaurant,
            title = "Noch nichts eingetragen",
            text = "Füge Lebensmittel, Rezepte oder einen Schnelleintrag zu ${meal.label} hinzu.",
            actionLabel = "Hinzufügen",
            onAction = onAdd,
        )
        if (previousDayCount > 0) {
            OutlinedButton(onClick = onCopy, enabled = !isCopying) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(copyLabel)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${entryCountLabel(previousDayCount)} · ${formatKcal(previousDayKcal)} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEntryRow(
    entry: DiaryEntryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnDelete by rememberUpdatedState(onDelete)
    // Not saveable on purpose: an entry restored via "Rückgängig" must appear un-swiped again.
    val dismissState = remember(entry.id) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { true },
            positionalThreshold = { totalDistance -> totalDistance * 0.35f },
        )
    }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            currentOnDelete()
            // Only reached if the row is still shown (deleting failed): bring it back.
            delay(1_500)
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (active) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Löschen",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        EntryRow(entry = entry, onClick = onClick)
    }
}

@Composable
private fun EntryRow(entry: DiaryEntryEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    sourceBadgeLabel(entry)?.let { label ->
                        Spacer(Modifier.width(6.dp))
                        SourceBadge(label)
                    }
                }
                Text(
                    text = listOfNotNull(entry.brand?.takeIf { it.isNotBlank() }, amountLabel(entry)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                MacroLine(carbs = entry.carbs, protein = entry.protein, fat = entry.fat)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatKcal(entry.kcal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "kcal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceBadgeLabel(entry: DiaryEntryEntity): String? = when (entry.source) {
    EntrySource.RECIPE -> "Rezept"
    EntrySource.AI -> "KI"
    EntrySource.QUICK -> "Schnell"
    EntrySource.FOOD -> null
}

@Composable
private fun SourceBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
