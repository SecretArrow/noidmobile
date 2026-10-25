// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.pow.MinerEngine
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.component.StatCard

@Composable
fun DashboardScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vault = remember { Vault(ctx) }
    val rpc = remember { RpcClient(url = vault.rpcUrl, miningKey = vault.miningKey.ifEmpty { null }) }

    var address by remember { mutableStateOf("—") }
    var balance by remember { mutableStateOf("—") }
    var height by remember { mutableStateOf("—") }
    var peers by remember { mutableStateOf("—") }

    val minerState by MinerEngine.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        launch {
            while (true) {
                val s = try { rpc.walletStatus() } catch (_: Exception) { null }
                val n = try { rpc.getNodeStatus() } catch (_: Exception) { null }
                val h = try { rpc.blockCount() } catch (_: Exception) { null }
                address = s?.address ?: "—"
                balance = ((s?.balance_micronoid?.toLongOrNull() ?: 0L) / 1_000_000.0).let { "%.6f".format(it) } + " NOID"
                height  = h?.toString() ?: "—"
                peers   = n?.peers?.toString() ?: "—"
                withContext(Dispatchers.IO) { Thread.sleep(5_000) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "NOID//MOBILE",
            color = NoidGreen,
            fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            "> DASHBOARD",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = MaterialTheme.typography.labelMedium.fontFamily,
            fontSize = 12.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("HASHRATE", "%.1f H/s".format(minerState.hashrateHps),
                modifier = Modifier.weight(1f))
            StatCard("BLOCKS", minerState.blocksFound.toString(),
                modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("HEIGHT", height, modifier = Modifier.weight(1f))
            StatCard("PEERS", peers, modifier = Modifier.weight(1f))
        }
        StatCard("ACTIVE ADDRESS", address)
        StatCard("BALANCE", balance)
    }
}
