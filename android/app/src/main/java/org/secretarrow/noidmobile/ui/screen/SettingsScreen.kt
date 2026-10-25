// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.ui.NoidAmber
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.NoidMagenta
import org.secretarrow.noidmobile.ui.component.NeonButton
import org.secretarrow.noidmobile.ui.component.NeonButtonStyle
import org.secretarrow.noidmobile.ui.component.ScanlineBox

/**
 * Settings screen with TWO mining connection modes:
 *
 * 1. SOLO (JSON-RPC to remote node) — connect directly to a parano1d node
 *    running on your VPS. The node holds the wallet + builds proven
 *    templates; this app only does Poseidon2b PoW search. WORKING TODAY.
 *
 * 2. POOL (Stratum V2 native) — connect directly to a peakpool instance
 *    over Noise-encrypted TCP. EXPERIMENTAL — requires you to run
 *    peakpool on your own server (it's proprietary, not a public pool).
 *
 * Both modes connect DIRECTLY from the app — no bridge, no middleware.
 */
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
    var threads by remember { mutableStateOf(vault.threads.toString()) }
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "> CONFIG",
            color = NoidGreen,
            fontFamily = MaterialTheme.typography.headlineMedium.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )

        // ─── MODE 1: SOLO — Remote node via JSON-RPC ────────────────
        ScanlineBox(borderColor = NoidGreen) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("MODE 1: SOLO (JSON-RPC → remote node)", color = NoidGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Connect directly to a parano1d node running on your VPS. " +
                        "The node holds the wallet and builds proven templates; " +
                        "this app only does PoW search. WORKING TODAY.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text("RPC URL", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = rpcUrl,
                    onValueChange = { rpcUrl = it },
                    placeholder = { Text("https://your-vps.com:9601") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    colors = noidFieldColors(),
                )

                Text("MINING KEY (Bearer token from node)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = miningKey,
                    onValueChange = { miningKey = it },
                    placeholder = { Text("(leave blank for localhost solo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    colors = noidFieldColors(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    NeonButton(
                        "TEST",
                        {
                            if (testing) return@NeonButton
                            testing = true
                            testResult = "Testing..."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val rpc = RpcClient(url = rpcUrl, miningKey = miningKey.ifBlank { null })
                                        val status = rpc.getNodeStatus()
                                        "✓ Connected — synced=${status.synced} peers=${status.peer_count} backend=${status.cpu_backend}"
                                    } catch (e: Exception) {
                                        "✗ Failed: ${e.message?.take(100)}"
                                    }
                                }
                                testResult = result
                                testing = false
                                Toast.makeText(ctx, if (result.startsWith("✓")) "Connected" else "Failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        style = NeonButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    NeonButton(
                        "USE LOCALHOST",
                        {
                            rpcUrl = "http://127.0.0.1:9601"
                            miningKey = ""
                        },
                        style = NeonButtonStyle.Accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (testResult.isNotBlank()) {
                    Text(
                        testResult,
                        color = if (testResult.startsWith("✓")) NoidGreen else NoidAmber,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // ─── MODE 2: POOL — Stratum V2 native ───────────────────────
        ScanlineBox(borderColor = NoidMagenta) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("MODE 2: POOL (Stratum V2 native → peakpool)", color = NoidMagenta, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Connect directly to a peakpool instance over Noise-encrypted TCP. " +
                        "EXPERIMENTAL — requires you to run peakpool on your own server " +
                        "(proprietary, Docker image only). No bridge needed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text("POOL HOST", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = poolHost,
                    onValueChange = { poolHost = it },
                    placeholder = { Text("pool.your-vps.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    colors = noidFieldColors(),
                )

                Text("POOL PORT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = poolPort,
                    onValueChange = { v -> poolPort = v.filter { it.isDigit() }.take(5) },
                    placeholder = { Text("3333") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    colors = noidFieldColors(),
                )

                Text("POOL STATIC PUBKEY (Noise auth)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = poolPubkey,
                    onValueChange = { poolPubkey = it.filter { c -> c in "0123456789abcdefABCDEF" } },
                    placeholder = { Text("64 hex chars (32 bytes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    colors = noidFieldColors(),
                )
                Text(
                    "Get this from peakpool's logs when it starts. " +
                        "Authenticates the pool — prevents MITM.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        // ─── THREADS ─────────────────────────────────────────────────
        ScanlineBox(borderColor = NoidGreen) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("MINING THREADS", color = NoidGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = threads,
                    onValueChange = { v -> threads = v.filter { it.isDigit() }.take(2) },
                    placeholder = { Text("0 = all cores") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
                Text(
                    "0 = use all CPU cores. Set 2-4 for battery saving on phones.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        // ─── SETUP GUIDE ─────────────────────────────────────────────
        ScanlineBox(borderColor = NoidAmber) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("SETUP GUIDE", color = NoidAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Parano1d mining model is DIFFERENT from BTC. The worker " +
                        "needs a node that provides proven templates. There is no " +
                        "public pool — you run your own.\n\n" +
                        "QUICK SETUP (VPS):\n" +
                        "1. SSH to your VPS (Ubuntu 22+)\n" +
                        "2. Download parano1d from parano1d.org/downloads\n" +
                        "3. Run: parano1d --mode miner --miner-address o1...\n" +
                        "4. Get mining key: cat ~/.parano1d/mining.key\n" +
                        "5. In this app: set RPC URL = http://YOUR-VPS:9601\n" +
                        "6. Set Mining Key = the key from step 4\n" +
                        "7. Tap START MINING — app connects directly to VPS\n\n" +
                        "For peakpool (Stratum V2):\n" +
                        "1. cd deployments/parano1d && cp .env.example .env\n" +
                        "2. Set your wallet + node RPC token in .env\n" +
                        "3. docker compose up -d\n" +
                        "4. Get pool pubkey from: docker compose logs pool\n" +
                        "5. In this app: set Pool Host/Port/Pubkey above\n" +
                        "6. Tap START MINING in POOL mode",
                    color = NoidGreen,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        NeonButton("SAVE", {
            vault.rpcUrl = rpcUrl.ifBlank { "http://127.0.0.1:9601" }
            vault.miningKey = miningKey
            vault.poolHost = poolHost
            vault.poolPort = poolPort.toIntOrNull() ?: 3333
            vault.poolPubkey = poolPubkey
            vault.threads = threads.toIntOrNull() ?: 0
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
        }, style = NeonButtonStyle.Primary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun noidFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NoidGreen,
    unfocusedTextColor = NoidGreen,
    focusedBorderColor = NoidGreen,
    unfocusedBorderColor = Color(0xFF444444),
    focusedLabelColor = NoidGreen,
    cursorColor = NoidGreen,
)
