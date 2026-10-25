// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Bridge protocol messages (JSON, newline-delimited) over the WebSocket
 * to the Stratum V2 Noise bridge mini-service.
 *
 * Bridge → Miner:
 *  - {"type":"template",        ...}
 *  - {"type":"submit_result",   ...}
 *  - {"type":"status",          ...}
 *  - {"type":"log",             ...}
 * Miner → Bridge:
 *  - {"type":"submit","template_id":"...","nonce_hex":"..."}
 *
 * The bridge runs as a Next.js mini-service on the same host (port 3030),
 * reachable from the app via the gateway with `?XTransformPort=3030`.
 */
sealed interface BridgeMessage {
    val type: String

    data class Template(
        val template_id: String,
        val pow_fields_hex: String,
        val nonce_field_index: Int,
        val difficulty_target_hex: String,
        val height: Long,
        val expires_in_seconds: Long,
        val n_txs: Int,
    ) : BridgeMessage { override val type = "template" }

    data class SubmitResult(val ok: Boolean, val hash: String? = null, val error: String? = null) : BridgeMessage {
        override val type = "submit_result"
    }

    data class Status(val connected: Boolean, val pool: String? = null) : BridgeMessage {
        override val type = "status"
    }

    data class Log(val level: String, val message: String) : BridgeMessage {
        override val type = "log"
    }

    data class Submit(val template_id: String, val nonce_hex: String) : BridgeMessage {
        override val type = "submit"
    }
}

/**
 * WebSocket client for the Stratum V2 bridge. Reconnects automatically.
 *
 * NEVER include any wallet secret or mining key in messages sent over
 * this socket — the bridge authenticates with the pool out-of-band via
 * its own static Noise key.
 */
class StratumClient(
    private val bridgeUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    private val _messages = MutableSharedFlow<BridgeMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<BridgeMessage> = _messages.asSharedFlow()

    private var socket: WebSocket? = null
    @Volatile private var shouldReconnect = false

    fun start() {
        shouldReconnect = true
        connect()
    }

    fun stop() {
        shouldReconnect = false
        socket?.close(1000, "bye")
        socket = null
    }

    private fun connect() {
        val req = Request.Builder().url(bridgeUrl).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _messages.tryEmit(BridgeMessage.Status(connected = true))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { _messages.tryEmit(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _messages.tryEmit(BridgeMessage.Status(connected = false))
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _messages.tryEmit(
                    BridgeMessage.Log(level = "error", message = t.message ?: "ws failure")
                )
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        Thread {
            Thread.sleep(2000)
            if (shouldReconnect) connect()
        }.apply { isDaemon = true; name = "stratum-reconnect" }.start()
    }

    fun submit(templateId: String, nonceLeHex: String) {
        val payload =
            """{"type":"submit","template_id":"$templateId","nonce_hex":"$nonceLeHex"}"""
        socket?.send(payload)
    }

    private fun parse(text: String): BridgeMessage? {
        // Lightweight JSON extraction — avoids a full JSON parse per
        // message. The bridge only sends a handful of fixed shapes.
        val map = parseJsonShallow(text) ?: return null
        return when (map["type"]) {
            "template" -> BridgeMessage.Template(
                template_id = map["template_id"].orEmpty(),
                pow_fields_hex = map["pow_fields_hex"].orEmpty(),
                nonce_field_index = map["nonce_field_index"]?.toIntOrNull() ?: 10,
                difficulty_target_hex = map["difficulty_target_hex"].orEmpty(),
                height = map["height"]?.toLongOrNull() ?: 0L,
                expires_in_seconds = map["expires_in_seconds"]?.toLongOrNull() ?: 30L,
                n_txs = map["n_txs"]?.toIntOrNull() ?: 0,
            )
            "submit_result" -> BridgeMessage.SubmitResult(
                ok = map["ok"]?.toBooleanStrictOrNull() == true,
                hash = map["hash"],
                error = map["error"],
            )
            "status" -> BridgeMessage.Status(
                connected = map["connected"]?.toBooleanStrictOrNull() == true,
                pool = map["pool"],
            )
            "log" -> BridgeMessage.Log(
                level = map["level"] ?: "info",
                message = map["message"].orEmpty(),
            )
            else -> null
        }
    }

    /** Minimal flat JSON string→string parser, sufficient for our bridge
     *  messages which never nest objects. */
    private fun parseJsonShallow(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        val re = Regex(""""([^"\\]+)"\s*:\s*(?:"([^"\\]*)"|(true|false|[-0-9]+))""")
        for (m in re.findAll(text)) {
            val key = m.groupValues[1]
            val value = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3]
            out[key] = value
        }
        return out
    }
}
