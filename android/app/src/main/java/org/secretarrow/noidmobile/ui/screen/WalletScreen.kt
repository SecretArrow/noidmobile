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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.Vault
import org.secretarrow.noidmobile.data.model.WalletHistoryEntry
import org.secretarrow.noidmobile.pow.NativePow
import org.secretarrow.noidmobile.ui.NoidMono

@OptIn(ExperimentalMaterial3Api::class)
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
    var sendFee by remember { mutableStateOf("") }
    var historyList by remember { mutableStateOf<List<WalletHistoryEntry>>(emptyList()) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var importHex by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Derive address locally from the secret — no node required.
    // Falls back to node RPC for balance/history only.
    LaunchedEffect(secretHex) {
        if (secretHex.length == 64) {
            val derived = withContext(Dispatchers.Default) {
                NativePow.deriveAddress(secretHex)
            }
            address = derived.ifBlank { "(derivation failed — is the secret 64 hex chars?)" }

            scope.launch {
                val s = try { rpc.walletStatus() } catch (_: Exception) { null }
                if (s != null) {
                    balance = ((s.balance_micronoid?.toLongOrNull() ?: 0L) / 1_000_000.0)
                        .let { "%.6f".format(it) } + " NOID"
                    if (!s.address.isNullOrBlank()) address = s.address
                } else {
                    balance = "(node offline — address shown is locally derived)"
                }
            }
        } else {
            address = "(no wallet loaded — tap New wallet)"
            balance = "—"
        }
    }

    // Fetch history once per wallet.
    LaunchedEffect(secretHex) {
        val h = try { rpc.walletHistory() } catch (e: Exception) { null }
        if (h != null) {
            historyList = h
            historyError = null
        } else {
            historyList = emptyList()
            historyError = "(no history — node offline or no transactions)"
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
            showImportSheet = false
            Toast.makeText(ctx, "Wallet imported", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Invalid hex — need 64 chars (0-9a-f)", Toast.LENGTH_LONG).show()
        }
    }

    fun clearWallet() {
        vault.clearWallet()
        secretHex = ""
        address = ""
        balance = "—"
        historyList = emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New wallet") },
                            onClick = { menuOpen = false; newWallet() },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Import") },
                            onClick = { menuOpen = false; showImportSheet = true },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Clear wallet") },
                            onClick = { menuOpen = false; clearWallet() },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
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
            // ── Balance hero card ──────────────────────────────────────
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

            // ── Address card ───────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "ADDRESS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            address,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = NoidMono,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (address.isNotBlank()) copyToClipboard("Address", address) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = { Toast.makeText(ctx, "QR scanner coming soon", Toast.LENGTH_SHORT).show() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan QR")
                        }
                    }
                }
            }

            // ── Recovery code card ─────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "RECOVERY CODE (256-bit spend secret — WRITE THIS DOWN)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = if (secretHex.length != 64) {
                                "(no wallet loaded — tap New wallet)"
                            } else if (showSecret) {
                                secretHex.grouped(8)
                            } else {
                                "●".repeat(64).grouped(8)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = NoidMono,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (secretHex.length == 64) copyToClipboard("Spend Secret", secretHex) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = { showSecret = !showSecret },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (showSecret) "Hide" else "Show")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "WARNING: This is NOT a BIP39 mnemonic. Anyone with this code can spend your funds. " +
                            "BIP39 mnemonic display is planned for a future release.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ── Send section ───────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SEND NOID",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = sendTo,
                        onValueChange = { sendTo = it },
                        label = { Text("Recipient address (o1…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { pasteFromClipboard { sendTo = it.trim() } }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sendAmount,
                            onValueChange = { sendAmount = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Amount (NOID)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            value = sendFee,
                            onValueChange = { sendFee = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Fee (NOID)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    Button(
                        onClick = {
                            val amt = (sendAmount.toDoubleOrNull() ?: 0.0) * 1_000_000
                            if (sendTo.startsWith("o1") && amt > 0) {
                                Toast.makeText(ctx, "Send requires a local node — submit via RPC", Toast.LENGTH_LONG).show()
                                // Submit via node RPC — node holds the wallet secret.
                            } else {
                                Toast.makeText(ctx, "Enter valid o1… address and amount", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SEND", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── History section ────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "HISTORY",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            val text = historyList.joinToString("\n") { e ->
                                "${if (e.direction == "send") "→" else "←"} ${e.amount_micronoid} μNOID  h=${e.height}  ${e.tx_hash.take(12)}…"
                            }.ifBlank { "(no history)" }
                            copyToClipboard("History", text)
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy history")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    if (historyList.isEmpty()) {
                        Text(
                            historyError ?: "(no history)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(min = 32.dp),
                        )
                    } else {
                        // Cap at 50 rows for safe rendering inside the scroll Column.
                        historyList.take(50).forEach { e ->
                            HistoryRow(e)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    // ── Import wallet modal bottom sheet ─────────────────────────────
    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Import wallet from hex",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Paste a 64-character hex spend secret. The wallet address is derived locally via JNI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = importHex,
                    onValueChange = { v -> importHex = v.filter { it in "0123456789abcdefABCDEF " } },
                    label = { Text("64 hex chars (0-9a-f)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            pasteFromClipboard { importHex = it.filter { c -> c in "0123456789abcdefABCDEF" } }
                        }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showImportSheet = false },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    Button(
                        onClick = { importWallet(importHex) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Load")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: WalletHistoryEntry) {
    val isSend = e.direction == "send"
    val amountNoid = ((e.amount_micronoid.toLongOrNull() ?: 0L) / 1_000_000.0)
        .let { "%.6f".format(it) } + " NOID"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isSend) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = if (isSend) "Sent" else "Received",
            tint = if (isSend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            SelectionContainer {
                Text(
                    amountNoid,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = NoidMono,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            SelectionContainer {
                Text(
                    "h=${e.height}  ${e.tx_hash.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = NoidMono,
                )
            }
        }
    }
}

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
