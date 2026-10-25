// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.pow

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.secretarrow.noidmobile.data.BridgeMessage
import org.secretarrow.noidmobile.data.MinerMode
import org.secretarrow.noidmobile.data.RpcClient
import org.secretarrow.noidmobile.data.RpcException
import org.secretarrow.noidmobile.data.StratumClient
import org.secretarrow.noidmobile.data.Vault
import java.security.SecureRandom
import java.time.Instant

/**
 * Mining engine. Implements the EXACT protocol from the worklog:
 *
 *   1. `paranoid_getBlockTemplate(miner_address)` → decode `pow_fields_hex`
 *      (256 hex → 16 × 16-byte LE u128 fields).
 *   2. Search nonces in parallel (coroutines) calling
 *      `Poseidon2bHasher.hash(powFields, nonce)`.
 *   3. Check `le256_lt(hash, target)` via `Poseidon2bHasher.checkTarget`.
 *   4. `paranoid_submitBlock(template_id, nonce_le_hex)`.
 *
 * Template expires in `expires_in_seconds`; refresh when stale. Uses a
 * random start nonce so multiple miners on the same template don't
 * collide. Same logic as upstream `noid_extminer/src/main.rs`.
 *
 * In SOLO mode the engine talks directly to the local node via JSON-RPC.
 * In POOL mode it talks to the Stratum V2 bridge via WebSocket — the
 * bridge handles the Noise handshake with the pool, the engine only
 * does Poseidon2b hashing.
 */
object MinerEngine {

    private val _state = MutableStateFlow(MinerState())
    val state: StateFlow<MinerState> = _state.asStateFlow()

    private val _log = MutableSharedFlow<LogLine>(extraBufferCapacity = 256)
    val log: SharedFlow<LogLine> = _log.asSharedFlow()

    private val controlMutex = Mutex()
    @Volatile private var running = false

    /** Begin the mining loop. Returns when stopped. */
    suspend fun runLoop(context: Context, onProgress: (hashrateHps: Double, status: String) -> Unit = { _, _ -> }) {
        val vault = Vault(context)
        if (!controlMutex.tryLock()) return
        try {
            running = true
            _state.value = _state.value.copy(running = true)
            when (vault.minerMode) {
                MinerMode.SOLO -> runSolo(vault, onProgress)
                MinerMode.POOL -> runPool(vault, onProgress)
            }
        } finally {
            running = false
            _state.value = _state.value.copy(running = false, hashrateHps = 0.0)
            controlMutex.unlock()
        }
    }

    fun stop() {
        running = false
        _state.value = _state.value.copy(running = false)
    }

    // -------------------------------------------------------------------
    // SOLO — direct JSON-RPC to the local node
    // -------------------------------------------------------------------

    private suspend fun runSolo(vault: Vault, onProgress: (Double, String) -> Unit) {
        val rpc = RpcClient(url = vault.rpcUrl, miningKey = vault.miningKey.ifEmpty { null })
        log("info", "paranoid-miner  rpc=${vault.rpcUrl}  mode=SOLO")
        val threads = if (vault.threads > 0) vault.threads else Runtime.getRuntime().availableProcessors()
        log("info", "threads=$threads  backend=${backendLabel()}")

        var lastHeight = 0L
        val rngStartNonce = SecureRandom().nextLong() and 0x7FFF_FFFF_FFFF_FFFFL

        while (running) {
            val template = try {
                rpc.getBlockTemplate("")
            } catch (e: Exception) {
                log("error", "template fetch failed: ${e.message} — retrying in 2s")
                delay(2000); continue
            }

            if (template.nonce_field_index != 10) {
                log("error", "unexpected nonce_field_index=${template.nonce_field_index}")
                delay(2000); continue
            }
            if (template.height == lastHeight) {
                delay(500); continue
            }

            log("info", "┌─ h=${template.height}  txs=${template.n_txs}  expires=${template.expires_in_seconds}s")
            _state.value = _state.value.copy(
                height = template.height,
                targetHex = template.difficulty_target_hex,
                templateId = template.template_id,
            )

            val hasher = Poseidon2bHasher(template.pow_fields_hex, template.nonce_field_index)
            val deadlineMs = System.currentTimeMillis() + (template.expires_in_seconds.coerceAtMost(Int.MAX_VALUE.toLong()) - 1) * 1000L

            val t0 = System.nanoTime()
            val hashesTried = java.util.concurrent.atomic.AtomicLong(0)

            val solution = searchNonceParallel(
                hasher = hasher,
                targetLeHex = template.difficulty_target_hex,
                startNonce = rngStartNonce,
                threads = threads,
                deadlineMs = deadlineMs,
                hashesTried = hashesTried,
            )

            val elapsed = (System.nanoTime() - t0).coerceAtLeast(1L)
            val hps = hashesTried.get().toDouble() / (elapsed / 1_000_000_000.0)
            _state.value = _state.value.copy(hashrateHps = hps)
            onProgress(hps, "SOLO  h=${template.height}")

            if (solution == null) {
                log("warn", "└─ EXPIRED  refreshing node-owned template")
                continue
            }

            if (System.currentTimeMillis() >= deadlineMs) {
                log("warn", "└─ EXPIRED  solution arrived too late")
                continue
            }

            val nonceLeHex = Poseidon2bHasher.longToLeHex(solution)
            try {
                val hash = rpc.submitBlock(template.template_id, nonceLeHex)
                lastHeight = template.height
                _state.value = _state.value.copy(blocksFound = _state.value.blocksFound + 1)
                log("info", "└─ SOLVED  nonce=$solution  hps=${"%.1f".format(hps)}  hash=${hash.take(20)}…")
            } catch (e: RpcException) {
                if (e.isStale) log("warn", "└─ STALE  parent lost race")
                else log("error", "└─ submit failed: ${e.message}")
            } catch (e: Exception) {
                log("error", "└─ submit failed: ${e.message}")
            }
            delay(500)
        }
    }

    // -------------------------------------------------------------------
    // POOL — Stratum V2 bridge over WebSocket
    // -------------------------------------------------------------------

    private suspend fun runPool(vault: Vault, onProgress: (Double, String) -> Unit) {
        val url = vault.poolBridgeUrl.ifBlank {
            log("error", "no pool bridge URL configured — falling back to SOLO")
            runSolo(vault, onProgress); return
        }
        val bridge = StratumClient(url)
        bridge.start()
        log("info", "paranoid-miner  bridge=$url  mode=POOL")

        val threads = if (vault.threads > 0) vault.threads else Runtime.getRuntime().availableProcessors()
        val rngStartNonce = SecureRandom().nextLong() and 0x7FFF_FFFF_FFFF_FFFFL

        // Listen to bridge messages until the engine is stopped.
        coroutineScope {
            val listener = launch {
                bridge.messages.collect { msg ->
                    handleBridgeMessage(msg, bridge, vault, onProgress, threads, rngStartNonce)
                }
            }
            try {
                while (running) delay(500)
            } finally {
                listener.cancel()
                bridge.stop()
            }
        }
    }

    private suspend fun handleBridgeMessage(
        msg: BridgeMessage,
        bridge: StratumClient,
        vault: Vault,
        onProgress: (Double, String) -> Unit,
        threads: Int,
        startNonce: Long,
    ) {
        when (msg) {
            is BridgeMessage.Template -> {
                log("info", "┌─ h=${msg.height}  txs=${msg.n_txs}  expires=${msg.expires_in_seconds}s (pool)")
                _state.value = _state.value.copy(
                    height = msg.height,
                    targetHex = msg.difficulty_target_hex,
                    templateId = msg.template_id,
                )
                val hasher = Poseidon2bHasher(msg.pow_fields_hex, msg.nonce_field_index)
                val deadlineMs = System.currentTimeMillis() + (msg.expires_in_seconds.coerceAtMost(Int.MAX_VALUE.toLong()) - 1) * 1000L
                val hashesTried = java.util.concurrent.atomic.AtomicLong(0)
                val solution = searchNonceParallel(
                    hasher = hasher,
                    targetLeHex = msg.difficulty_target_hex,
                    startNonce = startNonce,
                    threads = threads,
                    deadlineMs = deadlineMs,
                    hashesTried = hashesTried,
                )
                solution?.let {
                    val nonceLeHex = Poseidon2bHasher.longToLeHex(it)
                    bridge.submit(msg.template_id, nonceLeHex)
                    log("info", "└─ SUBMIT  nonce=$it (pool)")
                }
            }
            is BridgeMessage.SubmitResult -> {
                if (msg.ok) {
                    _state.value = _state.value.copy(blocksFound = _state.value.blocksFound + 1)
                    log("info", "└─ ACCEPTED  hash=${msg.hash?.take(20) ?: "?"}…")
                } else {
                    log("warn", "└─ REJECTED  ${msg.error ?: "no error"}")
                }
            }
            is BridgeMessage.Status -> {
                log(if (msg.connected) "info" else "warn",
                    if (msg.connected) "pool connected${msg.pool?.let { ": $it" } ?: ""}"
                    else "pool disconnected")
                _state.value = _state.value.copy(poolConnected = msg.connected)
            }
            is BridgeMessage.Log -> log(msg.level, msg.message)
            else -> Unit
        }
    }

    // -------------------------------------------------------------------
    // Parallel nonce search — mirrors upstream rayon split.
    // -------------------------------------------------------------------

    private suspend fun searchNonceParallel(
        hasher: Poseidon2bHasher,
        targetLeHex: String,
        startNonce: Long,
        threads: Int,
        deadlineMs: Long,
        hashesTried: java.util.concurrent.atomic.AtomicLong,
    ): Long? = coroutineScope {
        val perThread = CHUNK_SIZE / threads
        var chunkStart = startNonce

        while (running) {
            if (System.currentTimeMillis() >= deadlineMs) return@coroutineScope null

            val chunkEnd = chunkStart + CHUNK_SIZE
            val deferreds = (0 until threads).map { tid ->
                async(Dispatchers.Default) {
                    val ts = chunkStart + tid.toLong() * perThread
                    val te = minOf(ts + perThread, chunkEnd)
                    if (ts >= te) return@async null

                    var nonce = ts
                    while (nonce < te) {
                        if (System.currentTimeMillis() >= deadlineMs) return@async null
                        val n = minOf((te - nonce).toInt(), Poseidon2bHasher.BATCH_SIZE).coerceAtLeast(1)
                        val digests = hasher.batchHashHex(nonce, n)
                        hashesTried.addAndGet(n.toLong())
                        for ((i, hash) in digests.withIndex()) {
                            val bytes = hexToBytes(hash)
                            if (NativePow.checkTargetNative(bytes, targetLeHex)) {
                                return@async nonce + i
                            }
                        }
                        nonce += n.toLong()
                    }
                    null
                }
            }

            val solution = deferreds.firstNotNullOfOrNull { it.await() }
            if (solution != null) return@coroutineScope solution
            chunkStart = chunkStart + CHUNK_SIZE
        }
        null
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun backendLabel(): String =
        if (NativePow.openclAvailableNative()) "OpenCL(GPU)+CPU" else "CPU(libnoidmobile_pow.so)"

    private fun log(level: String, message: String) {
        _log.tryEmit(LogLine(Instant.now().toString(), level, message))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                      Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** Mirrors upstream `CHUNK_SIZE = 10_000_000`. */
    private const val CHUNK_SIZE = 10_000_000L
}

data class MinerState(
    val running: Boolean = false,
    val mode: MinerMode = MinerMode.SOLO,
    val height: Long = 0L,
    val hashrateHps: Double = 0.0,
    val blocksFound: Long = 0L,
    val targetHex: String = "",
    val templateId: String = "",
    val poolConnected: Boolean = false,
)

data class LogLine(val ts: String, val level: String, val message: String)
