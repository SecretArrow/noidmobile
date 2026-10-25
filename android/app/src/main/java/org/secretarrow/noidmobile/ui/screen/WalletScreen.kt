// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.pow.NativePow
import org.secretarrow.noidmobile.ui.NoidAmber
import org.secretarrow.noidmobile.ui.NoidGreen
import org.secretarrow.noidmobile.ui.NoidMagenta
import org.secretarrow.noidmobile.ui.component.NeonButton
import org.secretarrow.noidmobile.ui.component.NeonButtonStyle
import org.secretarrow.noidmobile.ui.component.ScanlineBox

@Composable
fun WalletScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { Vault(ctx) }
    val rpc = remember { RpcClient(url = vault.rpcUrl, miningKey = vault.miningKey.ifEmpty { null }) }

    var secretHex by remember { mutableStateOf(vault.spendSecretHex ?: "") }
    var address by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("—") }
    var sendTo by remember { mutableStateOf("") }
    var sendAmount by remember { mutableStateOf("") }
    var history by remember { mutableStateOf("") }
    var importHex by remember { mutableStateOf("") }

    // Derive address locally from the secret — no node required.
    // Falls back to node RPC for balance/history only.
    LaunchedEffect(secretHex) {
        if (secretHex.length == 64) {
            // Local derivation via JNI (always works, no network).
            val derived = withContext(Dispatchers.Default) {
                NativePow.deriveAddress(secretHex)
            }
            address = derived.ifBlank { "(derivation failed — is the secret 64 hex chars?)" }

            // Try to fetch balance from node (optional).
            scope.launch {
                val s = try { rpc.walletStatus() } catch (_: Exception) { null }
                if (s != null) {
                    balance = ((s.balance_micronoid?.toLongOrNull() ?: 0L) / 1_000_000.0)
                        .let { "%.6f".format(it) } + " NOID"
                    // Trust the node's address if it returned one (it has the
                    // full derivation pipeline including any network-specific
                    // tweaks).
                    if (!s.address.isNullOrBlank()) address = s.address
                } else {
                    balance = "(node offline — address shown is locally derived)"
                }
            }
        } else {
            address = "(no wallet loaded — tap NEW WALLET)"
            balance = "—"
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun pasteFromClipboard(onPaste: (String) -> Unit) {
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isNotBlank()) onPaste(text)
        else Toast.makeText(ctx, "Clipboard empty", Toast.LENGTH_SHORT).show()
    }

    fun newWallet() {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.toHex()
        secretHex = hex
        vault.spendSecretHex = hex
    }

    fun importWallet(hex: String) {
        val clean = hex.trim().lowercase().filter { it in "0123456789abcdef" }
        if (clean.length == 64) {
            secretHex = clean
            vault.spendSecretHex = clean
            importHex = ""
            Toast.makeText(ctx, "Wallet imported", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Invalid hex — need 64 chars (0-9a-f)", Toast.LENGTH_LONG).show()
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

        // --- ADDRESS + BALANCE with COPY buttons + selectable ---
        ScanlineBox(borderColor = NoidGreen) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("ACTIVE ADDRESS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                SelectionContainer {
                    Text(
                        text = address,
                        color = NoidGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    NeonButton(
                        "COPY ADDR",
                        { if (address.isNotBlank()) copyToClipboard("Address", address) },
                        style = NeonButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    NeonButton(
                        "COPY SECRET",
                        { if (secretHex.length == 64) copyToClipboard("Secret", secretHex) },
                        style = NeonButtonStyle.Accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("BALANCE: $balance", color = NoidGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }

        // --- SECRET with COPY + selectable ---
        ScanlineBox(borderColor = if (secretHex.length != 64) NoidAmber else NoidMagenta) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("RECOVERY CODE (256-bit spend secret — WRITE THIS DOWN)",
                    color = NoidAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                SelectionContainer {
                    Text(
                        text = if (secretHex.length != 64) "(no wallet loaded — tap NEW WALLET)" else secretHex.grouped(8),
                        color = NoidGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    NeonButton(
                        "COPY",
                        { if (secretHex.length == 64) copyToClipboard("Spend Secret", secretHex) },
                        style = NeonButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    NeonButton(
                        "SHOW/HIDE",
                        { /* toggle visibility could be added; for now always shown */ },
                        style = NeonButtonStyle.Accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "WARNING: This is NOT a BIP39 mnemonic. Anyone with this code can spend your funds. " +
                        "BIP39 mnemonic display is planned for a future release.",
                    color = NoidAmber,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // --- NEW / CLEAR ---
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonButton("NEW WALLET", ::newWallet,
                style = NeonButtonStyle.Primary, modifier = Modifier.weight(1f))
            NeonButton("CLEAR", {
                vault.clearWallet()
                secretHex = ""
                address = ""
                balance = "—"
            },
                style = NeonButtonStyle.Warn, modifier = Modifier.weight(1f))
        }

        // --- IMPORT HEX with PASTE button ---
        ScanlineBox(borderColor = NoidMagenta) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("IMPORT WALLET FROM HEX", color = NoidMagenta, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = importHex,
                    onValueChange = { v -> importHex = v.filter { it in "0123456789abcdefABCDEF " } },
                    label = { Text("paste 64 hex chars here") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    NeonButton(
                        "PASTE",
                        { pasteFromClipboard { importHex = it.filter { c -> c in "0123456789abcdefABCDEF" } } },
                        style = NeonButtonStyle.Accent,
                        modifier = Modifier.weight(1f),
                    )
                    NeonButton(
                        "LOAD",
                        { importWallet(importHex) },
                        style = NeonButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // --- SEND with PASTE buttons ---
        ScanlineBox(borderColor = NoidGreen) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("SEND", color = NoidGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = sendTo,
                    onValueChange = { sendTo = it },
                    label = { Text("to address (o1…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = noidFieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    NeonButton(
                        "PASTE ADDR",
                        { pasteFromClipboard { sendTo = it.trim() } },
                        style = NeonButtonStyle.Accent,
                        modifier = Modifier.weight(1f),
                    )
                    NeonButton(
                        "SCAN QR",
                        { Toast.makeText(ctx, "QR scanner coming soon", Toast.LENGTH_SHORT).show() },
                        style = NeonButtonStyle.Accent,
                        modifier = Modifier.weight(1f),
                    )
                }
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
                            Toast.makeText(ctx, "Send requires a local node — submit via RPC", Toast.LENGTH_LONG).show()
                            // Submit via node RPC — node holds the wallet secret.
                        } else {
                            Toast.makeText(ctx, "Enter valid o1… address and amount", Toast.LENGTH_LONG).show()
                        }
                    },
                    style = NeonButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        // --- HISTORY with selectable text + COPY ---
        ScanlineBox(borderColor = NoidGreen) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HISTORY", color = NoidGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    NeonButton(
                        "COPY",
                        { copyToClipboard("History", history) },
                        style = NeonButtonStyle.Accent,
                    )
                }
                LaunchedEffect(Unit) {
                    launch {
                        val h = try { rpc.walletHistory() } catch (_: Exception) { null }
                        history = h?.joinToString("\n") { e ->
                            "${if (e.direction == "send") "→" else "←"} ${e.amount_micronoid} μNOID  h=${e.height}  ${e.tx_hash.take(12)}…"
                        } ?: "(no history — node offline or no transactions)"
                    }
                }
                SelectionContainer {
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
