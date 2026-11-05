package de.kalorienkompass.ui.food

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.domain.NutritionMath
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.DecimalTextField
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.navigation.NavResults
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Food details: amount + unit, live nutrition, table per 100 g/ml, favorite, add to the diary
 * (or, in [pickMode], return the food as a recipe ingredient – see spec §9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodDetailScreen(navController: NavController, foodId: Long, date: LocalDate?, meal: MealType?, pickMode: Boolean) {
    val vm: FoodDetailViewModel = viewModel(
        key = "food_detail_${foodId}_${date?.toEpochDay()}_${meal?.name}_$pickMode",
        factory = appViewModelFactory { FoodDetailViewModel(foodId, date, meal, it.foodRepository, it.diaryRepository) },
    )
    val load by vm.food.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /** Opened from the Lebensmittel tab (no date/meal): choose meal and date here. */
    val freeMode = !pickMode && (date == null || meal == null)
    val food = (load as? FoodDetailLoad.Loaded)?.food

    val options = remember(food) { food?.let { amountUnitOptions(it) } ?: emptyList() }
    val option = options.firstOrNull { it.key == vm.unitKey } ?: options.firstOrNull()
    val amount = parseAmount(vm.amountText)
    val grams = if (amount != null && option != null) amount * option.gramsPerUnit else null

    val onPrimaryAction: () -> Unit = action@{
        val f = food ?: return@action
        val o = option ?: return@action
        val a = amount ?: return@action
        if (pickMode) {
            returnPickedFood(navController, f.id, a * o.gramsPerUnit)
        } else {
            vm.addToDiary(f, a, o) { ok ->
                if (ok) {
                    val popped = navController.popBackStack(Routes.FOOD_SEARCH, inclusive = true)
                    if (!popped) navController.popBackStack()
                    val now = navController.currentBackStackEntry?.destination?.route
                    if (now != Routes.MEAL && now != Routes.DIARY) {
                        Toast.makeText(
                            context,
                            "„${f.name}“ zu ${vm.meal.label} (${dayLabel(vm.date)}) hinzugefügt",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("Speichern fehlgeschlagen. Bitte versuche es erneut.") }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (pickMode) "Zutat" else "Lebensmittel") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (food != null) {
                        IconButton(onClick = { vm.toggleFavorite(food) }) {
                            Icon(
                                imageVector = if (food.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (food.isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
                                tint = if (food.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (food.source == FoodSource.CUSTOM || food.source == FoodSource.AI) {
                            IconButton(
                                onClick = {
                                    navController.navigate(Routes.customFood(foodId = food.id, date = date, meal = meal, pick = pickMode))
                                },
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (food != null) {
                BottomActionBar(
                    label = when {
                        pickMode -> "Zum Rezept hinzufügen"
                        freeMode -> "Zum Tagebuch hinzufügen"
                        else -> "Hinzufügen"
                    },
                    detail = grams?.let { "${formatKcal(NutritionMath.forGrams(food, it).kcal)} kcal" },
                    enabled = grams != null && !vm.saving,
                    loading = vm.saving,
                    icon = if (pickMode) Icons.AutoMirrored.Filled.PlaylistAdd else Icons.Filled.Add,
                    onClick = onPrimaryAction,
                )
            }
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when (val state = load) {
            FoodDetailLoad.Loading -> LoadingBox(modifier = contentModifier)
            FoodDetailLoad.NotFound -> Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Lebensmittel nicht gefunden",
                    text = "Es wurde vielleicht gerade gelöscht.",
                    actionLabel = "Zurück",
                    onAction = { navController.popBackStack() },
                )
            }
            is FoodDetailLoad.Loaded -> FoodDetailContent(
                food = state.food,
                options = options,
                option = option,
                amountText = vm.amountText,
                grams = grams,
                pickMode = pickMode,
                freeMode = freeMode,
                meal = vm.meal,
                date = vm.date,
                onAmountChange = vm::onAmountChange,
                onUnitSelected = { vm.onUnitSelected(it, grams) },
                onMealSelected = vm::onMealSelected,
                onDateSelected = vm::onDateSelected,
                onDone = onPrimaryAction,
                modifier = contentModifier,
            )
        }
    }
}

/** Pick-mode result protocol (spec §9): hand food id + grams to RecipeEdit and go back there. */
private fun returnPickedFood(navController: NavController, foodId: Long, grams: Double) {
    val target = runCatching { navController.getBackStackEntry(Routes.RECIPE_EDIT) }.getOrNull()
    if (target == null) {
        navController.popBackStack()
        return
    }
    target.savedStateHandle[NavResults.PICKED_GRAMS] = grams
    target.savedStateHandle[NavResults.PICKED_FOOD_ID] = foodId
    if (!navController.popBackStack(Routes.RECIPE_EDIT, inclusive = false)) navController.popBackStack()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodDetailContent(
    food: FoodEntity,
    options: List<AmountUnitOption>,
    option: AmountUnitOption?,
    amountText: String,
    grams: Double?,
    pickMode: Boolean,
    freeMode: Boolean,
    meal: MealType,
    date: LocalDate,
    onAmountChange: (String) -> Unit,
    onUnitSelected: (AmountUnitOption) -> Unit,
    onMealSelected: (MealType) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FoodHeader(food)

        // ---- amount ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text("Menge", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DecimalTextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    label = "Anzahl",
                    imeAction = ImeAction.Done,
                    onImeAction = { if (grams != null) onDone() },
                    modifier = Modifier.weight(0.8f),
                )
                Spacer(Modifier.width(10.dp))
                if (option != null) {
                    UnitDropdown(
                        options = options,
                        selected = option,
                        onSelect = onUnitSelected,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
            if (option != null) {
                Spacer(Modifier.height(10.dp))
                AmountSuggestions(option = option, onPick = onAmountChange)
            }
            if (option != null && !option.isBase && grams != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "= ${formatGrams(grams)} ${food.unitSymbol}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (grams == null && amountText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Bitte gib eine Menge größer als 0 ein.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---- meal / date ----
        if (!pickMode) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mahlzeit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (freeMode) {
                        AssistChip(
                            onClick = { showDatePicker = true },
                            label = { Text(dayLabel(date)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.CalendarToday,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        )
                    } else {
                        Text(
                            text = dayLabel(date),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MealChipsRow(selected = meal, onSelect = onMealSelected)
            }
        }

        // ---- nutrition for the chosen amount ----
        AmountNutritionCard(food = food, grams = grams)

        // ---- table per 100 g ----
        NutritionTableCard(food = food, grams = grams)

        SourceFooter(food)
        Spacer(Modifier.height(8.dp))
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showDatePicker = false
                    },
                ) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Abbrechen") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun FoodHeader(food: FoodEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val imageUrl = food.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            // Open Food Facts: "...front_de.12.200.jpg" also exists in 400 px; fall back to the original URL.
            var model by remember(imageUrl) { mutableStateOf(imageUrl.replace(".200.jpg", ".400.jpg")) }
            AsyncImage(
                model = model,
                contentDescription = "Produktfoto",
                contentScale = ContentScale.Crop,
                onError = { if (model != imageUrl) model = imageUrl },
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White),
            )
            Spacer(Modifier.width(14.dp))
        } else {
            val emoji = categoryEmoji(food.category)
            if (emoji != null) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.width(14.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = food.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val brand = food.brand?.takeIf { it.isNotBlank() }
            if (brand != null) {
                Text(
                    text = brand,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(food.source)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = per100Label(food),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(
    options: List<AmountUnitOption>,
    selected: AmountUnitOption,
    onSelect: (AmountUnitOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it && options.size > 1 },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Einheit") },
            trailingIcon = { if (options.size > 1) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.label,
                            fontWeight = if (item.key == selected.key) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Quick amount chips: 50/100/150/200/250 g, or ½/1/1½/2 portions. */
@Composable
private fun AmountSuggestions(option: AmountUnitOption, onPick: (String) -> Unit) {
    val values: List<Pair<String, String>> = if (option.isBase) {
        listOf("50", "100", "150", "200", "250").map { it to it }
    } else {
        listOf("½" to "0,5", "1" to "1", "1½" to "1,5", "2" to "2", "3" to "3")
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { (label, value) ->
            SuggestionChip(
                onClick = { onPick(value) },
                label = { Text(if (option.isBase) "$label ${option.diaryLabel}" else "$label ×") },
                shape = RoundedCornerShape(10.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun AmountNutritionCard(food: FoodEntity, grams: Double?) {
    val nutrition = grams?.let { NutritionMath.forGrams(food, it) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (grams != null) "Nährwerte für ${formatGrams(grams)} ${food.unitSymbol}" else "Nährwerte",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = nutrition?.let { formatKcal(it.kcal) } ?: "–",
                style = MaterialTheme.typography.displaySmall,
                color = AppColors.kcal,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "kcal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroTile("Kohlenhydrate", nutrition?.carbs, AppColors.carbs, Modifier.weight(1f))
            MacroTile("Eiweiß", nutrition?.protein, AppColors.protein, Modifier.weight(1f))
            MacroTile("Fett", nutrition?.fat, AppColors.fat, Modifier.weight(1f))
        }
        EnergySplit(food)
    }
}

@Composable
private fun MacroTile(label: String, grams: Double?, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(color)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = grams?.let { "${formatGrams(it)} g" } ?: "–",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Share of the energy from carbohydrates, protein and fat (stacked bar + legend). */
@Composable
private fun EnergySplit(food: FoodEntity) {
    val carbs = food.carbs * 4.0
    val protein = food.protein * 4.0
    val fat = food.fat * 9.0
    val total = carbs + protein + fat
    if (total <= 0.0) return
    val parts = listOf(
        Triple("KH", carbs, AppColors.carbs),
        Triple("Eiweiß", protein, AppColors.protein),
        Triple("Fett", fat, AppColors.fat),
    ).filter { it.second > 0.0 }

    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { (_, value, color) ->
            Box(
                modifier = Modifier
                    .weight((value / total).toFloat().coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Energieanteile: " + parts.joinToString(" · ") { (label, value, _) ->
            "$label ${(value / total * 100).roundToInt()} %"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NutritionTableCard(food: FoodEntity, grams: Double?) {
    val unit = food.unitSymbol
    val factor = grams?.let { it / 100.0 }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("Nährwerte pro 100 $unit", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        TableRow(
            label = "",
            per100 = "100 $unit",
            perAmount = grams?.let { "${formatGrams(it)} $unit" },
            header = true,
        )
        TableDivider()
        TableRow(
            label = "Energie",
            per100 = "${formatKcal(food.kcal)} kcal",
            perAmount = factor?.let { "${formatKcal(food.kcal * it)} kcal" },
            emphasize = true,
        )
        TableRow(
            label = "",
            per100 = "${formatKcal(food.kcal * KJ_PER_KCAL)} kJ",
            perAmount = factor?.let { "${formatKcal(food.kcal * KJ_PER_KCAL * it)} kJ" },
            subtle = true,
        )
        TableDivider()
        NutrientRow("Fett", food.fat, factor)
        TableDivider()
        NutrientRow("Kohlenhydrate", food.carbs, factor)
        NutrientRow("davon Zucker", food.sugar, factor, indent = true)
        TableDivider()
        NutrientRow("Ballaststoffe", food.fiber, factor)
        TableDivider()
        NutrientRow("Eiweiß", food.protein, factor)
        TableDivider()
        NutrientRow("Salz", food.salt, factor)
    }
}

private const val KJ_PER_KCAL = 4.184

@Composable
private fun NutrientRow(label: String, per100: Double?, factor: Double?, indent: Boolean = false) {
    TableRow(
        label = label,
        per100 = per100?.let { "${formatNutrient(it)} g" } ?: "–",
        perAmount = if (factor == null) null else per100?.let { "${formatNutrient(it * factor)} g" } ?: "–",
        indent = indent,
    )
}

@Composable
private fun TableRow(
    label: String,
    per100: String,
    perAmount: String?,
    header: Boolean = false,
    emphasize: Boolean = false,
    subtle: Boolean = false,
    indent: Boolean = false,
) {
    val style = when {
        header -> MaterialTheme.typography.labelMedium
        subtle -> MaterialTheme.typography.bodySmall
        else -> MaterialTheme.typography.bodyMedium
    }
    val color = if (header || subtle || indent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val weight = if (emphasize) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (subtle) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = style,
            color = color,
            fontWeight = weight,
            modifier = Modifier
                .weight(1.3f)
                .padding(start = if (indent) 14.dp else 0.dp),
        )
        Text(
            text = per100,
            style = style,
            color = color,
            fontWeight = weight,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        if (perAmount != null) {
            Text(
                text = perAmount,
                style = style,
                color = if (header) MaterialTheme.colorScheme.primary else color,
                fontWeight = weight,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TableDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
}

@Composable
private fun SourceFooter(food: FoodEntity) {
    val source = when (food.source) {
        FoodSource.OPEN_FOOD_FACTS -> "Quelle: Open Food Facts (Daten unter ODbL-Lizenz). Angaben können unvollständig sein."
        FoodSource.BUILTIN -> "Quelle: Basis-Datenbank (Durchschnittswerte)."
        FoodSource.CUSTOM -> "Von dir angelegt."
        FoodSource.AI -> "Von der KI geschätzt – die Werte können abweichen."
    }
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val barcode = food.barcode
        if (!barcode.isNullOrBlank()) {
            Text("Barcode: $barcode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Primary action pinned above the navigation bar and the keyboard. */
@Composable
internal fun BottomActionBar(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    if (detail != null) {
                        Text(
                            text = "  ·  $detail",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
