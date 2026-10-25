// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow / Paranoid Zero contributors.
//
//! C ABI surface for Android NDK / JNI. Consumed by `noid_jni.cpp` in the
//! Android app, which in turn is loaded by `System.loadLibrary("noidmobile_pow")`
//! from Kotlin.
//!
//! All functions take length-prefixed strings (pointer + length) to avoid
//! any assumption about C string termination, and write results into
//! caller-allocated output buffers. Return value convention: `0` on
//! success, `-1` on error, and where applicable `1`/`0` for boolean
//! queries.

#![cfg(not(target_arch = "wasm32"))]

use crate::{
    batch_hash, check_target, decode_pow_fields_hex, derive_address_hex, hash_pow, le256_lt,
    validate_bech32m_o1, POW_DIGEST_LEN,
};
use std::ffi::CStr;
use std::os::raw::c_char;
use std::slice;

/// Read a length-prefixed C string into a Rust `&str`. Returns `None` if
/// the bytes are not valid UTF-8 or the pointer is null.
unsafe fn read_str<'a>(ptr: *const c_char, len: usize) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    let bytes = slice::from_raw_parts(ptr as *const u8, len);
    std::str::from_utf8(bytes).ok()
}

/// `noidmobile_hash_pow(pow_fields_hex, nonce_le_hex, out[32]) -> i32`
///
/// Writes 32 bytes of Poseidon2b PoW digest to `out`. Returns 0 on
/// success, -1 on error.
#[no_mangle]
pub extern "C" fn noidmobile_hash_pow(
    pow_fields_hex_ptr: *const c_char,
    pow_fields_hex_len: usize,
    nonce_le_hex_ptr: *const c_char,
    nonce_le_hex_len: usize,
    out: *mut u8,
) -> i32 {
    if out.is_null() {
        return -1;
    }
    let pow_fields_hex = unsafe { match read_str(pow_fields_hex_ptr, pow_fields_hex_len) {
        Some(s) => s,
        None => return -1,
    }};
    let nonce_le_hex = unsafe { match read_str(nonce_le_hex_ptr, nonce_le_hex_len) {
        Some(s) => s,
        None => return -1,
    }};

    match hash_pow(pow_fields_hex, nonce_le_hex) {
        Ok(digest) => {
            unsafe {
                std::ptr::copy_nonoverlapping(digest.as_ptr(), out, POW_DIGEST_LEN);
            }
            0
        }
        Err(_) => -1,
    }
}

/// `noidmobile_check_target(hash[32], target_le_hex) -> i32`
///
/// Returns 1 if `hash < target` (256-bit LE), 0 if not, -1 on error.
#[no_mangle]
pub extern "C" fn noidmobile_check_target(
    hash_ptr: *const u8,
    hash_len: usize,
    target_le_hex_ptr: *const c_char,
    target_le_hex_len: usize,
) -> i32 {
    if hash_ptr.is_null() || hash_len != POW_DIGEST_LEN {
        return -1;
    }
    let hash: [u8; POW_DIGEST_LEN] = unsafe {
        let mut a = [0u8; POW_DIGEST_LEN];
        std::ptr::copy_nonoverlapping(hash_ptr, a.as_mut_ptr(), POW_DIGEST_LEN);
        a
    };
    let target_le_hex = unsafe { match read_str(target_le_hex_ptr, target_le_hex_len) {
        Some(s) => s,
        None => return -1,
    }};

    if check_target(&hash, target_le_hex) {
        1
    } else {
        0
    }
}

/// `noidmobile_validate_address(addr) -> i32`
///
/// Returns 1 if the address is a valid bech32m Parano1d address (HRP `o1`),
/// 0 otherwise.
#[no_mangle]
pub extern "C" fn noidmobile_validate_address(
    addr_ptr: *const c_char,
    addr_len: usize,
) -> i32 {
    let addr = unsafe { match read_str(addr_ptr, addr_len) {
        Some(s) => s,
        None => return 0,
    }};
    if validate_bech32m_o1(addr) {
        1
    } else {
        0
    }
}

/// `noidmobile_batch_hash(pow_fields_hex, nonce_start, nonce_count, out) -> i32`
///
/// Writes `nonce_count * 32` bytes of digests to `out`. The caller MUST
/// allocate at least `nonce_count * 32` bytes. Returns 0 on success, -1
/// on error.
#[no_mangle]
pub extern "C" fn noidmobile_batch_hash(
    pow_fields_hex_ptr: *const c_char,
    pow_fields_hex_len: usize,
    nonce_start: u64,
    nonce_count: u32,
    out: *mut u8,
) -> i32 {
    if out.is_null() || nonce_count == 0 {
        return -1;
    }
    let pow_fields_hex = unsafe { match read_str(pow_fields_hex_ptr, pow_fields_hex_len) {
        Some(s) => s,
        None => return -1,
    }};
    match batch_hash(pow_fields_hex, nonce_start, nonce_count) {
        Ok(bytes) => {
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len());
            }
            0
        }
        Err(_) => -1,
    }
}

/// `noidmobile_le256_lt(a[32], b[32]) -> i32`
///
/// Convenience helper exposed for clients that want the exact same
/// 256-bit LE comparison primitive the miner uses.
#[no_mangle]
pub extern "C" fn noidmobile_le256_lt(a: *const u8, b: *const u8) -> i32 {
    if a.is_null() || b.is_null() {
        return -1;
    }
    let a_arr: [u8; 32] = unsafe {
        let mut x = [0u8; 32];
        std::ptr::copy_nonoverlapping(a, x.as_mut_ptr(), 32);
        x
    };
    let b_arr: [u8; 32] = unsafe {
        let mut x = [0u8; 32];
        std::ptr::copy_nonoverlapping(b, x.as_mut_ptr(), 32);
        x
    };
    if le256_lt(&a_arr, &b_arr) {
        1
    } else {
        0
    }
}

/// `noidmobile_derive_address(secret_hex, out_buf, out_buf_len) -> i32`
///
/// Derives the bech32m Parano1d address (`o1...`) from a 256-bit spend
/// secret. Writes a null-terminated UTF-8 string into `out_buf`. Returns
/// the number of bytes written (excluding the null terminator) on success,
/// or `-1` on error.
///
/// The caller MUST allocate `out_buf` with at least 80 bytes (addresses
/// are ~60 chars + null + safety margin).
#[no_mangle]
pub extern "C" fn noidmobile_derive_address(
    secret_hex_ptr: *const c_char,
    secret_hex_len: usize,
    out_buf: *mut c_char,
    out_buf_len: usize,
) -> i64 {
    if out_buf.is_null() || out_buf_len < 80 {
        return -1;
    }
    let secret_hex = unsafe { match read_str(secret_hex_ptr, secret_hex_len) {
        Some(s) => s,
        None => return -1,
    }};
    match derive_address_hex(secret_hex) {
        Ok(address) => {
            let bytes = address.as_bytes();
            if bytes.len() + 1 > out_buf_len {
                return -1;
            }
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf as *mut u8, bytes.len());
                // Null-terminate
                *out_buf.add(bytes.len()) = 0;
            }
            bytes.len() as i64
        }
        Err(_) => -1,
    }
}

// `decode_pow_fields_hex` is re-exported here so external C tests can reach
// it via the C ABI if needed; not exported as `#[no_mangle]` because it
// returns a Rust array — kept as a marker for downstream tooling.
#[doc(hidden)]
pub fn _cffi_decode_pow_fields_hex_marker(hex: &str) {
    let _ = decode_pow_fields_hex(hex);
}

// Silence "unused import" if the CStr path is not exercised.
#[allow(dead_code)]
fn _cstr_marker(s: &CStr) -> &str {
    s.to_str().unwrap_or("")
}
