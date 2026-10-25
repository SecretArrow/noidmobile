// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.secretarrow.noidmobile.pow.LogLine
import org.secretarrow.noidmobile.ui.NoidMono

/**
 * Cleaner terminal-style log view.
 *
 * - Sits inside an MD3 Card (surfaceContainerLow) instead of a scanline box.
 * - Monospace font for log lines — semantic: these are timestamps + codes.
 * - Color-coded by level (error/warn/info/ok) using MD3 semantic colors.
 * - Auto-scrolls to the newest line when the user is already at the bottom.
 * - FAB to jump back to the latest line when scrolled up.
 */
@Composable
fun LogView(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val atBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.totalItemsCount - 1
            if (last < 0) true
            else info.visibleItemsInfo.lastOrNull()?.index?.let { it >= last - 1 } ?: true
        }
    }

    // Auto-scroll to newest line when the user is already watching the tail.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && atBottom) {
            state.animateScrollToItem(lines.lastIndex)
        }
    }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(lines) { line ->
                    val color = when (line.level) {
                        "error" -> MaterialTheme.colorScheme.error
                        "warn"  -> MaterialTheme.colorScheme.tertiary
                        "info"  -> MaterialTheme.colorScheme.primary
                        "ok"    -> MaterialTheme.colorScheme.primary
                        else    -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "${line.ts.substringAfter('T').take(12)}  ${line.message}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = NoidMono,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
        }

        // FAB to jump to the latest log line when scrolled up.
        if (!atBottom && lines.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    if (lines.isNotEmpty()) {
                        coroutineScope.launch {
                            state.animateScrollToItem(lines.lastIndex)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(36.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Jump to latest",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
