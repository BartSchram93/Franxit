package com.bartschram.franxit.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The app's loading animation: three dots with a soft wave running through them (each dot rises,
 * grows and brightens a moment after the previous one). Calmer than a spinner, and it fits inside
 * buttons and rows without changing their size.
 */
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 6.dp,
) {
    val time = rememberInfiniteTransition(label = "dots")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "wave")
    Row(
        modifier.semantics { contentDescription = t("Loading") },
        horizontalArrangement = Arrangement.spacedBy(dotSize * 0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(dotSize)
                    // Read in the draw phase only, so the animation doesn't recompose every frame.
                    .graphicsLayer {
                        val phase = ((time.value - i * 0.16f) % 1f + 1f) % 1f
                        val k = if (phase < 0.6f) sin(phase / 0.6f * PI).toFloat() else 0f
                        val s = 0.6f + 0.4f * k
                        scaleX = s
                        scaleY = s
                        alpha = 0.3f + 0.7f * k
                        translationY = -k * size.height * 0.45f
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

/** The refresh icon, turning while a check runs: the button stays where it is instead of becoming a spinner. */
@Composable
fun SpinningRefresh(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    val angle = rememberInfiniteTransition(label = "refresh")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = t("Checking"),
            tint = tint,
            modifier = Modifier.graphicsLayer { rotationZ = angle.value },
        )
    }
}
