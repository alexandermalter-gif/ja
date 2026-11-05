package de.kalorienkompass.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatKcal

/** Label, thin rounded progress bar and "12 / 255 g". The bar turns red when the goal is exceeded by > 5 %. */
@Composable
fun MacroProgress(
    label: String,
    current: Double,
    goal: Double,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String = "g",
) {
    val target = if (goal > 0.0) (current / goal).toFloat().coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 600),
        label = "macroProgress",
    )
    val over = goal > 0.0 && current > goal * 1.05
    val barColor = if (over) AppColors.over else color
    val shape = RoundedCornerShape(3.dp)

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(shape)
                .background(color.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(barColor),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${formatKcal(current)} / ${formatKcal(goal)} $unit",
            style = MaterialTheme.typography.labelMedium,
            color = if (over) AppColors.over else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
