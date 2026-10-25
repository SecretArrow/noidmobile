// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.SecureRandom
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

@Composable
fun WalletScreen() {
    val ctx = LocalContext.current
    val vault = remember { Vault(ctx) }
    val rpc = remember { RpcClient(url = vault.rpcUrl, miningKey = vault.miningKey.ifEmpty { null }) }

    var secretHex by remember { mutableStateOf(vault.spendSecretHex ?: "") }
    var address by remember { mutableStateOf("—") }
    var balance by remember { mutableStateOf("—") }
    var sendTo by remember { mutableStateOf("") }
    var sendAmount by remember { mutableStateOf("") }
    var history by remember { mutableStateOf("") }

    LaunchedEffect(secretHex) {
        if (secretHex.isBlank()) return@LaunchedEffect
        launch {
            val s = try { rpc.walletStatus() } catch (_: Exception) { null }
            address = s?.address ?: "—"
            balance = ((s?.balance_micronoid?.toLongOrNull() ?: 0L) / 1_000_000.0).let { "%.6f".format(it) } + " NOID"
        }
    }

    fun newWallet() {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.toHex()
        secretHex = hex
        vault.spendSecretHex = hex
    }

    fun importWallet(hex: String) {
        if (hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" }) {
            secretHex = hex
            vault.spendSecretHex = hex
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
            "> WALLET",
            color = NoidGreen,
            fontFamily = MaterialTheme.typography.headlineMedium.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("ACTIVE ADDRESS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(address, color = NoidGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("BALANCE: $balance", color = NoidGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }

        ScanlineBox(borderColor = if (secretHex.isBlank()) NoidAmber else NoidMagenta) {
            Column {
                Text("RECOVERY CODE (256-bit spend secret — WRITE THIS DOWN)",
                    color = NoidAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (secretHex.isBlank()) "(no wallet loaded — tap NEW WALLET)" else secretHex.grouped(8),
                    color = NoidGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "WARNING: This is NOT a BIP39 mnemonic. Anyone with this code can spend your funds. " +
                        "BIP39 mnemonic display is planned for a future release.",
                    color = NoidAmber,
                    fontSize = 10.sp,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("NEW WALLET", ::newWallet,
                style = NeonButtonStyle.Primary, modifier = Modifier.weight(1f))
            NeonButton("CLEAR", { vault.clearWallet(); secretHex = "" },
                style = NeonButtonStyle.Warn, modifier = Modifier.weight(1f))
        }

        OutlinedTextField(
            value = secretHex,
            onValueChange = { v -> if (v.length <= 64 && v.all { it in "0123456789abcdefABCDEF" }) secretHex = v },
            label = { Text("IMPORT HEX (64 chars)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NoidGreen,
                unfocusedTextColor = NoidGreen,
                focusedBorderColor = NoidGreen,
                unfocusedBorderColor = Color(0xFF444444),
                focusedLabelTextColor = NoidGreen,
                cursorColor = NoidGreen,
            ),
        )
        NeonButton("LOAD FROM HEX", { importWallet(secretHex) },
            style = NeonButtonStyle.Accent, modifier = Modifier.fillMaxWidth())

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("SEND", color = NoidGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = sendTo,
                    onValueChange = { sendTo = it },
                    label = { Text("to address (o1…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
                OutlinedTextField(
                    value = sendAmount,
                    onValueChange = { sendAmount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("amount (NOID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
                NeonButton(
                    "SEND",
                    {
                        val amt = (sendAmount.toDoubleOrNull() ?: 0.0) * 1_000_000
                        if (sendTo.startsWith("o1") && amt > 0) {
                            // Submit via node RPC — node holds the wallet secret.
                            // We don't expose the secret to the wallet screen beyond display.
                        }
                    },
                    style = NeonButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        ScanlineBox(borderColor = NoidGreen) {
            Column {
                Text("HISTORY", color = NoidGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                LaunchedEffect(Unit) {
                    launch {
                        val h = try { rpc.walletHistory() } catch (_: Exception) { null }
                        history = h?.joinToString("\n") { e ->
                            "${if (e.direction == "send") "→" else "←"} ${e.amount_micronoid} μNOID  h=${e.height}  ${e.tx_hash.take(12)}…"
                        } ?: "(no history)"
                    }
                }
                Text(
                    text = history.ifBlank { "(no history)" },
                    color = NoidGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(top = 4.dp),
                )
            }
        }
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

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

private fun String.grouped(n: Int): String =
    chunked(n).joinToString(" ")
