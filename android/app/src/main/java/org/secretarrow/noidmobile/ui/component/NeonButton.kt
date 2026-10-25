// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.secretarrow.noidmobile.ui.NoidBlack
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.NoidMagenta
import org.secretarrow.noidmobile.ui.NoidAmber

enum class NeonButtonStyle { Primary, Accent, Warn }

/**
 * Cyberpunk neon button: solid background variant of the design system.
 * Used for START MINING / STOP MINING / SEND / IMPORT etc.
 */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: NeonButtonStyle = NeonButtonStyle.Primary,
) {
    val color = when (style) {
        NeonButtonStyle.Primary -> NoidGreen
        NeonButtonStyle.Accent  -> NoidMagenta
        NeonButtonStyle.Warn    -> NoidAmber
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (enabled) color.copy(alpha = if (pressed) 0.25f else 0.12f) else Color(0xFF222222)

    Box(
        modifier = modifier
            .background(bg)
            .border(1.dp, if (enabled) color else Color(0xFF444444))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(PaddingValues(horizontal = 18.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) color else Color(0xFF666666),
            fontFamily = MaterialTheme.typography.labelLarge.fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = MaterialTheme.typography.labelLarge.fontSize,
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
        )
    }
}
