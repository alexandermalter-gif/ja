package de.kalorienkompass.ui.scan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bright mint used on the (always dark) camera screen, independent of the app theme. */
internal val ScannerAccent: Color = Color(0xFF5EE6A8)

/** Position and size of the scan window inside the camera area (dp). */
internal data class ScanWindow(val left: Dp, val top: Dp, val width: Dp, val height: Dp) {
    val bottom: Dp get() = top + height
}

/** Wide window for 1D barcodes, horizontally centered and a little above the middle. */
internal fun scanWindowFor(maxWidth: Dp, maxHeight: Dp): ScanWindow {
    val width = minOf(maxWidth * 0.82f, 380.dp)
    val height = minOf(width * 0.58f, maxHeight * 0.34f)
    val top = (maxHeight * 0.40f - height / 2).coerceAtLeast(88.dp)
    return ScanWindow(left = (maxWidth - width) / 2, top = top, width = width, height = height)
}

/** How the scan window is drawn. */
internal enum class OverlayMode {
    /** Animated scan line. */
    SCANNING,

    /** Pulsing corners (lookup running, camera starting). */
    BUSY,

    /** Static corners. */
    IDLE,

    /** Solid accent frame (product found). */
    SUCCESS,
}

/**
 * Darkens everything outside the scan window, draws rounded corner brackets and – while
 * scanning – a moving scan line. A tap anywhere calls [onTap] (tap to focus) and shows a short
 * focus ring.
 */
@Composable
internal fun ScanOverlay(
    window: ScanWindow,
    mode: OverlayMode,
    modifier: Modifier = Modifier,
    onTap: ((Offset) -> Unit)? = null,
) {
    val currentOnTap by rememberUpdatedState(onTap)

    val transition = rememberInfiniteTransition(label = "scanOverlay")
    val lineProgress: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanLine",
    )
    val pulse: State<Float> = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTaps by remember { mutableIntStateOf(0) }
    val focusRing = remember { Animatable(0f) }
    LaunchedEffect(focusTaps) {
        if (focusTaps > 0) {
            focusRing.snapTo(1f)
            focusRing.animateTo(0f, animationSpec = tween(durationMillis = 900))
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Offscreen layer so that BlendMode.Clear punches a transparent hole into the scrim.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val handler = currentOnTap ?: return@detectTapGestures
                    focusPoint = offset
                    focusTaps++
                    handler(offset)
                }
            },
    ) {
        val rect = Rect(
            left = window.left.toPx(),
            top = window.top.toPx(),
            right = (window.left + window.width).toPx(),
            bottom = window.bottom.toPx(),
        )
        val radius = 22.dp.toPx()

        // Scrim with a transparent window.
        drawRect(Color.Black.copy(alpha = 0.58f))
        drawRoundRect(
            color = Color.Black,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(radius),
            blendMode = BlendMode.Clear,
        )

        // Hairline around the window.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 1.dp.toPx()),
        )

        val bracketAlpha = when (mode) {
            OverlayMode.BUSY -> pulse.value
            OverlayMode.IDLE -> 0.7f
            OverlayMode.SCANNING, OverlayMode.SUCCESS -> 1f
        }
        drawCornerBrackets(
            rect = rect,
            radius = radius,
            length = 34.dp.toPx(),
            strokeWidth = 4.dp.toPx(),
            color = ScannerAccent.copy(alpha = bracketAlpha),
        )

        when (mode) {
            OverlayMode.SCANNING -> drawScanLine(rect, lineProgress.value)
            OverlayMode.SUCCESS -> drawRoundRect(
                color = ScannerAccent,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 3.dp.toPx()),
            )
            OverlayMode.BUSY, OverlayMode.IDLE -> Unit
        }

        // Tap-to-focus ring.
        val ringAlpha = focusRing.value
        val point = focusPoint
        if (point != null && ringAlpha > 0f) {
            drawCircle(
                color = Color.White.copy(alpha = ringAlpha),
                radius = 28.dp.toPx() + (1f - ringAlpha) * 10.dp.toPx(),
                center = point,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawScanLine(rect: Rect, progress: Float) {
    val inset = 16.dp.toPx()
    val y = rect.top + inset + progress * (rect.height - 2 * inset)
    val left = rect.left + 20.dp.toPx()
    val right = rect.right - 20.dp.toPx()
    val glow = 22.dp.toPx()

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, ScannerAccent.copy(alpha = 0.22f), Color.Transparent),
            startY = y - glow,
            endY = y + glow,
        ),
        topLeft = Offset(left, y - glow),
        size = Size(right - left, glow * 2),
    )
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, ScannerAccent, ScannerAccent, Color.Transparent),
            startX = left,
            endX = right,
        ),
        start = Offset(left, y),
        end = Offset(right, y),
        strokeWidth = 2.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawCornerBrackets(
    rect: Rect,
    radius: Float,
    length: Float,
    strokeWidth: Float,
    color: Color,
) {
    val r = radius
    val l = maxOf(length, r + 6f)
    val path = Path().apply {
        // top left
        moveTo(rect.left, rect.top + l)
        lineTo(rect.left, rect.top + r)
        arcTo(Rect(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r), 180f, 90f, false)
        lineTo(rect.left + l, rect.top)
        // top right
        moveTo(rect.right - l, rect.top)
        lineTo(rect.right - r, rect.top)
        arcTo(Rect(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r), 270f, 90f, false)
        lineTo(rect.right, rect.top + l)
        // bottom right
        moveTo(rect.right, rect.bottom - l)
        lineTo(rect.right, rect.bottom - r)
        arcTo(Rect(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom), 0f, 90f, false)
        lineTo(rect.right - l, rect.bottom)
        // bottom left
        moveTo(rect.left + l, rect.bottom)
        lineTo(rect.left + r, rect.bottom)
        arcTo(Rect(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom), 90f, 90f, false)
        lineTo(rect.left, rect.bottom - l)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
