// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.pow.MinerEngine
import org.secretarrow.noidmobile.ui.NoidMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onNavigateWallet: () -> Unit) {
    val ctx = LocalContext.current
    val vault = remember { Vault(ctx) }
    val rpc = remember { RpcClient(url = vault.rpcUrl, miningKey = vault.miningKey.ifEmpty { null }) }

    var address by remember { mutableStateOf("—") }
    var balance by remember { mutableStateOf("—") }
    var height by remember { mutableStateOf("—") }
    var peers by remember { mutableStateOf("—") }

    val minerState by MinerEngine.state.collectAsStateWithLifecycle()

    // Poll wallet + node status every 5s. Same logic as before — purely
    // visual refresh of the dashboard, no behavioural change.
    LaunchedEffect(Unit) {
        while (true) {
            val s = try { rpc.walletStatus() } catch (_: Exception) { null }
            val n = try { rpc.getNodeStatus() } catch (_: Exception) { null }
            val h = try { rpc.blockCount() } catch (_: Exception) { null }
            address = s?.address ?: "—"
            balance = ((s?.balance_micronoid?.toLongOrNull() ?: 0L) / 1_000_000.0)
                .let { "%.6f".format(it) } + " NOID"
            height  = h?.toString() ?: "—"
            peers   = n?.peers?.toString() ?: "—"
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NOID//MOBILE", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateWallet) {
                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = "Open wallet")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Hero balance card ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "BALANCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        balance,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── Stat grid 2x2 with leading icons ───────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "HASHRATE",
                    value = "%.1f H/s".format(minerState.hashrateHps),
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Filled.Speed,
                )
                StatCard(
                    label = "BLOCKS",
                    value = minerState.blocksFound.toString(),
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Filled.Inventory2,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "HEIGHT",
                    value = height,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Filled.Layers,
                )
                StatCard(
                    label = "PEERS",
                    value = peers,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Filled.Hub,
                )
            }

            // ── Active address card with COPY button ───────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "ACTIVE ADDRESS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = NoidMono,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = {
                        if (address.isNotBlank() && address != "—") {
                            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Address", address))
                            Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy address")
                    }
                }
            }
        }
    }
}
