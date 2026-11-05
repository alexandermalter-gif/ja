package de.kalorienkompass.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.NumberInputDialog
import de.kalorienkompass.ui.components.SectionHeader
import de.kalorienkompass.ui.navigation.NavResults
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Create (recipeId == null) or edit a recipe: name, portions, note and ingredients picked via
 * the food picker (pick-result protocol, spec §9). The draft lives in [RecipeEditViewModel] and
 * therefore survives the trip to the picker.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditScreen(navController: NavController, recipeId: Long?) {
    val dependencies = LocalRecipeDependencies.current
    val vm: RecipeEditViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val deps = recipeDependencies(dependencies)
                RecipeEditViewModel(
                    recipeRepository = deps.recipes,
                    foodRepository = deps.foods,
                    savedState = createSavedStateHandle(),
                    recipeId = recipeId,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    ConsumePickedFood(navController = navController, onPicked = vm::addPickedIngredient)

    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showPortionsDialog by rememberSaveable { mutableStateOf(false) }
    var amountDialogKey by rememberSaveable { mutableStateOf<Long?>(null) }

    val guardUnsavedChanges = state.hasChanges && !state.isBusy
    val close: () -> Unit = {
        if (guardUnsavedChanges) showDiscardDialog = true else navController.popBackStack()
    }
    BackHandler(enabled = guardUnsavedChanges) { showDiscardDialog = true }

    val openPicker: () -> Unit = {
        navController.navigateIfResumed(lifecycleOwner, Routes.foodPick())
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is RecipeEditEvent.Saved -> navigateAfterSave(navController, event)
                RecipeEditEvent.Deleted -> navigateAfterDelete(navController)
                is RecipeEditEvent.Message -> scope.launch { snackbarHostState.showMessage(event.text) }
                is RecipeEditEvent.IngredientRemoved -> scope.launch {
                    val name = event.ingredient.food?.name ?: "Zutat"
                    val result = snackbarHostState.showMessage("„$name“ entfernt", actionLabel = "Rückgängig")
                    if (result == SnackbarResult.ActionPerformed) {
                        vm.restoreIngredient(event.index, event.ingredient)
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isNew) "Neues Rezept" else "Rezept bearbeiten",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (state.canDelete) {
                        IconButton(onClick = { showDeleteDialog = true }, enabled = !state.isBusy) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Rezept löschen")
                        }
                    }
                    if (state.isEditable) {
                        TextButton(onClick = vm::save, enabled = !state.isBusy) {
                            Text("Speichern")
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
        when {
            state.isLoading -> LoadingBox(contentModifier)
            state.notFound -> Box(contentModifier) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Rezept nicht gefunden",
                    text = "Dieses Rezept gibt es nicht mehr. Vielleicht wurde es gelöscht.",
                    actionLabel = "Zurück",
                    onAction = { navController.popBackStack() },
                )
            }
            state.loadError != null -> Box(contentModifier) {
                EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    title = "Fehler beim Laden",
                    text = state.loadError.orEmpty(),
                    actionLabel = "Erneut versuchen",
                    onAction = vm::retryLoad,
                )
            }
            else -> RecipeEditContent(
                state = state,
                modifier = contentModifier,
                onNameChange = vm::setName,
                onNoteChange = vm::setNote,
                onDecreasePortions = vm::decreasePortions,
                onIncreasePortions = vm::increasePortions,
                onPortionsClick = { showPortionsDialog = true },
                onAmountClick = { key -> amountDialogKey = key },
                onRemoveIngredient = vm::removeIngredient,
                onAddIngredient = openPicker,
                onSave = vm::save,
                onDelete = { showDeleteDialog = true },
            )
        }
    }

    if (showPortionsDialog) {
        NumberInputDialog(
            title = "Portionen des Rezepts",
            initial = state.draft.portions.toDouble(),
            suffix = "Portionen",
            decimals = false,
            onConfirm = { vm.setPortions(it.roundToInt()) },
            onDismiss = { showPortionsDialog = false },
        )
    }

    amountDialogKey?.let { key ->
        val item = state.draft.ingredients.firstOrNull { it.key == key }
        if (item != null) {
            NumberInputDialog(
                title = item.food?.name ?: "Menge",
                initial = item.grams,
                suffix = item.unit,
                onConfirm = { vm.setIngredientAmount(key, it) },
                onDismiss = { amountDialogKey = null },
            )
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Rezept löschen?",
            text = "„${state.draft.name.ifBlank { "Rezept" }}“ wird endgültig gelöscht. " +
                "Bereits eingetragene Mahlzeiten im Tagebuch bleiben erhalten.",
            confirmLabel = "Löschen",
            destructive = true,
            onConfirm = vm::delete,
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "Änderungen verwerfen?",
            text = "Deine Änderungen an diesem Rezept wurden noch nicht gespeichert.",
            confirmLabel = "Verwerfen",
            destructive = true,
            onConfirm = { navController.popBackStack() },
            onDismiss = { showDiscardDialog = false },
        )
    }
}

/**
 * Pick-result protocol (spec §9): the food picker writes [NavResults.PICKED_FOOD_ID] and
 * [NavResults.PICKED_GRAMS] into this screen's back stack entry and pops back to it.
 * The values are consumed once and then cleared.
 */
@Composable
private fun ConsumePickedFood(navController: NavController, onPicked: (Long, Double?) -> Unit) {
    val owner = LocalViewModelStoreOwner.current
    val entry = remember(owner, navController) {
        owner as? NavBackStackEntry ?: navController.currentBackStackEntry
    } ?: return
    LaunchedEffect(entry) {
        val handle = entry.savedStateHandle
        handle.getStateFlow<Any?>(NavResults.PICKED_FOOD_ID, null).collect { raw ->
            if (raw == null) return@collect
            val foodId = parsePickedFoodId(raw)
            val grams = parsePickedGrams(handle.get<Any?>(NavResults.PICKED_GRAMS))
            handle[NavResults.PICKED_FOOD_ID] = null
            handle[NavResults.PICKED_GRAMS] = null
            if (foodId != null) onPicked(foodId, grams)
        }
    }
}

/** New recipe → its detail page (keeping the diary context of the food search); edit → back. */
private fun navigateAfterSave(navController: NavController, event: RecipeEditEvent.Saved) {
    if (!event.wasNew) {
        navController.popBackStack()
        return
    }
    val (date, meal) = navController.previousBackStackEntry.diaryContext()
    navController.navigate(Routes.recipeDetail(event.recipeId, date, meal)) {
        popUpTo(Routes.RECIPE_EDIT) { inclusive = true }
    }
}

/** After deleting, the (now empty) detail page below the editor is closed as well. */
private fun navigateAfterDelete(navController: NavController) {
    val previousRoute = navController.previousBackStackEntry?.destination?.route
    val popped = previousRoute == Routes.RECIPE_DETAIL &&
        navController.popBackStack(Routes.RECIPE_DETAIL, inclusive = true)
    if (!popped) navController.popBackStack()
}

/** Date/meal of the food search the editor was opened from (if any). */
private fun NavBackStackEntry?.diaryContext(): Pair<LocalDate?, MealType?> {
    val entry = this ?: return null to null
    if (entry.destination.route != Routes.FOOD_SEARCH) return null to null
    val args = entry.arguments ?: return null to null
    val epochDay = args.getLong("date", -1L)
    val date = if (epochDay == -1L) null else LocalDate.ofEpochDay(epochDay)
    return date to MealType.fromName(args.getString("meal"))
}

@Composable
private fun RecipeEditContent(
    state: RecipeEditUiState,
    modifier: Modifier,
    onNameChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDecreasePortions: () -> Unit,
    onIncreasePortions: () -> Unit,
    onPortionsClick: () -> Unit,
    onAmountClick: (Long) -> Unit,
    onRemoveIngredient: (Long) -> Unit,
    onAddIngredient: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val draft = state.draft
    val enabled = !state.isBusy
    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text("Name des Rezepts") },
                placeholder = { Text("z. B. Linsen-Dal") },
                singleLine = true,
                isError = state.showNameError,
                supportingText = if (state.showNameError) {
                    { Text("Bitte gib einen Namen ein.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Portionen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "So viele Portionen ergibt das Rezept",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PortionStepper(
                    value = draft.portions.toString(),
                    onDecrease = onDecreasePortions,
                    onIncrease = onIncreasePortions,
                    decreaseEnabled = enabled && draft.portions > MIN_RECIPE_PORTIONS,
                    increaseEnabled = enabled && draft.portions < MAX_RECIPE_PORTIONS,
                    onValueClick = if (enabled) onPortionsClick else null,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = draft.note,
                onValueChange = onNoteChange,
                label = { Text("Notiz (optional)") },
                placeholder = { Text("z. B. Zubereitung oder Garzeit") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionHeader(
            title = if (draft.ingredients.isEmpty()) "Zutaten" else "Zutaten (${draft.ingredients.size})",
        )
        if (draft.ingredients.isEmpty()) {
            EmptyIngredientsCard(showError = state.showIngredientsError)
        } else {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                draft.ingredients.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    EditableIngredientRow(
                        item = item,
                        enabled = enabled,
                        onAmountClick = { onAmountClick(item.key) },
                        onRemove = { onRemoveIngredient(item.key) },
                    )
                }
            }
        }
        FilledTonalButton(
            onClick = onAddIngredient,
            enabled = enabled && !state.isAddingIngredient,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (state.isAddingIngredient) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Zutat hinzufügen")
        }

        SectionHeader(title = "Nährwerte")
        NutritionSummaryCard(state)

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Rezept speichern", fontWeight = FontWeight.Bold)
        }
        if (state.canDelete) {
            OutlinedButton(
                onClick = onDelete,
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rezept löschen")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EmptyIngredientsCard(showError: Boolean) {
    val borderColor = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    BorderedCard(border = BorderStroke(1.dp, borderColor)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Kitchen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Noch keine Zutaten",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Suche Lebensmittel, scanne einen Barcode oder lege ein eigenes Lebensmittel an.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Like [AppCard], but with a custom border color (red when the ingredient list is required). */
@Composable
private fun BorderedCard(border: BorderStroke, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = border,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun EditableIngredientRow(
    item: IngredientDraft,
    enabled: Boolean,
    onAmountClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val food = item.food
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = food?.name ?: "Gelöschtes Lebensmittel",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (food == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (food == null) {
                Text(
                    text = "Nicht mehr vorhanden – wird beim Speichern entfernt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val n = item.nutrition
                Text(
                    text = buildString {
                        food.brand?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                        append(formatKcal(n.kcal)).append(" kcal")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                MacroLine(n)
            }
        }
        Spacer(Modifier.width(8.dp))
        AmountPill(
            text = "${formatGrams(item.grams)} ${item.unit}",
            onClick = onAmountClick,
            enabled = enabled && food != null,
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Zutat entfernen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NutritionSummaryCard(state: RecipeEditUiState) {
    val nutrition = state.nutrition
    val portions = state.draft.portions.coerceAtLeast(MIN_RECIPE_PORTIONS)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Pro Portion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatKcal(nutrition.perPortion.kcal),
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.kcal,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "kcal",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Gesamt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatKcal(nutrition.total.kcal)} kcal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        MacroTilesRow(nutrition.perPortion)
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (nutrition.totalGrams > 0.0) {
                "Gesamtmenge ${formatGrams(nutrition.totalGrams)} g · " +
                    "ca. ${formatGrams(nutrition.totalGrams / portions)} g pro Portion (${portionsLabel(portions)})"
            } else {
                "Füge Zutaten hinzu, um die Nährwerte zu berechnen."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.draft.missingIngredientCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Gelöschte Lebensmittel werden nicht mitgerechnet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
