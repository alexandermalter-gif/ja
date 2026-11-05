package de.kalorienkompass.ui.ai

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.kalorienkompass.ai.FoodPhotoRequests
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.DecimalTextField
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.SectionHeader
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- Review -------------------------------------------------------------------------------------

@Composable
internal fun ReviewContent(
    state: AiPhotoUiState.Review,
    selectedMeal: MealType,
    model: AiModel,
    onMealChange: (MealType) -> Unit,
    onAmountChange: (Long, String) -> Unit,
    onSelectFood: (Long, FoodEntity?) -> Unit,
    onSearchFood: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onRetakePhoto: () -> Unit,
    onManualSearch: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "photo") {
            PhotoHeader(photo = state.photo, mealName = state.mealName)
        }
        if (state.notes.isNotBlank()) {
            item(key = "notes") { NotesCard(state.notes) }
        }
        if (state.items.isEmpty()) {
            item(key = "empty") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Filled.Restaurant,
                        title = "Keine Lebensmittel erkannt",
                        text = "Die KI hat auf dem Foto nichts Essbares gefunden. Versuche es mit einem anderen " +
                            "Foto oder suche die Lebensmittel selbst.",
                        actionLabel = "Neues Foto",
                        onAction = onRetakePhoto,
                    )
                    TextButton(onClick = onManualSearch, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Lebensmittel selbst suchen")
                    }
                }
            }
        } else {
            item(key = "totals") { TotalsCard(total = state.total, itemCount = state.items.size) }
            item(key = "meal") { MealChips(selected = selectedMeal, enabled = !state.saving, onSelect = onMealChange) }
            item(key = "header") {
                SectionHeader(title = "Erkannte Lebensmittel")
            }
            items(state.items, key = { it.key }) { item ->
                ReviewItemCard(
                    item = item,
                    enabled = !state.saving,
                    onAmountChange = { onAmountChange(item.key, it) },
                    onSelectFood = { onSelectFood(item.key, it) },
                    onSearch = { onSearchFood(item.key) },
                    onRemove = { onRemove(item.key) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "footer") {
            Text(
                text = "Mengen und Nährwerte sind Schätzungen (${model.shortName}). Bitte prüfe sie vor dem " +
                    "Hinzufügen. Bei Zuordnung zu einem Lebensmittel gelten dessen Nährwerte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PhotoHeader(photo: Bitmap?, mealName: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (photo != null) {
            val image = remember(photo) { photo.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Dein Foto",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                text = "Von der KI erkannt",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Text(
                text = mealName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotesCard(notes: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TotalsCard(total: Nutrition, itemCount: Int) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Gesamt",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatKcal(total.kcal),
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
            }
            Text(
                text = "$itemCount Lebensmittel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroTile("Kohlenhydrate", total.carbs, AppColors.carbs, Modifier.weight(1f))
            MacroTile("Eiweiß", total.protein, AppColors.protein, Modifier.weight(1f))
            MacroTile("Fett", total.fat, AppColors.fat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MacroTile(label: String, grams: Double, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${formatGrams(grams)} g",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealChips(selected: MealType, enabled: Boolean, onSelect: (MealType) -> Unit) {
    Column {
        Text(
            text = "Hinzufügen zu",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MealType.entries.forEach { meal ->
                FilterChip(
                    selected = meal == selected,
                    onClick = { onSelect(meal) },
                    enabled = enabled,
                    label = { Text("${meal.emoji} ${meal.label}") },
                )
            }
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: ReviewItem,
    enabled: Boolean,
    onAmountChange: (String) -> Unit,
    onSelectFood: (FoodEntity?) -> Unit,
    onSearch: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
                Text(
                    text = item.ai.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                ConfidenceChip(item.ai.confidence)
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "${item.ai.name} entfernen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DecimalTextField(
                value = item.amountText,
                onValueChange = onAmountChange,
                label = "Menge",
                suffix = item.unit,
                imeAction = ImeAction.Done,
                onImeAction = { focusManager.clearFocus() },
                modifier = Modifier.width(136.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${formatKcal(item.nutrition.kcal)} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MacroLine(item.nutrition)
            }
        }
        if (item.amount == null) {
            Text(
                text = "Bitte eine Menge eingeben – sonst wird dieses Lebensmittel nicht hinzugefügt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (item.usesAiEstimate && item.ai.kcalPer100g <= 0.0) {
            Text(
                text = "Die KI schätzt 0 kcal – stimmt das? Sonst bitte unten ein Lebensmittel zuordnen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        FoodSelector(item = item, enabled = enabled, onSelect = onSelectFood, onSearch = onSearch)
    }
}

@Composable
private fun MacroLine(n: Nutrition) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = AppColors.carbs, fontWeight = FontWeight.SemiBold)) { append("K ") }
            withStyle(SpanStyle(color = muted)) { append("${formatGrams(n.carbs)} g   ") }
            withStyle(SpanStyle(color = AppColors.protein, fontWeight = FontWeight.SemiBold)) { append("E ") }
            withStyle(SpanStyle(color = muted)) { append("${formatGrams(n.protein)} g   ") }
            withStyle(SpanStyle(color = AppColors.fat, fontWeight = FontWeight.SemiBold)) { append("F ") }
            withStyle(SpanStyle(color = muted)) { append("${formatGrams(n.fat)} g") }
        },
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

@Composable
private fun ConfidenceChip(confidence: String) {
    val (label, color) = when (confidence) {
        FoodPhotoRequests.CONFIDENCE_HIGH -> "Sicher erkannt" to MaterialTheme.colorScheme.primary
        FoodPhotoRequests.CONFIDENCE_LOW -> "Unsicher" to MaterialTheme.colorScheme.error
        else -> "Teilweise sicher" to MaterialTheme.colorScheme.secondary
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/** Shows the current assignment (database food or AI estimate) with a dropdown of alternatives. */
@Composable
private fun FoodSelector(
    item: ReviewItem,
    enabled: Boolean,
    onSelect: (FoodEntity?) -> Unit,
    onSearch: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = item.selectedFood
    val accent = if (selected == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Box {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = enabled) { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (selected == null) Icons.Filled.AutoAwesome else Icons.Filled.Link,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selected == null) "KI-Schätzung" else "Zugeordnet: ${selected.source.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                    Text(
                        text = selected?.displayName() ?: "Nährwerte von der KI geschätzt",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatKcal(item.per100.kcal)} kcal / 100 ${item.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Zuordnung ändern",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp),
        ) {
            if (item.candidates.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Keine passenden Lebensmittel in der Datenbank") },
                    onClick = {},
                    enabled = false,
                )
            }
            item.candidates.forEach { candidate ->
                val food = candidate.food
                val isSelected = selected?.id == food.id
                DropdownMenuItem(
                    text = { FoodMenuText(food) },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(Icons.Filled.Check, contentDescription = "Ausgewählt", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(food)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text("KI-Schätzung verwenden")
                        Text(
                            text = "${formatKcal(item.ai.kcalPer100g)} kcal / 100 g",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    if (selected == null) {
                        Icon(Icons.Filled.Check, contentDescription = "Ausgewählt", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            DropdownMenuItem(
                text = { Text("Anderes Lebensmittel suchen…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSearch()
                },
            )
        }
    }
}

@Composable
private fun FoodMenuText(food: FoodEntity) {
    Column {
        Text(text = food.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = foodDetails(food),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun FoodEntity.displayName(): String =
    if (brand.isNullOrBlank()) name else "$name ($brand)"

private fun foodDetails(food: FoodEntity): String = buildString {
    if (!food.brand.isNullOrBlank()) append(food.brand).append(" · ")
    append(formatKcal(food.kcal)).append(" kcal / 100 ").append(food.unit.ifBlank { "g" })
    append(" · ").append(food.source.label)
}

@Composable
internal fun ReviewBottomBar(state: AiPhotoUiState.Review, onAddAll: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val count = state.addableCount
            val label = when {
                count == 0 -> "Menge fehlt"
                count == state.items.size -> "Alle hinzufügen · ${formatKcal(state.total.kcal)} kcal"
                else -> "$count hinzufügen · ${formatKcal(state.total.kcal)} kcal"
            }
            Button(
                onClick = onAddAll,
                enabled = !state.saving && count > 0,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --- "Anderes Lebensmittel suchen" --------------------------------------------------------------

private sealed interface RemoteSearchState {
    data object Idle : RemoteSearchState
    data object Loading : RemoteSearchState
    data class Loaded(val foods: List<FoodEntity>) : RemoteSearchState
    data class Failed(val message: String) : RemoteSearchState
}

/** Search dialog to assign another food: local database first, Open Food Facts on demand. */
@Composable
internal fun FoodSearchDialog(
    itemName: String,
    initialQuery: String,
    searchLocal: suspend (String) -> List<FoodEntity>,
    searchRemote: suspend (String) -> List<FoodEntity>,
    onSelect: (FoodEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var localResults by remember { mutableStateOf<List<FoodEntity>?>(null) }
    var remote by remember { mutableStateOf<RemoteSearchState>(RemoteSearchState.Idle) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(query) {
        remote = RemoteSearchState.Idle
        if (query.trim().length < 2) {
            localResults = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        localResults = try {
            searchLocal(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focusRequester.requestFocus() }
    }

    fun loadRemote() {
        val q = query
        remote = RemoteSearchState.Loading
        scope.launch {
            remote = try {
                RemoteSearchState.Loaded(searchRemote(q))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RemoteSearchState.Failed("Open Food Facts ist gerade nicht erreichbar. Bitte prüfe deine Internetverbindung.")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f),
        ) {
            Column(modifier = Modifier.padding(top = 20.dp, start = 20.dp, end = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lebensmittel zuordnen", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "für „$itemName“",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Schließen")
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Suche leeren")
                            }
                        }
                    },
                    placeholder = { Text("z. B. Reis gekocht") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val local = localResults
                    when {
                        local == null -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                        local.isEmpty() && query.trim().length >= 2 -> item(key = "none") {
                            Text(
                                text = "Nichts in deinen Lebensmitteln gefunden.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                        else -> items(local, key = { "l${it.id}" }) { food ->
                            FoodResultRow(food = food, onClick = { onSelect(food) })
                        }
                    }
                    if (query.trim().length >= 2) {
                        item(key = "remote") {
                            when (val r = remote) {
                                RemoteSearchState.Idle -> OutlinedButton(
                                    onClick = ::loadRemote,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, end = 8.dp),
                                ) {
                                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Bei Open Food Facts suchen")
                                }
                                RemoteSearchState.Loading -> Box(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                                is RemoteSearchState.Failed -> Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                    Text(
                                        text = r.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = ::loadRemote) { Text("Erneut versuchen") }
                                }
                                is RemoteSearchState.Loaded -> Text(
                                    text = if (r.foods.isEmpty()) "Auch bei Open Food Facts nichts gefunden." else "Open Food Facts",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
                                )
                            }
                        }
                        val loaded = remote as? RemoteSearchState.Loaded
                        if (loaded != null) {
                            items(loaded.foods.withIndex().toList(), key = { "r${it.index}" }) { (_, food) ->
                                FoodResultRow(food = food, onClick = { onSelect(food) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodResultRow(food: FoodEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(sourceColor(food.source).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (food.source == FoodSource.AI) Icons.Filled.AutoAwesome else Icons.Filled.Restaurant,
                contentDescription = null,
                tint = sourceColor(food.source),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        FoodMenuText(food)
    }
}

@Composable
private fun sourceColor(source: FoodSource): Color = when (source) {
    FoodSource.BUILTIN -> MaterialTheme.colorScheme.primary
    FoodSource.CUSTOM -> MaterialTheme.colorScheme.tertiary
    FoodSource.OPEN_FOOD_FACTS -> MaterialTheme.colorScheme.secondary
    FoodSource.AI -> MaterialTheme.colorScheme.secondary
}
