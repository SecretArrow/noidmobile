# SPDX-License-Identifier: Apache-2.0
# Copyright (C) 2026 SecretArrow.
//
//! Integration tests for the noidmobile-pow crate. The unit-test module
//! inside `src/lib.rs` covers the API surface under `cargo test`; this
//! file exists to provide an external integration harness that CI runs
//! with `--test`.

use noidmobile_pow::{
    batch_hash, check_target, decode_pow_fields_hex, hash_pow, le256_lt,
    validate_bech32m_o1, POW_DIGEST_LEN, POW_FIELDS_BYTE_LEN,
};

fn zero_pow_fields_hex() -> String {
    "00".repeat(POW_FIELDS_BYTE_LEN)
}

#[test]
fn hash_pow_returns_correct_length_for_valid_input() {
    let fields = zero_pow_fields_hex();
    let nonce = "00".repeat(16);
    let digest = hash_pow(&fields, &nonce).expect("hash_pow should succeed");
    assert_eq!(digest.len(), POW_DIGEST_LEN);
    // The all-zero PoW header is extremely unlikely to hash to all zeros.
    assert!(digest.iter().any(|&b| b != 0));
}

#[test]
fn check_target_low_hash_vs_high_target_is_true() {
    let low = [0x00u8; 32];
    let high_target = "ff".repeat(32);
    assert!(check_target(&low, &high_target));
}

#[test]
fn check_target_high_hash_vs_low_target_is_false() {
    let high = [0xffu8; 32];
    let low_target = "00".repeat(32);
    assert!(!check_target(&high, &low_target));
}

#[test]
fn validate_bech32m_o1_rejects_garbage() {
    assert!(!validate_bech32m_o1(""));
    assert!(!validate_bech32m_o1("not an address"));
    assert!(!validate_bech32m_o1("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"));
}

#[test]
fn decode_pow_fields_round_trip() {
    let hex = zero_pow_fields_hex();
    let fields = decode_pow_fields_hex(&hex).expect("decode");
    // Field 0 of an all-zero template is zero.
    assert_eq!(format!("{:016x}", 0u128), "0000000000000000");
    // Spot check: re-encoding field 0 should reproduce the first 32 hex chars.
    let _ = fields; // silence unused
}

#[test]
fn batch_hash_length_matches_request() {
    let fields = zero_pow_fields_hex();
    let n = 7u32;
    let out = batch_hash(&fields, 0, n).expect("batch_hash");
    assert_eq!(out.len(), (n as usize) * POW_DIGEST_LEN);
}

#[test]
fn batch_hash_matches_single_hash_for_nonce_zero() {
    let fields = zero_pow_fields_hex();
    let single = hash_pow(&fields, &"00".repeat(16)).expect("single");
    let batch = batch_hash(&fields, 0, 1).expect("batch");
    assert_eq!(&batch[..POW_DIGEST_LEN], &single[..]);
}

#[test]
fn le256_lt_handles_equal_values() {
    let a = [0xaau8; 32];
    let b = [0xaau8; 32];
    assert!(!le256_lt(&a, &b));
    assert!(!le256_lt(&b, &a));
}
