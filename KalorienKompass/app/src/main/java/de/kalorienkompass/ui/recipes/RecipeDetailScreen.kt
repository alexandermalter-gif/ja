package de.kalorienkompass.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import de.kalorienkompass.data.db.IngredientWithFood
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.domain.NutritionMath
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.NumberInputDialog
import de.kalorienkompass.ui.components.SectionHeader
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Recipe with nutrition per portion and its ingredients; logs a chosen number of portions to
 * the diary ([date]/[meal] from the arguments, else today and the meal for the time of day).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(navController: NavController, recipeId: Long, date: LocalDate?, meal: MealType?) {
    val dependencies = LocalRecipeDependencies.current
    val vm: RecipeDetailViewModel = viewModel(
        key = "recipe_detail_$recipeId",
        factory = viewModelFactory {
            initializer {
                val deps = recipeDependencies(dependencies)
                RecipeDetailViewModel(deps.recipes, deps.diary, recipeId, date, meal)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showPortionsDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is RecipeDetailEvent.Added -> {
                    // Opened from the food search of a meal: back to the meal/diary, like foods do.
                    val returned = navController.popBackStack(Routes.FOOD_SEARCH, inclusive = true)
                    if (!returned) {
                        scope.launch {
                            val result = snackbarHostState.showMessage(event.message, actionLabel = "Rückgängig")
                            if (result == SnackbarResult.ActionPerformed) vm.undoAdd(event.entryId)
                        }
                    }
                }
                is RecipeDetailEvent.Message -> scope.launch { snackbarHostState.showMessage(event.text) }
            }
        }
    }

    val content = state as? RecipeDetailUiState.Content
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Rezept") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (content != null) {
                        val favorite = content.recipe.recipe.isFavorite
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                                tint = if (favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                navController.navigateIfResumed(lifecycleOwner, Routes.recipeEdit(content.recipe.recipe.id))
                            },
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rezept bearbeiten")
                        }
                    }
                },
                colors = recipeTopBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when (val s = state) {
            RecipeDetailUiState.Loading -> LoadingBox(contentModifier)
            RecipeDetailUiState.NotFound -> Box(contentModifier) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Rezept nicht gefunden",
                    text = "Dieses Rezept gibt es nicht mehr. Vielleicht wurde es gelöscht.",
                    actionLabel = "Zurück",
                    onAction = { navController.popBackStack() },
                )
            }
            RecipeDetailUiState.Error -> Box(contentModifier) {
                EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    title = "Fehler beim Laden",
                    text = "Das Rezept konnte nicht geladen werden.",
                    actionLabel = "Zurück",
                    onAction = { navController.popBackStack() },
                )
            }
            is RecipeDetailUiState.Content -> RecipeDetailContent(
                state = s,
                modifier = contentModifier,
                onDecreasePortions = vm::decreasePortions,
                onIncreasePortions = vm::increasePortions,
                onPortionsClick = { showPortionsDialog = true },
                onMealSelect = vm::setMeal,
                onDateClick = { showDatePicker = true },
                onAdd = vm::addToDiary,
                onEdit = {
                    navController.navigateIfResumed(lifecycleOwner, Routes.recipeEdit(s.recipe.recipe.id))
                },
            )
        }
    }

    if (showPortionsDialog && content != null) {
        NumberInputDialog(
            title = "Portionen eintragen",
            initial = content.selection.portions,
            suffix = "Portionen",
            onConfirm = vm::setPortions,
            onDismiss = { showPortionsDialog = false },
        )
    }

    if (showDatePicker && content != null) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = content.selection.date.toEpochDay() * MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            vm.setDate(LocalDate.ofEpochDay(Math.floorDiv(millis, MILLIS_PER_DAY)))
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun RecipeDetailContent(
    state: RecipeDetailUiState.Content,
    modifier: Modifier,
    onDecreasePortions: () -> Unit,
    onIncreasePortions: () -> Unit,
    onPortionsClick: () -> Unit,
    onMealSelect: (MealType) -> Unit,
    onDateClick: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
) {
    val recipe = state.recipe.recipe
    val nutrition = state.nutrition
    val portions = recipe.portions.coerceAtLeast(MIN_RECIPE_PORTIONS)
    val ingredientCount = state.recipe.ingredients.size
    val selection = state.selection

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- header + nutrition per portion ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("Ergibt ").append(portionsLabel(portions))
                    append(" · ").append(ingredientsLabel(ingredientCount))
                    if (nutrition.totalGrams > 0.0) {
                        append(" · ").append(formatGrams(nutrition.totalGrams)).append(" g")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatKcal(nutrition.perPortion.kcal),
                    style = MaterialTheme.typography.displaySmall,
                    color = AppColors.kcal,
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = "kcal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "pro Portion",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = "Ganzes Rezept",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${formatKcal(nutrition.total.kcal)} kcal",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            MacroTilesRow(nutrition.perPortion)
            recipe.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Row {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // ---- log to diary ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Zum Tagebuch hinzufügen",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Portionen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${formatKcal(state.logNutrition.kcal)} kcal",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.kcal,
                    )
                }
                PortionStepper(
                    value = formatPortions(selection.portions),
                    onDecrease = onDecreasePortions,
                    onIncrease = onIncreasePortions,
                    decreaseEnabled = canDecreaseLogPortions(selection.portions),
                    increaseEnabled = canIncreaseLogPortions(selection.portions),
                    onValueClick = onPortionsClick,
                )
            }
            Spacer(Modifier.height(4.dp))
            MacroLine(state.logNutrition)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Mahlzeit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            MealChips(selected = selection.meal, onSelect = onMealSelect, enabled = !state.isAdding)
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = onDateClick,
                enabled = !state.isAdding,
                label = { Text(dayLabel(selection.date)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAdd,
                enabled = state.canLog && !state.isAdding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isAdding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Zum Tagebuch hinzufügen", fontWeight = FontWeight.Bold)
            }
            if (!state.canLog) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Dieses Rezept hat keine Zutaten. Bearbeite es, um Zutaten hinzuzufügen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---- ingredients ----
        SectionHeader(
            title = "Zutaten",
            actionLabel = "Bearbeiten",
            onAction = onEdit,
        )
        if (state.recipe.ingredients.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Noch keine Zutaten eingetragen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                state.recipe.ingredients.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    IngredientLine(item)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Mengen für das ganze Rezept (${portionsLabel(portions)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun IngredientLine(item: IngredientWithFood) {
    val food = item.food
    val grams = item.ingredient.grams
    if (food == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Gelöschtes Lebensmittel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "${formatGrams(grams)} g · wird nicht mitgerechnet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val n = NutritionMath.forGrams(food, grams)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                text = food.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    food.brand?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    append(formatGrams(grams)).append(' ').append(food.unit.ifBlank { "g" })
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${formatKcal(n.kcal)} kcal",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
