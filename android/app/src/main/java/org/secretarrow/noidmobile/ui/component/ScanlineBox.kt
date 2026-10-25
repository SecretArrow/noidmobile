// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.secretarrow.noidmobile.ui.NoidGreen

/**
 * Cyberpunk scanline box: thin border + animated scanline overlay.
 * Used to wrap status panels and the miner log view.
 */
@Composable
fun ScanlineBox(
    modifier: Modifier = Modifier,
    borderColor: Color = NoidGreen,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val alpha by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanlineAlpha",
    )

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor)
            .padding(12.dp)
            .drawWithContent {
                drawContent()
                // Subtle scanline overlay.
                val stripeHeight = 2f
                var y = 0f
                while (y < size.height) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                        size = androidx.compose.ui.geometry.Size(size.width, stripeHeight),
                        alpha = 0.25f,
                    )
                    y += 4f
                }
            }
            .alpha(1f),
        content = content,
    )
}
