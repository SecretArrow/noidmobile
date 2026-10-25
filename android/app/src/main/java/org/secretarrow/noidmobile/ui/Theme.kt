// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NoidColorScheme = darkColorScheme(
    primary = NoidGreen,
    onPrimary = NoidBlack,
    primaryContainer = NoidDarkGray,
    onPrimaryContainer = NoidGreen,
    secondary = NoidMagenta,
    onSecondary = NoidBlack,
    secondaryContainer = NoidDarkGray,
    onSecondaryContainer = NoidMagenta,
    tertiary = NoidAmber,
    onTertiary = NoidBlack,
    background = NoidBlack,
    onBackground = NoidWhite,
    surface = NoidPanel,
    onSurface = NoidWhite,
    surfaceVariant = NoidDarkGray,
    onSurfaceVariant = NoidGray,
    error = NoidAmber,
    onError = NoidBlack,
    outline = NoidGray,
)

/**
 * Cyberpunk terminal theme. Always dark — ignores the system light/dark
 * setting because the neon green-on-black aesthetic is the design.
 */
@Composable
fun NoidTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() is intentionally ignored.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = NoidColorScheme,
        typography = NoidTypography,
        content = content,
    )
}
