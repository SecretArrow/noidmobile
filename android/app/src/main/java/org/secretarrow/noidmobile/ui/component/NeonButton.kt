// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class NeonButtonStyle { Primary, Accent, Warn }

/**
 * MD3 buttons wrapper. Kept under the historical `NeonButton` name so
 * callers don't change; internally it maps to MD3 [Button] (filled),
 * [OutlinedButton], and a tinted [Button] (error) for the warn style.
 *
 * - Primary → MD3 filled Button (default primary container/onPrimary).
 * - Accent  → MD3 OutlinedButton.
 * - Warn    → MD3 filled Button using `error` / `onError`.
 *
 * MD3 default shapes give us the standard 20dp rounded corners.
 * Height is pinned to 48dp to honour the MD3 touch-target minimum.
 */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: NeonButtonStyle = NeonButtonStyle.Primary,
) {
    when (style) {
        NeonButtonStyle.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }

        NeonButtonStyle.Accent -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }

        NeonButtonStyle.Warn -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor   = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
