// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.component.NeonButton
import org.secretarrow.noidmobile.ui.component.NeonButtonStyle
import org.secretarrow.noidmobile.ui.component.ScanlineBox

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val vault = remember { Vault(ctx) }

    var rpcUrl by remember { mutableStateOf(vault.rpcUrl) }
    var poolUrl by remember { mutableStateOf(vault.poolBridgeUrl) }
    var miningKey by remember { mutableStateOf(vault.miningKey) }
    var threads by remember { mutableStateOf(vault.threads.toString()) }

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

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("RPC URL", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                OutlinedTextField(
                    value = rpcUrl,
                    onValueChange = { rpcUrl = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
            }
        }

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("POOL BRIDGE URL (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                OutlinedTextField(
                    value = poolUrl,
                    onValueChange = { poolUrl = it },
                    placeholder = { Text("/?XTransformPort=3030") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
                Text(
                    "Reach the Stratum V2 bridge through the gateway with ?XTransformPort=3030.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("MINING KEY (Bearer token)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                OutlinedTextField(
                    value = miningKey,
                    onValueChange = { miningKey = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                Text("Leave blank for solo mining on 127.0.0.1.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("THREADS (0 = all cores)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                OutlinedTextField(
                    value = threads,
                    onValueChange = { v -> threads = v.filter { it.isDigit() }.take(2) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
            }
        }

        NeonButton("SAVE", {
            vault.rpcUrl = rpcUrl.ifBlank { "http://127.0.0.1:9601" }
            vault.poolBridgeUrl = poolUrl
            vault.miningKey = miningKey
            vault.threads = threads.toIntOrNull() ?: 0
        }, style = NeonButtonStyle.Primary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun noidFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NoidGreen,
    unfocusedTextColor = NoidGreen,
    focusedBorderColor = NoidGreen,
    unfocusedBorderColor = Color(0xFF444444),
    focusedLabelTextColor = NoidGreen,
    cursorColor = NoidGreen,
)
