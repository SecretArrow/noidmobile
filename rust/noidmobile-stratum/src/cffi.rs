// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! C ABI surface for JNI consumption from the Android app. Exported
//! functions:
//!
//! - `noidmobile_stratum_connect(host, host_len, port, pubkey, pubkey_len)`
//! - `noidmobile_stratum_setup(client)`
//! - `noidmobile_stratum_recv_template(client, out, out_len)`
//! - `noidmobile_stratum_submit(client, tpl_id, tpl_len, nonce, nonce_len, out, out_len)`
//! - `noidmobile_stratum_disconnect(client)`
//!
//! Conventions:
//! - All strings are length-prefixed (pointer + byte length) — we do NOT
//!   rely on C null-termination for inputs.
//! - Output buffers receive a **null-terminated** UTF-8 string. The
//!   return value is the string length in bytes (excluding the null
//!   terminator) on success, or a negative value on error.
//! - The opaque client handle is a `*mut StratumV2Client` returned by
//!   `_connect` and freed by `_disconnect`. Callers MUST synchronize
//!   access from a single thread (the client is `!Sync`).
//! - None of these functions panic. All Rust errors are mapped to
//!   negative return codes / null pointers.

use std::ffi::c_char;
use std::slice;

use crate::{ClientError, StratumV2Client, SubmitResult};

/// Read a length-prefixed C string into a Rust `&str`. Returns `None` if
/// the pointer is null, the length is zero, or the bytes are not valid
/// UTF-8.
///
/// # Safety
/// `ptr` must point to at least `len` readable bytes (or be null).
unsafe fn read_str<'a>(ptr: *const c_char, len: usize) -> Option<&'a str> {
    if ptr.is_null() || len == 0 {
        return None;
    }
    let bytes = slice::from_raw_parts(ptr as *const u8, len);
    std::str::from_utf8(bytes).ok()
}

/// Write `s` into `out_buf` as a null-terminated UTF-8 string. Returns
/// the number of bytes written (excluding the null terminator) on
/// success, or `-1` if the buffer is null or too small.
///
/// # Safety
/// `out_buf` must point to at least `out_buf_len` writable bytes (or be
/// null, in which case we return -1).
unsafe fn write_str(out_buf: *mut c_char, out_buf_len: usize, s: &str) -> i64 {
    if out_buf.is_null() {
        return -1;
    }
    let bytes = s.as_bytes();
    // Need bytes.len() + 1 for the null terminator.
    if bytes.len() + 1 > out_buf_len {
        return -1;
    }
    std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf as *mut u8, bytes.len());
    // Null-terminate
    *out_buf.add(bytes.len()) = 0;
    bytes.len() as i64
}

/// `noidmobile_stratum_connect(host, host_len, port, pool_pubkey_hex, pubkey_len) -> *mut StratumV2Client`
///
/// Connects to `host:port` and performs the Noise_NX handshake. Returns
/// an opaque client handle on success, or null on error. The caller MUST
/// free the handle with `noidmobile_stratum_disconnect` when done.
#[no_mangle]
pub extern "C" fn noidmobile_stratum_connect(
    host: *const c_char,
    host_len: usize,
    port: u16,
    pool_pubkey_hex: *const c_char,
    pubkey_len: usize,
) -> *mut StratumV2Client {
    let host = match unsafe { read_str(host, host_len) } {
        Some(s) => s,
        None => {
            log::error!("noidmobile_stratum_connect: null/invalid host");
            return std::ptr::null_mut();
        }
    };
    let pool_pubkey = unsafe { read_str(pool_pubkey_hex, pubkey_len) }.unwrap_or("");

    match StratumV2Client::connect(host, port, pool_pubkey) {
        Ok(c) => Box::into_raw(Box::new(c)),
        Err(e) => {
            log::error!("noidmobile_stratum_connect '{host}:{port}': {e}");
            std::ptr::null_mut()
        }
    }
}

/// `noidmobile_stratum_setup(client) -> i32`
///
/// Sends `SetupConnection` and waits for the response. Returns 0 on
/// success (the assigned `channel_id` is stored inside the client and
/// can be retrieved via... well, we don't expose it as a separate call
/// — callers that need it should use the Rust API directly), -1 on
/// error.
#[no_mangle]
pub extern "C" fn noidmobile_stratum_setup(client: *mut StratumV2Client) -> i32 {
    let client = match safe_client_mut(client) {
        Some(c) => c,
        None => return -1,
    };
    match client.setup() {
        Ok(_) => 0,
        Err(e) => {
            log::error!("noidmobile_stratum_setup: {e}");
            -1
        }
    }
}

/// `noidmobile_stratum_recv_template(client, out_buf, out_buf_len) -> i64`
///
/// Blocks until a new mining template arrives (or the read timeout
/// expires). On success, writes a null-terminated JSON-encoded
/// `BlockTemplate` into `out_buf` and returns the string length (excl.
/// null). Returns `0` if no template arrived within the read timeout
/// (caller should loop). Returns `-1` on connection error.
#[no_mangle]
pub extern "C" fn noidmobile_stratum_recv_template(
    client: *mut StratumV2Client,
    out_buf: *mut c_char,
    out_buf_len: usize,
) -> i64 {
    let client = match safe_client_mut(client) {
        Some(c) => c,
        None => return -1,
    };
    match client.recv_template() {
        Ok(Some(template)) => {
            let json = match serde_json::to_string(&template) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("noidmobile_stratum_recv_template: serialize: {e}");
                    return -1;
                }
            };
            unsafe { write_str(out_buf, out_buf_len, &json) }
        }
        Ok(None) => 0,
        Err(e) => {
            log::error!("noidmobile_stratum_recv_template: {e}");
            -1
        }
    }
}

/// `noidmobile_stratum_submit(client, template_id, template_id_len, nonce_le_hex, nonce_len, out_buf, out_buf_len) -> i32`
///
/// Submits a found nonce. Returns:
/// - `1` if accepted (`out_buf` receives the block hash, may be empty)
/// - `0` if rejected (`out_buf` receives the reason)
/// - `-1` on connection error (`out_buf` receives an error message)
#[no_mangle]
pub extern "C" fn noidmobile_stratum_submit(
    client: *mut StratumV2Client,
    template_id: *const c_char,
    template_id_len: usize,
    nonce_le_hex: *const c_char,
    nonce_len: usize,
    out_buf: *mut c_char,
    out_buf_len: usize,
) -> i32 {
    let client = match safe_client_mut(client) {
        Some(c) => c,
        None => return -1,
    };
    let template_id = match unsafe { read_str(template_id, template_id_len) } {
        Some(s) => s,
        None => {
            log::error!("noidmobile_stratum_submit: null template_id");
            return -1;
        }
    };
    let nonce_le_hex = match unsafe { read_str(nonce_le_hex, nonce_len) } {
        Some(s) => s,
        None => {
            log::error!("noidmobile_stratum_submit: null nonce_le_hex");
            return -1;
        }
    };

    match client.submit_nonce(template_id, nonce_le_hex) {
        Ok(SubmitResult::Accepted(hash)) => {
            if !out_buf.is_null() && out_buf_len > 0 {
                unsafe { write_str(out_buf, out_buf_len, &hash) };
            }
            1
        }
        Ok(SubmitResult::Rejected(reason)) => {
            if !out_buf.is_null() && out_buf_len > 0 {
                unsafe { write_str(out_buf, out_buf_len, &reason) };
            }
            0
        }
        Err(e) => {
            log::error!("noidmobile_stratum_submit: {e}");
            if !out_buf.is_null() && out_buf_len > 0 {
                let msg = format!("error: {e}");
                unsafe { write_str(out_buf, out_buf_len, &msg) };
            }
            -1
        }
    }
}

/// `noidmobile_stratum_disconnect(client)`
///
/// Cleanly shuts down the connection and frees the client handle. The
/// pointer MUST NOT be used after this call. Passing null is a no-op.
#[no_mangle]
pub extern "C" fn noidmobile_stratum_disconnect(client: *mut StratumV2Client) {
    if client.is_null() {
        return;
    }
    // SAFETY: the caller guaranteed that `client` was obtained from
    // `noidmobile_stratum_connect` and has not been freed yet. We take
    // ownership back into a Box and let it drop, which closes the TCP
    // stream via `StratumV2Client`'s Drop impl.
    let mut boxed = unsafe { Box::from_raw(client) };
    boxed.disconnect();
    // `boxed` drops here, freeing the allocation.
}

/// `noidmobile_stratum_last_error_length() -> i32`
///
/// Returns the length (excluding null terminator) of the last error
/// message produced by a C ABI call on the current thread, or 0 if
/// there is no error stored. The main use case is letting JNI callers
/// retrieve a human-readable error string after a function returns -1.
///
/// NOTE: This is a stub — for simplicity, errors are only logged via
/// the `log` crate. A future patch can add a thread-local last-error
/// buffer. For now this always returns 0.
#[no_mangle]
pub extern "C" fn noidmobile_stratum_last_error_length() -> i32 {
    0
}

// ---------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------

/// Validate and dereference a `*mut StratumV2Client` to `&mut`. Returns
/// `None` if the pointer is null. We deliberately do NOT use
/// `Box::from_raw` here (which would free the memory on drop) — the
/// caller owns the handle until `_disconnect`.
fn safe_client_mut(client: *mut StratumV2Client) -> Option<&'static mut StratumV2Client> {
    if client.is_null() {
        log::error!("noidmobile_stratum: null client handle");
        return None;
    }
    // SAFETY: the caller guaranteed that `client` was obtained from
    // `noidmobile_stratum_connect` and has not been freed. We extend
    // the lifetime to 'static — the borrow is bounded by the caller's
    // use of the handle, which is safe as long as callers don't alias
    // the handle across threads (they must synchronize).
    Some(unsafe { &mut *client })
}

// Silence "unused" warning for ClientError import — it's used in
// std::error::Error trait impls that the compiler may or may not
// synthesize depending on feature flags.
#[allow(dead_code)]
fn _client_error_marker(e: ClientError) -> String {
    format!("{e}")
}
