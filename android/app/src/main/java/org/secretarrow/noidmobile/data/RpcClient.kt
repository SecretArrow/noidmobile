// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.secretarrow.noidmobile.data.model.AddressInfo
import org.secretarrow.noidmobile.data.model.BlockTemplateResponse
import org.secretarrow.noidmobile.data.model.ChainInfo
import org.secretarrow.noidmobile.data.model.JsonRpcRequest
import org.secretarrow.noidmobile.data.model.JsonRpcResponse
import org.secretarrow.noidmobile.data.model.MiningInfo
import org.secretarrow.noidmobile.data.model.NodeStatus
import org.secretarrow.noidmobile.data.model.WalletAddressInfo
import org.secretarrow.noidmobile.data.model.WalletBalance
import org.secretarrow.noidmobile.data.model.WalletHistoryEntry
import org.secretarrow.noidmobile.data.model.WalletSendPlan
import org.secretarrow.noidmobile.data.model.WalletSendResult
import org.secretarrow.noidmobile.data.model.WalletStatus
import org.secretarrow.noidmobile.data.model.WalletUtxoInfo
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 2.0 client for the Parano1d node (`paranoid_*` methods).
 *
 * Auth: optional `Authorization: Bearer <mining-key>` header — not
 * needed for solo miners using the default 127.0.0.1 binding.
 *
 * Never logs the mining key or any wallet secret.
 */
class RpcClient(
    private val url: String,
    private val miningKey: String? = null,
    client: OkHttpClient? = null,
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private inline fun <reified P, reified R> call(method: String, params: P): R {
        val req = JsonRpcRequest(method = method, params = params)
        val body = json.encodeToString(JsonRpcRequest.serializer(kotlinx.serialization.serializer<P>()), req)
            .toRequestBody("application/json".toMediaType())

        val builder = Request.Builder().url(url).post(body)
        if (!miningKey.isNullOrEmpty()) {
            builder.header("Authorization", "Bearer $miningKey")
        }

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RpcException("HTTP ${resp.code}: ${text.take(200)}")
            }
            val envelope = json.decodeFromString<JsonRpcResponse<R>>(text)
            envelope.error?.let { throw RpcException("RPC ${it.code}: ${it.message}") }
            return envelope.result ?: throw RpcException("RPC returned null result")
        }
    }

    // -- Mining -----------------------------------------------------------

    suspend fun getBlockTemplate(coinbase: String = ""): BlockTemplateResponse =
        withContext(Dispatchers.IO) {
            call<String, BlockTemplateResponse>("paranoid_getBlockTemplate", coinbase)
        }

    suspend fun submitBlock(templateId: String, nonceLeHex: String): String =
        withContext(Dispatchers.IO) {
            // paranoid_submitBlock(template_id, nonce_hex) -> string (block hash)
            call<List<String>, String>("paranoid_submitBlock", listOf(templateId, nonceLeHex))
        }

    // -- Wallet -----------------------------------------------------------

    suspend fun walletStatus(): WalletStatus = withContext(Dispatchers.IO) {
        call<JsonElement, WalletStatus>("paranoid_walletStatus", buildJsonObject {})
    }
    suspend fun walletGetBalance(): WalletBalance = withContext(Dispatchers.IO) {
        call<JsonElement, WalletBalance>("paranoid_walletGetBalance", buildJsonObject {})
    }
    suspend fun walletListUtxos(): List<WalletUtxoInfo> = withContext(Dispatchers.IO) {
        call<JsonElement, List<WalletUtxoInfo>>("paranoid_walletListUtxos", buildJsonObject {})
    }
    suspend fun walletHistory(): List<WalletHistoryEntry> = withContext(Dispatchers.IO) {
        call<JsonElement, List<WalletHistoryEntry>>("paranoid_walletHistory", buildJsonObject {})
    }
    suspend fun walletNextAddress(): WalletAddressInfo = withContext(Dispatchers.IO) {
        call<JsonElement, WalletAddressInfo>("paranoid_walletNextAddress", buildJsonObject {})
    }
    suspend fun walletListAddresses(): List<WalletAddressInfo> = withContext(Dispatchers.IO) {
        call<JsonElement, List<WalletAddressInfo>>("paranoid_walletListAddresses", buildJsonObject {})
    }
    suspend fun walletActiveAddress(): WalletAddressInfo = withContext(Dispatchers.IO) {
        call<JsonElement, WalletAddressInfo>("paranoid_walletActiveAddress", buildJsonObject {})
    }
    suspend fun walletSetActiveAddress(index: Int): WalletAddressInfo = withContext(Dispatchers.IO) {
        call<JsonElement, WalletAddressInfo>(
            "paranoid_walletSetActiveAddress",
            buildJsonObject { put("index", index) }
        )
    }
    suspend fun walletSend(toAddress: String, amountMicroNoid: Long, feeMicroNoid: Long): WalletSendResult =
        withContext(Dispatchers.IO) {
            call<JsonElement, WalletSendResult>(
                "paranoid_walletSend",
                buildJsonObject {
                    put("to_address", toAddress)
                    put("amount_micronoid", amountMicroNoid.toString())
                    put("fee_micronoid", feeMicroNoid.toString())
                }
            )
        }
    suspend fun walletPlanSend(toAddress: String, amountMicroNoid: Long, feeMicroNoid: Long): WalletSendPlan =
        withContext(Dispatchers.IO) {
            call<JsonElement, WalletSendPlan>(
                "paranoid_walletPlanSend",
                buildJsonObject {
                    put("to_address", toAddress)
                    put("amount_micronoid", amountMicroNoid.toString())
                    put("fee_micronoid", feeMicroNoid.toString())
                }
            )
        }
    suspend fun validateAddress(address: String): AddressInfo = withContext(Dispatchers.IO) {
        call<String, AddressInfo>("paranoid_validateAddress", address)
    }

    // -- Network ----------------------------------------------------------

    suspend fun blockCount(): Long = withContext(Dispatchers.IO) {
        call<JsonElement, JsonElement>("paranoid_blockCount", buildJsonObject {})
            .jsonPrimitive.content.toLong()
    }
    suspend fun getChainInfo(): ChainInfo = withContext(Dispatchers.IO) {
        call<JsonElement, ChainInfo>("paranoid_getChainInfo", buildJsonObject {})
    }
    suspend fun getMiningInfo(): MiningInfo = withContext(Dispatchers.IO) {
        call<JsonElement, MiningInfo>("paranoid_getMiningInfo", buildJsonObject {})
    }
    suspend fun getNodeStatus(): NodeStatus = withContext(Dispatchers.IO) {
        call<JsonElement, NodeStatus>("paranoid_getNodeStatus", buildJsonObject {})
    }
    suspend fun estimateFee(nOutputs: Int): Long = withContext(Dispatchers.IO) {
        call<JsonElement, JsonElement>(
            "paranoid_estimateFee",
            buildJsonObject { put("n_outputs", nOutputs) }
        ).jsonPrimitive.content.toLong()
    }
}

class RpcException(message: String) : RuntimeException(message) {
    val isStale: Boolean get() = message.contains("stale", ignoreCase = true)
}
