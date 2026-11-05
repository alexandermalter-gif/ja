package de.kalorienkompass.ui.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.DecimalTextField
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.parseDecimal

/** Emoji for the categories of the built-in foods (assets/foods_de.json). */
internal fun categoryEmoji(category: String?): String? = when (category) {
    "Obst" -> "🍎"
    "Gemüse" -> "🥦"
    "Brot & Backwaren" -> "🍞"
    "Getreide & Beilagen" -> "🍚"
    "Milchprodukte" -> "🥛"
    "Käse" -> "🧀"
    "Eier" -> "🥚"
    "Fleisch & Wurst" -> "🥩"
    "Fisch" -> "🐟"
    "Hülsenfrüchte" -> "🌱"
    "Nüsse & Samen" -> "🥜"
    "Fette & Öle" -> "🧈"
    "Süßes & Snacks" -> "🍫"
    "Getränke" -> "🥤"
    "Fertiggerichte & Fast Food" -> "🍕"
    "Saucen & Aufstriche" -> "🥫"
    else -> null
}

/** Round thumbnail: product photo, category emoji or the first letter of the name. */
@Composable
internal fun FoodAvatar(food: FoodEntity, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val tint = sourceColor(food.source)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val emoji = categoryEmoji(food.category)
        when {
            !food.imageUrl.isNullOrBlank() -> AsyncImage(
                model = food.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(shape)
                    .background(Color.White),
            )
            emoji != null -> Text(emoji, style = MaterialTheme.typography.titleLarge)
            else -> Text(
                text = food.name.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
    }
}

@Composable
internal fun sourceColor(source: FoodSource): Color = when (source) {
    FoodSource.BUILTIN -> MaterialTheme.colorScheme.tertiary
    FoodSource.CUSTOM -> MaterialTheme.colorScheme.secondary
    FoodSource.AI -> AppColors.fat
    FoodSource.OPEN_FOOD_FACTS -> MaterialTheme.colorScheme.primary
}

/** Small rounded label with the food source ("Basis", "Eigenes", "KI-Schätzung", "Open Food Facts"). */
@Composable
internal fun SourceBadge(source: FoodSource, modifier: Modifier = Modifier) {
    val color = sourceColor(source)
    Text(
        text = source.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * One food in a list: thumbnail, name (+ favorite star), source badge for BUILTIN/CUSTOM/AI,
 * brand and "52 kcal / 100 g".
 */
@Composable
internal fun FoodListItem(
    food: FoodEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoodAvatar(food)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = food.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (food.isFavorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorit",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (food.source != FoodSource.OPEN_FOOD_FACTS) {
                        SourceBadge(food.source)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = listOfNotNull(food.brand?.takeIf { it.isNotBlank() }, per100Label(food)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Small grey label above a group of list items. */
@Composable
internal fun ListSectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontally scrollable meal selection (emoji + label). */
@Composable
internal fun MealChipsRow(
    selected: MealType,
    onSelect: (MealType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MealType.entries.forEach { meal ->
            FilterChip(
                selected = meal == selected,
                onClick = { onSelect(meal) },
                label = { Text("${meal.emoji}  ${meal.label}") },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * Quick entry (kcal only, macros optional). If no kcal are entered, they are computed from the macros.
 * [onConfirm] receives name, kcal, protein, carbs, fat.
 */
@Composable
internal fun QuickAddDialog(
    mealLabel: String,
    onConfirm: (name: String, kcal: Double, protein: Double, carbs: Double, fat: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var kcalText by rememberSaveable { mutableStateOf("") }
    var carbsText by rememberSaveable { mutableStateOf("") }
    var proteinText by rememberSaveable { mutableStateOf("") }
    var fatText by rememberSaveable { mutableStateOf("") }
    var showMacros by rememberSaveable { mutableStateOf(false) }

    val carbs = parseDecimal(carbsText)?.takeIf { it >= 0 } ?: 0.0
    val protein = parseDecimal(proteinText)?.takeIf { it >= 0 } ?: 0.0
    val fat = parseDecimal(fatText)?.takeIf { it >= 0 } ?: 0.0
    val macroKcal = estimatedKcal(fat = fat, carbs = carbs, protein = protein)
    val typedKcal = parseDecimal(kcalText)
    val kcal = if (kcalText.isBlank()) macroKcal else typedKcal ?: -1.0
    val valid = kcal > 0.0 && kcal <= 20_000.0

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Schnelleintrag · $mealLabel") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    label = { Text("Bezeichnung (optional)") },
                    placeholder = { Text("z. B. Kantinenessen") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                DecimalTextField(
                    value = kcalText,
                    onValueChange = { kcalText = it },
                    label = "Kalorien",
                    suffix = "kcal",
                    imeAction = if (showMacros) ImeAction.Next else ImeAction.Done,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kcalText.isBlank() && macroKcal > 0.0) {
                    Text(
                        text = "≈ ${formatKcal(macroKcal)} kcal aus den Makros berechnet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { showMacros = !showMacros }) {
                    Icon(
                        imageVector = if (showMacros) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (showMacros) "Makros ausblenden" else "Makros angeben (optional)")
                }
                AnimatedVisibility(visible = showMacros) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DecimalTextField(
                            value = carbsText,
                            onValueChange = { carbsText = it },
                            label = "Kohlenhydrate",
                            suffix = "g",
                            imeAction = ImeAction.Next,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DecimalTextField(
                            value = proteinText,
                            onValueChange = { proteinText = it },
                            label = "Eiweiß",
                            suffix = "g",
                            imeAction = ImeAction.Next,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DecimalTextField(
                            value = fatText,
                            onValueChange = { fatText = it },
                            label = "Fett",
                            suffix = "g",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        onConfirm(name.trim(), kcal, protein, carbs, fat)
                        onDismiss()
                    }
                },
                enabled = valid,
            ) { Text("Hinzufügen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** Tabs above the food lists (search screen and Lebensmittel tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodTabsRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(
        selectedTabIndex = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outline) },
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Colored dot used in macro legends. */
@Composable
internal fun ColorDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
