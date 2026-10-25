// SPDX-License-Identifier: Apache-2.0
// Parano1d protocol types — mirrors noid_rpc/src/types.rs and noid_extminer/src/main.rs
// All amounts in μNOID (1 NOID = 1,000,000 μNOID). Addresses are bech32m with HRP "o1".

// ---------------------------------------------------------------------------
// Mining (external miner protocol)
// ---------------------------------------------------------------------------

/** Response from `paranoid_getBlockTemplate`. */
export interface BlockTemplateResponse {
  /** Opaque single-use node capability. Submitted back with the nonce. */
  template_id: string;
  /** 256 hex chars = 16 × 16-byte LE u128 Poseidon2b PoW header fields. */
  pow_fields_hex: string;
  /** Canonical nonce lane. Always 10 in v2.1. */
  nonce_field_index: number;
  /** 64 hex chars = 32-byte LE difficulty target. Valid if hash < target. */
  difficulty_target_hex: string;
  /** Candidate block height (u64). */
  height: number;
  /** Seconds before the node discards this template. */
  expires_in_seconds: number;
  /** Number of transactions in the candidate block. */
  n_txs: number;
}

// ---------------------------------------------------------------------------
// Chain / node status
// ---------------------------------------------------------------------------

export interface ChainInfo {
  tip_height: number;
  tip_hash: string;
  difficulty_bits: number;
  active_utxos: number;
  block_target_seconds: number;
}

export interface MiningInfo {
  difficulty_bits: number;
  block_reward_micronoid: number;
  history_step_height: number;
  next_block_subsidy_micronoid: number;
  network_hashrate_estimate: number;
}

export interface NodeStatus {
  synced: boolean;
  mining: boolean;
  cpu_backend: string;
  worker_pool_threads: number;
  peer_count: number;
  uptime_seconds: number;
}

export interface BlockHeaderInfo {
  height: number;
  hash: string;
  parent_hash: string;
  timestamp: number;
  difficulty_bits: number;
  n_txs: number;
  reward_micronoid: number;
  miner_address: string;
}

export interface SlotInfo {
  slot_index: number;
  owner: string;
  value_micronoid: number;
  creation_id: number;
  spent: boolean;
}

export interface StateInfo {
  capacity: number;
  filled: number;
  fill_percent: number;
  bytes_on_disk: number;
}

// ---------------------------------------------------------------------------
// Wallet
// ---------------------------------------------------------------------------

export interface WalletStatus {
  address: string;
  balance_micronoid: number;
  pending_micronoid: number;
  utxo_count: number;
  synced: boolean;
}

export interface WalletBalance {
  confirmed_micronoid: number;
  pending_micronoid: number;
  total_micronoid: number;
}

export interface WalletAddressInfo {
  index: number;
  address: string;
  is_active: boolean;
  balance_micronoid: number;
  utxo_count: number;
}

export interface WalletUtxoInfo {
  slot_index: number;
  value_micronoid: number;
  creation_id: number;
  confirmations: number;
}

export interface WalletUtxoSnapshot {
  revision: string;
  utxos: WalletUtxoInfo[];
}

export interface WalletHistoryEntry {
  txid: string;
  direction: "received" | "sent" | "mined" | "fee";
  amount_micronoid: number;
  fee_micronoid: number;
  height: number;
  timestamp: number;
  counterparty: string;
  confirmations: number;
}

export interface WalletSendPlan {
  inputs: Array<{ slot_index: number; value_micronoid: number }>;
  outputs: Array<{ to_address: string; value_micronoid: number }>;
  fee_micronoid: number;
  change_micronoid: number;
  total_input_micronoid: number;
  total_output_micronoid: number;
}

export interface WalletSendResult {
  txid: string;
  submitted: boolean;
  fee_micronoid: number;
  error?: string;
}

export interface AddressInfo {
  valid: boolean;
  canonical: string;
  hrp: string;
}

export interface FeeEstimate {
  fee_micronoid: number;
  bytes: number;
  rate_micronoid_per_byte: number;
}

// ---------------------------------------------------------------------------
// Mempool
// ---------------------------------------------------------------------------

export interface MempoolInfo {
  count: number;
  fee_floor_micronoid: number;
  transactions: Array<{
    txid: string;
    fee_micronoid: number;
    bytes: number;
  }>;
}

// ---------------------------------------------------------------------------
// JSON-RPC envelope
// ---------------------------------------------------------------------------

export interface JsonRpcRequest<T = unknown> {
  jsonrpc: "2.0";
  id: number;
  method: string;
  params: T;
}

export interface JsonRpcResponse<T = unknown> {
  jsonrpc: "2.0";
  id: number;
  result?: T;
  error?: { code: number; message: string; data?: unknown };
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

export const NOID_HRP = "o1" as const;
export const MICRONOID_PER_NOID = 1_000_000n;
export const POW_HEADER_FIELD_COUNT = 16;
export const POW_NONCE_FIELD_INDEX = 10;
export const POW_FIELDS_HEX_BYTES = POW_HEADER_FIELD_COUNT * 16; // 256
export const DIFFICULTY_TARGET_BYTES = 32;
export const DIFFICULTY_TARGET_HEX_CHARS = 64;
export const NONCE_BYTES = 16;
export const NONCE_HEX_CHARS = 32;
export const BLOCK_TARGET_SECONDS = 30;
export const DEFAULT_RPC_URL = "http://127.0.0.1:9601";
export const DEFAULT_STRATUM_BRIDGE_PORT = 3030;

/** Format μNOID → human-readable NOID string. */
export function formatNoid(micronoid: number | bigint): string {
  const v = typeof micronoid === "bigint" ? micronoid : BigInt(micronoid);
  const whole = v / MICRONOID_PER_NOID;
  const frac = v % MICRONOID_PER_NOID;
  const fracStr = frac.toString().padStart(6, "0").replace(/0+$/, "");
  return fracStr ? `${whole}.${fracStr}` : `${whole}`;
}

/** Parse a human NOID string into μNOID bigint. */
export function parseNoid(input: string): bigint {
  const trimmed = input.trim();
  if (!/^\d+(\.\d{0,6})?$/.test(trimmed)) {
    throw new Error("Invalid NOID amount — expected e.g. 1.5 or 0.000001");
  }
  const [whole, frac = ""] = trimmed.split(".");
  const fracPadded = (frac + "000000").slice(0, 6);
  return BigInt(whole) * MICRONOID_PER_NOID + BigInt(fracPadded);
}
