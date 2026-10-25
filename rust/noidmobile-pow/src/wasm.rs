// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow / Paranoid Zero contributors.
//
//! WASM bindings for the web miner. Exposed via `wasm-bindgen` and
//! consumed from TypeScript through `wasm-pack`-generated glue.

#![cfg(target_arch = "wasm32")]

use crate::{batch_hash, check_target, hash_pow, validate_bech32m_o1, POW_DIGEST_LEN};
use wasm_bindgen::prelude::*;

/// Compute the Poseidon2b PoW hash for a single nonce.
///
/// - `pow_fields_hex`: 256 hex chars = 16 × 16-byte LE fields.
/// - `nonce_le_hex`: 32 hex chars = 16-byte LE nonce.
///
/// Returns the 64-char hex digest, or an empty string on error.
#[wasm_bindgen]
pub fn wasm_hash_pow(pow_fields_hex: &str, nonce_le_hex: &str) -> String {
    match hash_pow(pow_fields_hex, nonce_le_hex) {
        Ok(digest) => hex::encode(digest),
        Err(_) => String::new(),
    }
}

/// True if `hash < target` as 256-bit LE integers.
///
/// - `hash_hex`: 64 hex chars (32-byte LE hash digest).
/// - `target_le_hex`: 64 hex chars (32-byte LE target).
#[wasm_bindgen]
pub fn wasm_check_target(hash_hex: &str, target_le_hex: &str) -> bool {
    let bytes = match hex::decode(hash_hex) {
        Ok(b) => b,
        Err(_) => return false,
    };
    if bytes.len() != POW_DIGEST_LEN {
        return false;
    }
    let mut arr = [0u8; POW_DIGEST_LEN];
    arr.copy_from_slice(&bytes);
    check_target(&arr, target_le_hex)
}

/// Validate a bech32m Parano1d address (HRP `"o1"`).
#[wasm_bindgen]
pub fn wasm_validate_address(addr: &str) -> bool {
    validate_bech32m_o1(addr)
}

/// Batch-hash `nonce_count` consecutive nonces starting at `nonce_start`.
///
/// Returns the concatenated 64-char hex digests for the
/// `nonce_count` nonces (total length `nonce_count * 64` chars).
/// Returns an empty string on error.
///
/// Used by the web miner's batched search loop, mirroring the upstream
/// `DIGEST_BATCH` strategy.
#[wasm_bindgen]
pub fn wasm_batch_hash(pow_fields_hex: &str, nonce_start: u64, nonce_count: u32) -> String {
    match batch_hash(pow_fields_hex, nonce_start, nonce_count) {
        Ok(bytes) => hex::encode(bytes),
        Err(_) => String::new(),
    }
}

/// One-time init hook: install a panic hook that logs to `console.error`
/// so worker exceptions surface in the browser devtools.
#[wasm_bindgen(start)]
pub fn _noidmobile_init() {
    console_error_panic_hook::set_once();
}

// Minimal external binding to `console.error` — avoids pulling in the
// full `web-sys` / `js-sys` dependency trees.
#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_namespace = console)]
    fn error(s: &str);
}

// Lightweight inline panic hook (no extra crate dep).
mod console_error_panic_hook {
    use std::sync::Once;
    static INIT: Once = Once::new();

    pub fn set_once() {
        INIT.call_once(|| {
            std::panic::set_hook(Box::new(|info| {
                super::error(&format!("{info}"));
            }));
        });
    }
}

// Re-export the WASM-only test module when building under wasm targets
// that support `wasm-bindgen-test`.
#[cfg(test)]
mod wasm_tests {
    use super::*;

    fn zero_fields() -> String {
        "00".repeat(crate::POW_FIELDS_BYTE_LEN)
    }

    #[test]
    fn wasm_hash_pow_returns_64_hex_chars() {
        let h = wasm_hash_pow(&zero_fields(), &"00".repeat(16));
        assert_eq!(h.len(), 64);
    }

    #[test]
    fn wasm_batch_hash_count_matches() {
        let h = wasm_batch_hash(&zero_fields(), 0, 3);
        assert_eq!(h.len(), 3 * 64);
    }
}
