package de.kalorienkompass.ui.progress

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.ui.components.EmptyState

/** Segmented period selector "1 W · 1 M · 3 M · 6 M · 1 J · Alle" (equal widths, fits small phones). */
@Composable
internal fun RangeSelector(
    selected: ProgressRange,
    onSelect: (ProgressRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceVariant)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProgressRange.entries.forEach { range ->
            val isSelected = range == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) colors.primary else Color.Transparent,
                label = "rangeBackground",
            )
            val foreground by animateColorAsState(
                targetValue = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                label = "rangeForeground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(background)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(range) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = range.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = foreground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Round tinted icon background. */
@Composable
internal fun IconBubble(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Card title row: icon bubble, title, optional subtitle and trailing content. */
@Composable
internal fun CardHeader(
    icon: ImageVector,
    title: String,
    tint: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon = icon, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Small label above a bold value. */
@Composable
internal fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Text color for a [Trend]: mint = good, amber = away from the goal, grey = neutral. */
@Composable
internal fun trendColor(trend: Trend): Color = when (trend) {
    Trend.GOOD -> MaterialTheme.colorScheme.primary
    Trend.BAD -> MaterialTheme.colorScheme.secondary
    Trend.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Pill with a trend arrow, e.g. "↘ −1,2 kg". */
@Composable
internal fun TrendChip(text: String, change: Double, trend: Trend, modifier: Modifier = Modifier) {
    val color = trendColor(trend)
    val icon = when {
        change < -0.0001 -> Icons.AutoMirrored.Filled.TrendingDown
        change > 0.0001 -> Icons.AutoMirrored.Filled.TrendingUp
        else -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** "Manuell" / "Health Connect" label. */
@Composable
internal fun SourceBadge(source: DataSource, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (source) {
        DataSource.HEALTH_CONNECT -> colors.tertiary.copy(alpha = 0.16f) to colors.tertiary
        DataSource.SENSOR -> colors.secondary.copy(alpha = 0.16f) to colors.secondary
        DataSource.MANUAL -> colors.onSurfaceVariant.copy(alpha = 0.12f) to colors.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (source == DataSource.HEALTH_CONNECT) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = foreground, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(source.label, style = MaterialTheme.typography.labelSmall, color = foreground, maxLines = 1)
    }
}

/** Empty hint inside a card, with an optional call to action. */
@Composable
internal fun InlineEmpty(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAction) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(actionLabel)
            }
        }
    }
}

/** Full-screen error with retry. */
@Composable
internal fun ErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Filled.ErrorOutline,
            title = "Etwas ist schiefgelaufen",
            text = message,
            actionLabel = "Erneut versuchen",
            onAction = onRetry,
        )
    }
}

internal fun measurementIcon(type: MeasurementType): ImageVector = when (type) {
    MeasurementType.WEIGHT -> Icons.Filled.MonitorWeight
    MeasurementType.BODY_FAT -> Icons.Filled.Percent
    else -> Icons.Filled.Straighten
}

/** "1 Tag" / "6 Tage" */
internal fun daysText(days: Int): String = if (days == 1) "1 Tag" else "$days Tage"

/** "aus 6 Tagen mit Einträgen · ohne heute" */
internal fun averageCaption(average: DayAverage, what: String): String {
    val days = if (average.days == 1) "1 Tag" else "${average.days} Tagen"
    return "Ø aus $days mit $what" + if (average.excludedToday) " · ohne heute (läuft noch)" else ""
}
