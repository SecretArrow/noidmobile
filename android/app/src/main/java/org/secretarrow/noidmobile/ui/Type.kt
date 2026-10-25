// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// JetBrains Mono is the canonical cyberpunk-terminal font. We fall back
// to the platform `Monospace` family if it's not bundled in res/font.
private val NoidMono = FontFamily.Monospace

val NoidTypography = Typography(
    displayLarge  = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Bold, fontSize = 32.sp, color = NoidGreen),
    displayMedium = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = NoidGreen),
    headlineLarge = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = NoidGreen),
    headlineMedium= TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = NoidGreen),
    titleLarge    = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = NoidGreen),
    titleMedium   = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NoidGreen),
    bodyLarge     = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = NoidWhite),
    bodyMedium    = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = NoidWhite),
    bodySmall     = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = NoidGray),
    labelLarge    = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NoidGreen),
    labelMedium   = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = NoidGray),
    labelSmall    = TextStyle(fontFamily = NoidMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = NoidGray),
)
