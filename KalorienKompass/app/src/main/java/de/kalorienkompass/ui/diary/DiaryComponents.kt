package de.kalorienkompass.ui.diary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kalorienkompass.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDate
import java.util.Locale

private const val RING_START_ANGLE = 135f
private const val RING_SWEEP_ANGLE = 270f

/**
 * 270° calorie ring (opening at the bottom). The arc animates from 0 on first display and then
 * between values; it turns red when [over] is true.
 */
@Composable
internal fun CalorieRing(
    progress: Float,
    over: Boolean,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        animated.animateTo(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        )
    }
    val arcColor by animateColorAsState(
        targetValue = if (over) AppColors.over else AppColors.kcal,
        animationSpec = tween(durationMillis = 400),
        label = "ringColor",
    )
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val glow = stroke * 1.8f
            val diameter = size.minDimension - glow
            if (diameter <= 0f) return@Canvas
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = RING_START_ANGLE,
                sweepAngle = RING_SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val sweep = RING_SWEEP_ANGLE * animated.value
            if (sweep > 0.5f) {
                // soft glow below the arc
                drawArc(
                    color = arcColor.copy(alpha = 0.16f),
                    startAngle = RING_START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = glow, cap = StrokeCap.Round),
                )
                drawArc(
                    color = arcColor,
                    startAngle = RING_START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** Thin rounded progress line with animated fill. */
@Composable
internal fun ThinProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "thinProgress",
    )
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(color.copy(alpha = 0.16f)),
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(color),
            )
        }
    }
}

/** Icon in a tinted circle. */
@Composable
internal fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * Tonal icon button that repeats [onStep] while it is held down (after a short delay).
 * A normal tap calls [onStep] once.
 */
@Composable
internal fun RepeatingIconButton(
    icon: ImageVector,
    contentDescription: String,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentOnStep by rememberUpdatedState(onStep)
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var repeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            repeated = false
            delay(450)
            while (isActive) {
                repeated = true
                currentOnStep()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(110)
            }
        }
    }

    FilledTonalIconButton(
        onClick = {
            if (!repeated) {
                currentOnStep()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            repeated = false
        },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}

/** Material3 date picker (German calendar: Monday first, German month names). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryDatePickerDialog(
    initial: LocalDate,
    today: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember(initial) {
        DatePickerState(
            locale = Locale.GERMANY,
            initialSelectedDateMillis = initial.toPickerMillis(),
            initialDisplayedMonthMillis = initial.toPickerMillis(),
            yearRange = (minOf(initial.year, today.year) - 10)..(maxOf(initial.year, today.year) + 1),
        )
    }
    val selected = state.selectedDateMillis?.let(::pickerMillisToDate)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) {
                Text("Anzeigen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    text = "Tag auswählen",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
            headline = {
                Text(
                    text = selected?.let { fullDateLabel(it, today) } ?: "Kein Tag gewählt",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                )
            },
            showModeToggle = false,
        )
    }
}
