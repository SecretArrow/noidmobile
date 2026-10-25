// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.secretarrow.noidmobile.data.MinerMode
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.pow.LogLine
import org.secretarrow.noidmobile.pow.MinerEngine
import org.secretarrow.noidmobile.pow.MinerService
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.NoidMagenta
import org.secretarrow.noidmobile.ui.component.LogView
import org.secretarrow.noidmobile.ui.component.NeonButton
import org.secretarrow.noidmobile.ui.component.NeonButtonStyle
import org.secretarrow.noidmobile.ui.component.ScanlineBox
import org.secretarrow.noidmobile.ui.component.StatCard

@Composable
fun MinerScreen() {
    val ctx = LocalContext.current
    val vault = remember { Vault(ctx) }
    val minerState by MinerEngine.state.collectAsStateWithLifecycle()
    val logLines = remember { mutableStateListOf<LogLine>() }
    var mode by remember { mutableStateOf(vault.minerMode) }

    LaunchedEffect(Unit) {
        MinerEngine.log.collect { line ->
            logLines.add(line)
            // Bounded buffer — keep last 200.
            while (logLines.size > 200) logLines.removeAt(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "> MINER",
            color = NoidGreen,
            fontFamily = MaterialTheme.typography.headlineMedium.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("HASHRATE", "%.1f H/s".format(minerState.hashrateHps),
                modifier = Modifier.weight(1f))
            StatCard("BLOCKS", minerState.blocksFound.toString(),
                modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("HEIGHT", minerState.height.toString(), modifier = Modifier.weight(1f))
            StatCard("MODE", mode.name,
                accent = if (mode == MinerMode.POOL) NoidMagenta else NoidGreen,
                modifier = Modifier.weight(1f))
        }
        ScanlineBox(modifier = Modifier.padding(0.dp)) {
            Text(
                "TARGET: ${minerState.targetHex.take(16)}…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = MaterialTheme.typography.bodySmall.fontFamily,
                fontSize = 11.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton(
                text = if (minerState.running) "STOP MINING" else "START MINING",
                onClick = {
                    if (minerState.running) {
                        MinerEngine.stop()
                        ctx.stopService(Intent(ctx, MinerService::class.java))
                    } else {
                        vault.minerMode = mode
                        val intent = Intent(ctx, MinerService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            ctx.startForegroundService(intent)
                        } else {
                            ctx.startService(intent)
                        }
                    }
                },
                style = if (minerState.running) NeonButtonStyle.Warn else NeonButtonStyle.Primary,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton(
                text = "SOLO",
                onClick = { mode = MinerMode.SOLO; vault.minerMode = MinerMode.SOLO },
                style = if (mode == MinerMode.SOLO) NeonButtonStyle.Primary else NeonButtonStyle.Accent,
                modifier = Modifier.weight(1f),
                enabled = !minerState.running,
            )
            NeonButton(
                text = "POOL",
                onClick = { mode = MinerMode.POOL; vault.minerMode = MinerMode.POOL },
                style = if (mode == MinerMode.POOL) NeonButtonStyle.Accent else NeonButtonStyle.Primary,
                modifier = Modifier.weight(1f),
                enabled = !minerState.running,
            )
        }

        LogView(logLines.toList())
    }
}
