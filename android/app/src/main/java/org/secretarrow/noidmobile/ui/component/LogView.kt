// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.secretarrow.noidmobile.pow.LogLine
import org.secretarrow.noidmobile.ui.NoidAmber
import org.secretarrow.noidmobile.ui.NoidGray
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.NoidMagenta

/**
 * Scrolling log view for miner status messages. Auto-scrolls to the
 * newest line. Bounded to 96dp tall, scrolls vertically with a custom
 * scrollbar-less style.
 */
@Composable
fun LogView(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            state.animateScrollToItem(lines.lastIndex)
        }
    }
    ScanlineBox(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .heightIn(max = 240.dp)
                .fillMaxWidth(),
        ) {
            items(lines) { line ->
                val color = when (line.level) {
                    "error" -> NoidAmber
                    "warn"  -> NoidMagenta
                    "info"  -> NoidGreen
                    else    -> NoidGray
                }
                Text(
                    text = "${line.ts.substringAfter('T').take(12)}  ${line.message}",
                    color = color,
                    fontFamily = MaterialTheme.typography.bodySmall.fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}
