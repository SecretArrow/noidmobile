// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.ui.NoidMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { Vault(ctx) }

    var rpcUrl by remember { mutableStateOf(vault.rpcUrl) }
    var miningKey by remember { mutableStateOf(vault.miningKey) }
    var poolHost by remember { mutableStateOf(vault.poolHost) }
    var poolPort by remember { mutableStateOf(vault.poolPort.toString()) }
    var poolPubkey by remember { mutableStateOf(vault.poolPubkey) }
    var threads by remember { mutableStateOf(vault.threads.coerceIn(0, 16)) }
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var guideExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Button(
                    onClick = {
                        vault.rpcUrl = rpcUrl.ifBlank { "http://127.0.0.1:9601" }
                        vault.miningKey = miningKey
                        vault.poolHost = poolHost
                        vault.poolPort = poolPort.toIntOrNull() ?: 3333
                        vault.poolPubkey = poolPubkey
                        vault.threads = threads
                        Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(48.dp),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SAVE SETTINGS", fontWeight = FontWeight.Bold)
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Section 1: Solo Mining ────────────────────────────────
            SectionCard(
                title = "Solo Mining",
                subtitle = "JSON-RPC → remote parano1d node",
                icon = Icons.Filled.Dns,
            ) {
                OutlinedTextField(
                    value = rpcUrl,
                    onValueChange = { rpcUrl = it },
                    label = { Text("RPC URL") },
                    placeholder = { Text("https://your-vps.com:9601") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = miningKey,
                    onValueChange = { miningKey = it },
                    label = { Text("Mining key (Bearer token from node)") },
                    placeholder = { Text("(leave blank for localhost solo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (testing) return@OutlinedButton
                            testing = true
                            testResult = "Testing..."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val rpc = RpcClient(url = rpcUrl, miningKey = miningKey.ifBlank { null })
                                        val status = rpc.getNodeStatus()
                                        "✓ Connected — synced=${status.synced} peers=${status.peers} version=${status.version.take(20)}"
                                    } catch (e: Exception) {
                                        "✗ Failed: ${e.message?.take(100)}"
                                    }
                                }
                                testResult = result
                                testing = false
                                Toast.makeText(ctx, if (result.startsWith("✓")) "Connected" else "Failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !testing,
                    ) {
                        Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("TEST")
                    }
                    OutlinedButton(
                        onClick = {
                            rpcUrl = "http://127.0.0.1:9601"
                            miningKey = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("USE LOCALHOST") }
                }
                if (testResult.isNotBlank()) {
                    val ok = testResult.startsWith("✓")
                    Surface(
                        color = if (ok) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (ok) Icons.Filled.Check else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (ok) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                testResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onErrorContainer,
                                fontFamily = NoidMono,
                            )
                        }
                    }
                }
                Text(
                    "Connect directly to a parano1d node running on your VPS. " +
                        "The node holds the wallet and builds proven templates; " +
                        "this app only does PoW search. WORKING TODAY.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Section 2: Pool Mining ────────────────────────────────
            SectionCard(
                title = "Pool Mining",
                subtitle = "Stratum V2 → peakpool (experimental)",
                icon = Icons.Filled.Hub,
            ) {
                OutlinedTextField(
                    value = poolHost,
                    onValueChange = { poolHost = it },
                    label = { Text("Pool host") },
                    placeholder = { Text("pool.your-vps.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = poolPort,
                    onValueChange = { v -> poolPort = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Pool port") },
                    placeholder = { Text("3333") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = poolPubkey,
                    onValueChange = { poolPubkey = it.filter { c -> c in "0123456789abcdefABCDEF" } },
                    label = { Text("Pool static pubkey (Noise auth)") },
                    placeholder = { Text("64 hex chars (32 bytes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Connect directly to a peakpool instance over Noise-encrypted TCP. " +
                        "EXPERIMENTAL — requires you to run peakpool on your own server " +
                        "(proprietary, Docker image only). Get the pool pubkey from peakpool's logs when it starts " +
                        "— authenticates the pool and prevents MITM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Section 3: Performance ────────────────────────────────
            SectionCard(
                title = "Performance",
                subtitle = null,
                icon = Icons.Filled.Memory,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Threads",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (threads == 0) "All cores" else threads.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = NoidMono,
                    )
                }
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.toInt() },
                    valueRange = 0f..16f,
                    steps = 15,
                )
                Text(
                    "0 = use all CPU cores. Set 2-4 for battery saving on phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Section 4: Setup Guide (expandable) ───────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { guideExpanded = !guideExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.HelpOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Setup Guide",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (guideExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (guideExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = guideExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Text(
                            SETUP_GUIDE_TEXT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = NoidMono,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private const val SETUP_GUIDE_TEXT = """Parano1d mining model is DIFFERENT from BTC. The worker
needs a node that provides proven templates. There is no
public pool — you run your own.

QUICK SETUP (VPS):
1. SSH to your VPS (Ubuntu 22+)
2. Download parano1d from parano1d.org/downloads
3. Run: parano1d --mode miner --miner-address o1...
4. Get mining key: cat ~/.parano1d/mining.key
5. In this app: set RPC URL = http://YOUR-VPS:9601
6. Set Mining Key = the key from step 4
7. Tap START MINING — app connects directly to VPS

For peakpool (Stratum V2):
1. cd deployments/parano1d && cp .env.example .env
2. Set your wallet + node RPC token in .env
3. docker compose up -d
4. Get pool pubkey from: docker compose logs pool
5. In this app: set Pool Host/Port/Pubkey above
6. Tap START MINING in POOL mode"""
