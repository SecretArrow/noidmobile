// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.data.model

import kotlinx.serialization.Serializable

/**
 * Wire-format models for the Parano1d JSON-RPC API. Method names and
 * field names match the upstream `noid_rpc` crate exactly; JSON field
 * casing is snake_case to match the node.
 */

@Serializable
data class BlockTemplateResponse(
    val template_id: String,
    val pow_fields_hex: String,
    val nonce_field_index: Int,
    val difficulty_target_hex: String,
    val height: Long,
    val expires_in_seconds: Long,
    val n_txs: Int,
)

@Serializable
data class WalletStatus(
    val address: String,
    val balance_micronoid: String,
    val utxo_count: Long,
    val address_count: Long = 0L,
)

@Serializable
data class WalletBalance(
    val confirmed_micronoid: String,
    val pending_micronoid: String = "0",
)

@Serializable
data class WalletUtxoInfo(
    val outpoint: String,
    val amount_micronoid: String,
    val address: String,
    val confirmed: Boolean,
)

@Serializable
data class WalletHistoryEntry(
    val direction: String,         // "send" | "receive"
    val amount_micronoid: String,
    val height: Long,
    val tx_hash: String,
    val address: String? = null,
    val peer_address: String? = null,
)

@Serializable
data class WalletAddressInfo(
    val address: String,
    val index: Int,
    val is_active: Boolean,
)

@Serializable
data class WalletSendResult(
    val tx_hash: String,
    val submitted: Boolean,
)

@Serializable
data class WalletSendPlan(
    val total_micronoid: String,
    val fee_micronoid: String,
    val inputs: List<String> = emptyList(),
)

@Serializable
data class ChainInfo(
    val network: String,
    val chain_id: String,
    val height: Long,
    val block_time_seconds: Long,
)

@Serializable
data class MiningInfo(
    val height: Long,
    val difficulty_bits: Long,
    val network_hashrate: String,
    val block_reward_micronoid: String,
)

@Serializable
data class NodeStatus(
    val connected: Boolean,
    val peers: Int,
    val synced: Boolean,
    val version: String,
)

@Serializable
data class AddressInfo(
    val valid: Boolean,
    val address: String,
)

// ---------------------------------------------------------------------------
// JSON-RPC envelope
// ---------------------------------------------------------------------------

@Serializable
data class JsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val id: Int = 1,
    val method: String,
    val params: T,
)

@Serializable
data class JsonRpcResponse<T>(
    val result: T? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
)
