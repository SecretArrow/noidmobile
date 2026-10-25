// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────
// MD3 dark color scheme — neon green tonal palette.
//
// Tonal palette generated from the #00E5A0 primary. Pure black is
// replaced by `#0A0E0C` (very dark green-tinted) so cards sit
// naturally on the surface and the UI doesn't feel harsh.
// ─────────────────────────────────────────────────────────────────────

private val NoidColorScheme = darkColorScheme(
    primary              = Color(0xFF00E5A0),
    onPrimary            = Color(0xFF003826),
    primaryContainer     = Color(0xFF00513A),
    onPrimaryContainer   = Color(0xFF7DF8C4),

    secondary            = Color(0xFFFF6B9D),
    onSecondary          = Color(0xFF590027),
    secondaryContainer   = Color(0xFF7A2949),
    onSecondaryContainer = Color(0xFFFFD9E2),

    tertiary             = Color(0xFFFFB74D),
    onTertiary           = Color(0xFF3D1F00),
    tertiaryContainer    = Color(0xFF5A3500),
    onTertiaryContainer  = Color(0xFFFFDDB0),

    error                = Color(0xFFFF5252),
    onError              = Color(0xFF690000),
    errorContainer       = Color(0xFF930000),
    onErrorContainer     = Color(0xFFFFDAD6),

    background           = Color(0xFF0A0E0C),
    onBackground         = Color(0xFFE1E3DE),
    surface              = Color(0xFF121815),
    onSurface            = Color(0xFFE1E3DE),
    surfaceVariant       = Color(0xFF1E2622),
    onSurfaceVariant     = Color(0xFFC0CCC6),

    surfaceContainerLowest  = Color(0xFF080B09),
    surfaceContainerLow     = Color(0xFF0F1411),
    surfaceContainer        = Color(0xFF121815),
    surfaceContainerHigh    = Color(0xFF1A201D),
    surfaceContainerHighest = Color(0xFF222825),

    outline              = Color(0xFF6B7972),
    outlineVariant       = Color(0xFF2A352F),

    inverseSurface       = Color(0xFFE1E3DE),
    inverseOnSurface     = Color(0xFF121815),
    inversePrimary       = Color(0xFF006B4C),
)

/**
 * Material Design 3 theme — Cyberpunk-Dark variant. Always dark; the
 * system light/dark setting is intentionally ignored because the
 * neon green-on-dark aesthetic is the brand.
 */
@Composable
fun NoidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NoidColorScheme,
        typography = NoidTypography,
        content = content,
    )
}
