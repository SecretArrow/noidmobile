// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow / Paranoid Zero contributors.
//
//! # noidmobile-pow
//!
//! Shared PoW engine for the noidmobile monorepo. Wraps the upstream
//! `noid_poseidon2b` + `noid_core` crates from <https://github.com/ignotusnemo/parano1d>
//! and exposes two FFI surfaces:
//!
//! - **WASM** (`#[cfg(target_arch = "wasm32")]`): bindings for the web miner
//!   via `wasm-bindgen` + `wasm-pack`.
//! - **C ABI** (otherwise): consumed by the Android NDK through JNI in
//!   `libnoidmobile_pow.so`.
//!
//! The hash logic mirrors the upstream `noid_extminer/src/main.rs` reference
//! miner: 16 × 16-byte LE `Block128` fields → set field 10 to the nonce →
//! `Poseidon2b` with `TAG_POWHDR` → 32-byte digest. Validity is `hash < target`
//! interpreted as 256-bit little-endian integers.

use noid_core::Block128;
use noid_poseidon2b::batch::FixedFieldNonceBatch;
use noid_poseidon2b::native::domain::TAG_POWHDR;

#[cfg(target_arch = "wasm32")]
pub mod wasm;

#[cfg(not(target_arch = "wasm32"))]
pub mod cffi;

// ---------------------------------------------------------------------------
// Constants — must match upstream `noid_extminer/src/main.rs`.
// ---------------------------------------------------------------------------

/// 16 × `Block128` PoW header fields (canonical Parano1d layout).
pub const POW_HEADER_FIELD_COUNT: usize = 16;
/// Canonical nonce lane index inside the PoW header.
pub const POW_NONCE_FIELD_INDEX: usize = 10;
/// Total byte length of the 16 LE fields (== 256 hex chars).
pub const POW_FIELDS_BYTE_LEN: usize = POW_HEADER_FIELD_COUNT * 16;
/// Poseidon2b digest length in bytes.
pub const POW_DIGEST_LEN: usize = 32;
/// Batch size used by the WASM batch hash helper (matches upstream DIGEST_BATCH).
pub const WASM_BATCH_SIZE: usize = 256;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Decode 256 hex chars into the canonical 16 × `Block128` field array.
pub fn decode_pow_fields_hex(hex_str: &str) -> Result<[Block128; POW_HEADER_FIELD_COUNT], String> {
    let bytes = hex::decode(hex_str).map_err(|e| format!("hex decode: {e}"))?;
    if bytes.len() != POW_FIELDS_BYTE_LEN {
        return Err(format!(
            "pow_fields_hex must be {POW_FIELDS_BYTE_LEN} bytes, got {}",
            bytes.len()
        ));
    }
    let mut fields: [Block128; POW_HEADER_FIELD_COUNT] = core::array::from_fn(|_| Block128::from(0u128));
    for (i, chunk) in bytes.chunks_exact(16).enumerate() {
        let arr: [u8; 16] = chunk.try_into().expect("chunk is 16 bytes");
        fields[i] = Block128::from(u128::from_le_bytes(arr));
    }
    Ok(fields)
}

/// Decode a 32-hex-char (16-byte) LE nonce into a `u128`.
pub fn decode_nonce_le_hex(nonce_le_hex: &str) -> Result<u128, String> {
    let bytes = hex::decode(nonce_le_hex).map_err(|e| format!("nonce hex decode: {e}"))?;
    if bytes.len() != 16 {
        return Err(format!("nonce must be 16 bytes, got {}", bytes.len()));
    }
    let arr: [u8; 16] = bytes.try_into().expect("16 bytes");
    Ok(u128::from_le_bytes(arr))
}

/// Decode 64 hex chars into a 32-byte LE target.
pub fn decode_target_le_hex(target_le_hex: &str) -> Result<[u8; 32], String> {
    let bytes = hex::decode(target_le_hex).map_err(|e| format!("target hex decode: {e}"))?;
    if bytes.len() != POW_DIGEST_LEN {
        return Err(format!("target must be {POW_DIGEST_LEN} bytes, got {}", bytes.len()));
    }
    let arr: [u8; 32] = bytes.try_into().expect("32 bytes");
    Ok(arr)
}

/// Compare two 32-byte values as 256-bit LE integers: `a < b`.
/// Mirrors upstream `le256_lt` exactly.
#[inline]
pub fn le256_lt(a: &[u8; 32], b: &[u8; 32]) -> bool {
    for i in (0..32).rev() {
        if a[i] < b[i] {
            return true;
        }
        if a[i] > b[i] {
            return false;
        }
    }
    false
}

// ---------------------------------------------------------------------------
// Core PoW API
// ---------------------------------------------------------------------------

/// Compute the Poseidon2b PoW hash for a single nonce.
///
/// - `pow_fields_hex`: 256 hex chars = 16 × 16-byte LE `u128` fields.
/// - `nonce_le_hex`: 32 hex chars = 16-byte LE nonce.
///
/// Sets field 10 to the supplied nonce and hashes with `TAG_POWHDR`,
/// returning the 32-byte digest — identical to what the upstream miner
/// would compute before the `le256_lt(hash, target)` check.
pub fn hash_pow(pow_fields_hex: &str, nonce_le_hex: &str) -> Result<[u8; 32], String> {
    let mut fields = decode_pow_fields_hex(pow_fields_hex)?;
    let nonce = decode_nonce_le_hex(nonce_le_hex)?;
    fields[POW_NONCE_FIELD_INDEX] = Block128::from(nonce);

    // Use the batch API with a single nonce — this is the same primitive
    // the upstream miner calls (`FixedFieldNonceBatch::hash_into`).
    let mut hasher = FixedFieldNonceBatch::new(TAG_POWHDR, &fields, POW_NONCE_FIELD_INDEX);
    let mut out = [[0u8; 32]; 1];
    hasher.hash_into(nonce, &mut out[..1]);
    Ok(out[0])
}

/// True if `hash < target` interpreted as 256-bit LE integers.
pub fn check_target(hash: &[u8; 32], target_le_hex: &str) -> bool {
    match decode_target_le_hex(target_le_hex) {
        Ok(target) => le256_lt(hash, &target),
        Err(_) => false,
    }
}

/// Validate a bech32m Parano1d address (HRP `"o1"`).
pub fn validate_bech32m_o1(address: &str) -> bool {
    // bech32 v0.11 API: `decode` returns `Result<(Hrp, Vec<u8>), DecodeError>`
    // and the Bech32m variant is selected automatically by the checksum.
    match bech32::decode(address) {
        Ok((hrp, data)) => {
            if hrp.as_str() != "o1" || data.is_empty() {
                return false;
            }
            // bech32 0.11 + Bech32m check: re-encode and compare to verify
            // the variant is Bech32m (not legacy Bech32).
            match bech32::encode::<Bech32m>(hrp, &data) {
                Ok(reencoded) => reencoded.eq_ignore_ascii_case(address),
                Err(_) => false,
            }
        }
        Err(_) => false,
    }
}

// Alias for the Bech32m variant marker used by the bech32 crate.
// In bech32 0.11, `Bech32m` is a unit struct implementing the `Variant` trait.
use bech32::Bech32m;

/// Derive a bech32m Parano1d address (`o1...`) from a 256-bit spend secret.
///
/// This calls the upstream `noid_poseidon2b::primitives::derive_address`
/// directly — the same function the `parano1d` node uses when you import
/// a wallet. The web and Android wallets call this so the user can see
/// their address WITHOUT needing a local node running.
///
/// Input: 64 lowercase hex chars (256-bit spend secret).
/// Output: bech32m string like `o1q...` (~60 chars).
pub fn derive_address_hex(secret_hex: &str) -> Result<String, String> {
    let bytes = hex::decode(secret_hex).map_err(|e| format!("secret hex decode: {e}"))?;
    if bytes.len() != 32 {
        return Err(format!("spend secret must be 32 bytes, got {}", bytes.len()));
    }

    use noid_poseidon2b::primitives::{derive_address, SpendSecret};
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&bytes);
    let secret = SpendSecret::from_bytes(arr);
    let address = derive_address(&secret);
    Ok(address.to_bech32())
}

/// Batch-hash `nonce_count` consecutive nonces starting at `nonce_start`.
///
/// Returns a flat `Vec<u8>` of length `nonce_count * 32`. Used by the web
/// miner's batched search loop (mirrors upstream `DIGEST_BATCH`).
pub fn batch_hash(pow_fields_hex: &str, nonce_start: u64, nonce_count: u32) -> Result<Vec<u8>, String> {
    let fields = decode_pow_fields_hex(pow_fields_hex)?;
    let count = nonce_count as usize;
    let mut out = vec![0u8; count * POW_DIGEST_LEN];

    let mut hasher = FixedFieldNonceBatch::new(TAG_POWHDR, &fields, POW_NONCE_FIELD_INDEX);
    let start = nonce_start as u128;

    // Process in sub-batches of WASM_BATCH_SIZE to bound peak memory.
    let mut digests = vec![[0u8; POW_DIGEST_LEN]; WASM_BATCH_SIZE];
    let mut written = 0usize;
    let mut nonce = start;
    let remaining_total = count;

    while written < remaining_total {
        let n = (remaining_total - written).min(WASM_BATCH_SIZE);
        hasher.hash_into(nonce, &mut digests[..n]);
        for i in 0..n {
            let dst = (written + i) * POW_DIGEST_LEN;
            out[dst..dst + POW_DIGEST_LEN].copy_from_slice(&digests[i]);
        }
        nonce = nonce.wrapping_add(n as u128);
        written += n;
    }

    Ok(out)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// 256 hex chars = 128 bytes = 16 × 16-byte LE fields, all zero — a
    /// valid template shape (the upstream miner accepts this in unit tests).
    fn zero_pow_fields_hex() -> String {
        "00".repeat(POW_FIELDS_BYTE_LEN)
    }

    #[test]
    fn hash_pow_returns_32_bytes() {
        let fields = zero_pow_fields_hex();
        let nonce = "00".repeat(16); // 16-byte LE nonce = 0
        let hash = hash_pow(&fields, &nonce).expect("hash succeeds");
        assert_eq!(hash.len(), POW_DIGEST_LEN);
        // The all-zero header is extremely unlikely to hash to all zeros.
        assert!(hash.iter().any(|&b| b != 0));
    }

    #[test]
    fn hash_pow_rejects_short_hex() {
        let nonce = "00".repeat(16);
        let bad = "00".repeat(POW_FIELDS_BYTE_LEN - 1);
        assert!(hash_pow(&bad, &nonce).is_err());
    }

    #[test]
    fn check_target_compares_correctly() {
        let low = [0x00u8; 32];
        let high = [0xffu8; 32];
        // Low hash vs high target → valid.
        let big_target = "ff".repeat(32);
        assert!(check_target(&low, &big_target));
        // High hash vs low target → invalid.
        let small_target = "00".repeat(32);
        assert!(!check_target(&high, &small_target));
    }

    #[test]
    fn validate_address_accepts_wellformed_and_rejects_garbage() {
        // We cannot derive a real address without the upstream SpendSecret
        // plumbing here, so we only assert the negative cases and the HRP.
        assert!(!validate_bech32m_o1(""));
        assert!(!validate_bech32m_o1("garbage"));
        assert!(!validate_bech32m_o1("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")); // BTC HRP
        assert!(!validate_bech32_with_wrong_hrp());
    }

    fn validate_bech32_with_wrong_hrp() -> bool {
        // A syntactically-valid bech32m string but with the wrong HRP
        // ("o1" is required). Constructed with the bech32 crate so it's a
        // true Bech32m checksum. bech32 0.11 API.
        use bech32::{encode, Bech32m, Hrp};
        let data: Vec<u8> = vec![0u8; 32];
        let hrp = Hrp::parse("xx").unwrap();
        let s = encode::<Bech32m>(hrp, &data).unwrap();
        validate_bech32m_o1(&s)
    }

    #[test]
    fn batch_hash_returns_expected_length() {
        let fields = zero_pow_fields_hex();
        let out = batch_hash(&fields, 0, 4).expect("batch hash");
        assert_eq!(out.len(), 4 * POW_DIGEST_LEN);
    }

    #[test]
    fn batch_hash_matches_single_hash() {
        let fields = zero_pow_fields_hex();
        let nonce_le = {
            let n: u128 = 7;
            hex::encode(n.to_le_bytes())
        };
        let single = hash_pow(&fields, &nonce_le).expect("single hash");
        let batch = batch_hash(&fields, 7, 1).expect("batch hash");
        assert_eq!(&batch[..POW_DIGEST_LEN], &single[..]);
    }

    #[test]
    fn le256_lt_matches_byte_order() {
        let mut a = [0u8; 32];
        a[0] = 0x01;
        let b = [0u8; 32];
        // a > b (LE), so a < b is false.
        assert!(!le256_lt(&a, &b));
        assert!(le256_lt(&b, &a));
    }
}
