// SPDX-License-Identifier: Apache-2.0
// Poseidon2b PoW backend for the web miner.
//
// Strategy: load the WASM module built by CI from the Rust crate
// `noidmobile-pow` (which wraps the real upstream `noid_poseidon2b`). If the
// WASM is not present (CI hasn't run yet, or the user is on an old build),
// the backend reports `available: false` and the miner refuses to start.
//
// We do NOT ship a hand-rolled TS Poseidon2b: the upstream parameters (round
// constants, MDS matrix, S-box exponent, field polynomial, domain tag layout)
// are specific to Parano1d and any divergence would produce invalid hashes.
// A wrong hash wastes CPU and can never submit a valid block — that would be
// worse than refusing to mine. The real hash function comes from WASM only.

import {
  DIFFICULTY_TARGET_BYTES,
  NONCE_BYTES,
  POW_FIELDS_HEX_BYTES,
  POW_HEADER_FIELD_COUNT,
  POW_NONCE_FIELD_INDEX,
} from "./types";

// ---------------------------------------------------------------------------
// Types matching the Rust crate's wasm-bindgen exports (rust/noidmobile-pow/src/wasm.rs)
// ---------------------------------------------------------------------------

export interface NoidWasmModule {
  wasm_hash_pow(powFieldsHex: string, nonceLeHex: string): string;
  wasm_check_target(hashHex: string, targetLeHex: string): boolean;
  wasm_validate_address(addr: string): boolean;
  wasm_batch_hash(powFieldsHex: string, nonceStart: string, nonceCount: number): string;
}

// ---------------------------------------------------------------------------
// Loader
// ---------------------------------------------------------------------------

const WASM_BASE_URL = "/wasm";
const WASM_JS_URL = `${WASM_BASE_URL}/noidmobile_pow.js`;
const WASM_GLUE_MARKER = "noidmobile_pow";

let cachedModule: NoidWasmModule | null = null;
let cachedError: string | null = null;
let loadingPromise: Promise<NoidWasmModule | null> | null = null;

/**
 * Try to load the WASM PoW backend.
 * Returns null if unavailable (CI hasn't built it yet, or the file is missing).
 * Caches the result so subsequent calls are instant.
 */
export async function loadPowBackend(): Promise<NoidWasmModule | null> {
  if (cachedModule) return cachedModule;
  if (cachedError) return null;
  if (loadingPromise) return loadingPromise;

  loadingPromise = (async () => {
    try {
      // Probe — if the JS glue doesn't exist, the WASM hasn't been built yet.
      const probe = await fetch(WASM_JS_URL, { method: "HEAD" });
      if (!probe.ok) {
        cachedError = `WASM glue not found at ${WASM_JS_URL} — CI must run build-wasm.yml first.`;
        return null;
      }

      // wasm-pack --target web emits an ES module with a default export `init`
      // and named exports for each #[wasm_bindgen] function.
      // Use a dynamic import with a cache-busting query so a freshly committed
      // WASM is picked up without a hard reload.
      const mod = (await import(
        /* webpackIgnore: true */ /* @vite-ignore */ `${WASM_JS_URL}?t=${Date.now()}`
      )) as {
        default: (opts?: { module_or_path?: string | URL }) => Promise<unknown>;
        wasm_hash_pow: (a: string, b: string) => string;
        wasm_check_target: (a: string, b: string) => boolean;
        wasm_validate_address: (a: string) => boolean;
        wasm_batch_hash: (a: string, b: string, c: number) => string;
      };

      // Init the WASM (loads noidmobile_pow_bg.wasm from the same /wasm dir).
      await mod.default();

      cachedModule = {
        wasm_hash_pow: mod.wasm_hash_pow,
        wasm_check_target: mod.wasm_check_target,
        wasm_validate_address: mod.wasm_validate_address,
        wasm_batch_hash: mod.wasm_batch_hash,
      };
      cachedError = null;
      return cachedModule;
    } catch (err) {
      cachedError = `WASM load failed: ${err instanceof Error ? err.message : String(err)}`;
      return null;
    } finally {
      loadingPromise = null;
    }
  })();

  return loadingPromise;
}

export function getPowBackendError(): string | null {
  return cachedError;
}

export function isPowBackendLoaded(): boolean {
  return cachedModule !== null;
}

// ---------------------------------------------------------------------------
// Hashing helpers — wrap the WASM exports with input validation.
// ---------------------------------------------------------------------------

/**
 * Hash a single PoW template + nonce. Returns a 32-byte digest as a hex string.
 * @param powFieldsHex 256 hex chars (16 × 16-byte LE u128 fields). Field[10] is overwritten by `nonce`.
 * @param nonceLeHex 32 hex chars (16-byte LE u128 nonce).
 */
export function hashPow(
  backend: NoidWasmModule,
  powFieldsHex: string,
  nonceLeHex: string,
): string {
  if (powFieldsHex.length !== POW_FIELDS_HEX_BYTES) {
    throw new Error(
      `pow_fields_hex must be ${POW_FIELDS_HEX_BYTES} hex chars, got ${powFieldsHex.length}`,
    );
  }
  if (nonceLeHex.length !== NONCE_BYTES * 2) {
    throw new Error(
      `nonce_le_hex must be ${NONCE_BYTES * 2} hex chars, got ${nonceLeHex.length}`,
    );
  }
  const out = backend.wasm_hash_pow(powFieldsHex, nonceLeHex);
  if (!out || out.length !== DIFFICULTY_TARGET_BYTES * 2) {
    throw new Error(`WASM returned malformed hash: len=${out?.length}`);
  }
  return out;
}

/**
 * Batch-hash a contiguous nonce range. Returns concatenated 64-char hex digests.
 * @param powFieldsHex 256 hex chars (template — field[10] is the nonce lane).
 * @param nonceStart LE u128 start nonce as a 32-char hex string.
 * @param nonceCount Number of nonces to hash (1..=4096).
 */
export function batchHash(
  backend: NoidWasmModule,
  powFieldsHex: string,
  nonceStart: bigint,
  nonceCount: number,
): string {
  if (nonceCount < 1 || nonceCount > 4096) {
    throw new Error(`nonce_count must be 1..=4096, got ${nonceCount}`);
  }
  // Encode the start nonce as 16-byte LE hex.
  const nonceStartHex = nonceToLeHex(nonceStart);
  return backend.wasm_batch_hash(powFieldsHex, nonceStartHex, nonceCount);
}

/**
 * Check if a 32-byte hash satisfies the target (hash < target, LE compare).
 * @param hashHex 64 hex chars (32-byte LE hash).
 * @param targetLeHex 64 hex chars (32-byte LE target).
 */
export function checkTarget(
  backend: NoidWasmModule,
  hashHex: string,
  targetLeHex: string,
): boolean {
  return backend.wasm_check_target(hashHex, targetLeHex);
}

// ---------------------------------------------------------------------------
// Pure-TS helpers (no WASM required)
// ---------------------------------------------------------------------------

/** Encode a u128 nonce as 16-byte little-endian hex (32 lowercase chars). */
export function nonceToLeHex(nonce: bigint): string {
  if (nonce < 0n || nonce > 0xffffffffffffffffffffffffffffffffn) {
    throw new Error("nonce out of u128 range");
  }
  let hex = nonce.toString(16);
  hex = hex.padStart(32, "0");
  // Reverse byte order for LE.
  const bytes: string[] = [];
  for (let i = 0; i < 16; i++) {
    bytes.push(hex.slice(30 - i * 2, 32 - i * 2));
  }
  return bytes.join("");
}

/** Decode a 16-byte LE hex nonce back to u128 bigint. */
export function leHexToNonce(leHex: string): bigint {
  if (leHex.length !== 32) throw new Error("le_hex must be 32 chars");
  let result = 0n;
  for (let i = 0; i < 16; i++) {
    const byte = parseInt(leHex.slice(i * 2, i * 2 + 2), 16);
    result |= BigInt(byte) << BigInt(i * 8);
  }
  return result;
}

/**
 * Pure-TS 256-bit little-endian comparison: returns true if `a < b`.
 * Used as a fallback when the WASM check_target is unavailable, and for
 * independent verification of the WASM result.
 */
export function le256LessThan(aHex: string, bHex: string): boolean {
  if (aHex.length !== 64 || bHex.length !== 64) {
    throw new Error("le256_lt expects two 64-char hex strings");
  }
  // Compare from most-significant byte (index 31) down to byte 0.
  for (let i = 31; i >= 0; i--) {
    const aByte = parseInt(aHex.slice(i * 2, i * 2 + 2), 16);
    const bByte = parseInt(bHex.slice(i * 2, i * 2 + 2), 16);
    if (aByte < bByte) return true;
    if (aByte > bByte) return false;
  }
  return false; // equal
}

/** Count the leading zero bits in a 32-byte LE target (for display). */
export function targetLeadingZeroBits(targetLeHex: string): number {
  if (targetLeHex.length !== 64) return 0;
  let bits = 0;
  for (let i = 31; i >= 0; i--) {
    const byte = parseInt(targetLeHex.slice(i * 2, i * 2 + 2), 16);
    if (byte === 0) {
      bits += 8;
    } else {
      bits += Math.clz32(byte) - 24;
      break;
    }
  }
  return bits;
}

export {
  POW_HEADER_FIELD_COUNT,
  POW_NONCE_FIELD_INDEX,
  POW_FIELDS_HEX_BYTES,
};
