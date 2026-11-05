package de.kalorienkompass.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatGrams

/*
 * Small UI building blocks shared by the recipe screens.
 */

/** Top bar colors matching the app background (like the other screens). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun recipeTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
)

/** "−  value  +" stepper; tapping the value (if [onValueClick] is set) allows typing a number. */
@Composable
internal fun PortionStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
    onValueClick: (() -> Unit)? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onDecrease, enabled = decreaseEnabled) {
            Icon(Icons.Filled.Remove, contentDescription = "Weniger Portionen")
        }
        Box(
            modifier = Modifier
                .widthIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    enabled = onValueClick != null,
                    onClickLabel = "Anzahl eingeben",
                    role = Role.Button,
                ) { onValueClick?.invoke() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        FilledTonalIconButton(onClick = onIncrease, enabled = increaseEnabled) {
            Icon(Icons.Filled.Add, contentDescription = "Mehr Portionen")
        }
    }
}

/** Three colored tiles: carbs, protein, fat (grams). */
@Composable
internal fun MacroTilesRow(nutrition: Nutrition, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MacroTile("Kohlenhydrate", nutrition.carbs, AppColors.carbs, Modifier.weight(1f))
        MacroTile("Eiweiß", nutrition.protein, AppColors.protein, Modifier.weight(1f))
        MacroTile("Fett", nutrition.fat, AppColors.fat, Modifier.weight(1f))
    }
}

@Composable
private fun MacroTile(label: String, grams: Double, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(
            text = "${formatGrams(grams)} g",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "KH 12 g · E 3 g · F 1 g" with colored macro initials. */
@Composable
internal fun MacroLine(nutrition: Nutrition, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MacroLinePart("KH", nutrition.carbs, AppColors.carbs)
        Spacer(Modifier.width(10.dp))
        MacroLinePart("E", nutrition.protein, AppColors.protein)
        Spacer(Modifier.width(10.dp))
        MacroLinePart("F", nutrition.fat, AppColors.fat)
    }
}

@Composable
private fun MacroLinePart(short: String, grams: Double, color: Color) {
    Text(
        text = short,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
    Spacer(Modifier.width(3.dp))
    Text(
        text = "${formatGrams(grams)} g",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Outlined "150 g ✎" pill that opens an amount dialog. */
@Composable
internal fun AmountPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClickLabel = "Menge ändern", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** One chip per meal (emoji + label); wraps onto a second line on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MealChips(
    selected: MealType,
    onSelect: (MealType) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MealType.entries.forEach { meal ->
            FilterChip(
                selected = meal == selected,
                onClick = { onSelect(meal) },
                enabled = enabled,
                label = { Text("${meal.emoji} ${meal.label}") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/** Ignores taps while a screen transition is running (prevents opening a screen twice). */
internal fun NavController.navigateIfResumed(
    owner: LifecycleOwner,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        navigate(route, builder)
    }
}

/** Replaces a visible snackbar instead of queueing behind it. */
internal suspend fun SnackbarHostState.showMessage(
    text: String,
    actionLabel: String? = null,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(
        message = text,
        actionLabel = actionLabel,
        withDismissAction = false,
        duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    )
}
