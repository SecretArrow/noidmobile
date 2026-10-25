// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────
// Material Design 3 — Cyberpunk-Dark variant
//
// Built from a neon green tonal palette (primary) with soft pink
// (secondary, replaces harsh magenta) and warm amber (tertiary) for
// warnings/special states. Pure black is replaced by a very dark
// green-tinted background so cards sit naturally on the surface.
//
// These are kept as semantic aliases so other code that imports
// `NoidGreen` / `NoidMagenta` / `NoidAmber` / `NoidBlack` keeps working
// after the MD3 redesign. The full tonal breakdown lives in Theme.kt.
// ─────────────────────────────────────────────────────────────────────

/** App background / `surfaceContainerLowest`. Very dark green-tinted. */
val NoidBlack   = Color(0xFF0A0E0C)

/** Primary — neon green, slightly desaturated to reduce eye strain. */
val NoidGreen   = Color(0xFF00E5A0)

/** Secondary — soft pink (replaces the original harsh magenta). */
val NoidMagenta = Color(0xFFFF6B9D)

/** Tertiary — warm amber for warnings / recovery secrets. */
val NoidAmber   = Color(0xFFFFB74D)

/** onSurface text colour. */
val NoidWhite   = Color(0xFFE1E3DE)

/** outline. */
val NoidGray    = Color(0xFF6B7972)
