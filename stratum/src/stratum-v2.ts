// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// Stratum V2 message codec — framing + JSON-RPC over Noise.
//
// Stratum V2 framing: every message is
//   [type: u8] [length: u24 LE] [payload: bytes]
//
// Most messages use Protocol Buffers, not JSON. The bridge does NOT
// parse the protobuf bodies — it only forwards framed messages between
// the browser miner (which speaks a JSON subset we define) and the pool.
// For the JSON↔protobuf translation we recognize the small subset of
// message types relevant to a non-custodial solo miner and emit/parse
// the corresponding JSON shapes.

import { SV2_MESSAGE_TYPES } from './types.ts';

/** Encode a Stratum V2 frame (type + 24-bit LE length + payload). */
export function encodeFrame(type: number, payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(6 + payload.length);
  out[0] = type & 0xff;
  const len = payload.length;
  out[1] = len & 0xff;
  out[2] = (len >>> 8) & 0xff;
  out[3] = (len >>> 16) & 0xff;
  // Bytes 4-5 are reserved extension bits in newer SV2 drafts; zero here.
  out[4] = 0;
  out[5] = 0;
  out.set(payload, 6);
  return out;
}

/** Decode the next frame from a buffer. Returns null if not enough bytes. */
export function tryDecodeFrame(buf: Uint8Array): { type: number; payload: Uint8Array; consumed: number } | null {
  if (buf.length < 6) return null;
  const type = buf[0];
  const len = buf[1] | (buf[2] << 8) | (buf[3] << 16);
  if (buf.length < 6 + len) return null;
  const payload = buf.slice(6, 6 + len);
  return { type, payload, consumed: 6 + len };
}

/** Reverse-lookup the message type name (for logging only). */
export function messageTypeName(type: number): string {
  for (const [name, code] of Object.entries(SV2_MESSAGE_TYPES)) {
    if (code === type) return name;
  }
  return `0x${type.toString(16)}`;
}
