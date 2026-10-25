// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// Shared types for the Stratum V2 bridge.

/** Wire-format messages exchanged with the browser miner over WebSocket. */
export type BridgeMessage =
  | {
      type: 'template';
      template_id: string;
      pow_fields_hex: string;
      nonce_field_index: number;
      difficulty_target_hex: string;
      height: number;
      expires_in_seconds: number;
      n_txs: number;
    }
  | { type: 'submit'; template_id: string; nonce_hex: string }
  | { type: 'submit_result'; ok: true; hash: string }
  | { type: 'submit_result'; ok: false; error: string }
  | { type: 'status'; connected: boolean; pool?: string }
  | { type: 'log'; level: 'info' | 'warn' | 'error'; message: string };

/** Stratum V2 message type IDs (subset — see spec). */
export const SV2_MESSAGE_TYPES = {
  SETUP_CONNECTION: 0x00,
  SETUP_CONNECTION_SUCCESS: 0x01,
  SETUP_CONNECTION_ERROR: 0x02,
  CHANNEL_ENDPOINT_CHANGED: 0x03,
  NEW_TEMPLATE: 0x06,
  SET_NEW_PREV_HASH: 0x07,
  SUBMIT_SOLUTION: 0x0a,
  OPEN_STANDARD_MINING_CHANNEL: 0x10,
  OPEN_STANDARD_MINING_CHANNEL_SUCCESS: 0x11,
  OPEN_STANDARD_MINING_CHANNEL_ERROR: 0x12,
  NEW_MINING_JOB: 0x15,
  SUBMIT_SHARES_STANDARD: 0x16,
  SUBMIT_SHARES_SUCCESS: 0x17,
  SUBMIT_SHARES_ERROR: 0x18,
} as const;

/** Runtime configuration for the bridge. */
export interface BridgeConfig {
  poolHost: string;
  poolPort: number;
  poolPubkey: Uint8Array | null; // 32 bytes X25519, or null to disable authentication
  bridgePort: number;
  staticKey: Uint8Array | null; // our static X25519 private key, or null for ephemeral
  logLevel: 'error' | 'warn' | 'info' | 'debug';
}
