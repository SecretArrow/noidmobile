// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! SV2 message type definitions, payload encoders/decoders, and the
//! Parano1d-specific `BlockTemplate` / `SubmitResult` types.
//!
//! peakpool is proprietary and its exact `NewMiningJob` / `SubmitShares`
//! payload schemas are not published. The decoders here therefore try
//! JSON first (peakpool is observed to embed a Parano1d-style JSON blob
//! inside the SV2 framing for Parano1d-specific fields) and fall back to
//! the standard SV2 binary layout if JSON fails.

use serde::{Deserialize, Serialize};

use crate::ClientError;

// ---------------------------------------------------------------------------
// Message type IDs (Mining Protocol, protocol = 0)
// ---------------------------------------------------------------------------

/// Client → server: open a connection + negotiate version.
pub const MSG_SETUP_CONNECTION: u8 = 0x00;
/// Server → client: setup succeeded, contains assigned `channel_id`.
pub const MSG_SETUP_CONNECTION_SUCCESS: u8 = 0x01;
/// Server → client: setup failed, contains error code.
pub const MSG_SETUP_CONNECTION_ERROR: u8 = 0x02;
/// Server → client: channel endpoint changed.
pub const MSG_CHANNEL_ENDPOINT_CHANGED: u8 = 0x03;
/// Server → client: request client to reconnect.
pub const MSG_RECONNECT: u8 = 0x04;
/// Server → client: new extended mining job (with merkle path).
pub const MSG_NEW_EXTENDED_MINING_JOB: u8 = 0x10;
/// Server → client: new standard mining job.
pub const MSG_NEW_MINING_JOB: u8 = 0x11;
/// Client → server: update channel (hash rate / target).
pub const MSG_UPDATE_CHANNEL: u8 = 0x12;
/// Client → server: submit shares.
pub const MSG_SUBMIT_SHARES: u8 = 0x13;
/// Server → client: shares accepted.
pub const MSG_SUBMIT_SHARES_SUCCESS: u8 = 0x14;
/// Server → client: shares rejected (with error code).
pub const MSG_SUBMIT_SHARES_ERROR: u8 = 0x15;
/// Client → server: set custom mining job.
pub const MSG_SET_CUSTOM_MINING_JOB: u8 = 0x16;
/// Server → client: custom mining job accepted.
pub const MSG_SET_CUSTOM_MINING_JOB_SUCCESS: u8 = 0x17;
/// Server → client: custom mining job rejected.
pub const MSG_SET_CUSTOM_MINING_JOB_ERROR: u8 = 0x18;

// ---------------------------------------------------------------------------
// Parano1d-specific public types
// ---------------------------------------------------------------------------

/// A mining template received from peakpool.
///
/// Field names match the Parano1d JSON-RPC `paranoid_getBlockTemplate`
/// response exactly so peakpool's JSON (if it follows the same shape)
/// round-trips cleanly through `serde_json`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlockTemplate {
    /// Single-use, expires in `expires_in_seconds`.
    pub template_id: String,
    /// 256 hex chars = 16 × 16-byte LE u128 fields.
    pub pow_fields_hex: String,
    /// Canonical Parano1d value: 10.
    pub nonce_field_index: u32,
    /// 64 hex chars = 32-byte LE target.
    pub difficulty_target_hex: String,
    /// Current chain height.
    pub height: u64,
    /// Template validity window in seconds.
    pub expires_in_seconds: u64,
    /// Number of transactions in the block (excluding coinbase).
    pub n_txs: u32,
}

/// Result of submitting a nonce to the pool.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SubmitResult {
    /// Shares accepted; contains the block hash (peakpool may omit this).
    Accepted(String),
    /// Shares rejected; contains the reason / error code.
    Rejected(String),
}

// ---------------------------------------------------------------------------
// STR0_255 helper — SV2's length-prefixed UTF-8 string (max 255 bytes)
// ---------------------------------------------------------------------------

/// Encode a `&str` as STR0_255: 1-byte length prefix + UTF-8 bytes.
pub fn encode_str0_255(s: &str) -> Result<Vec<u8>, ClientError> {
    let bytes = s.as_bytes();
    if bytes.len() > 255 {
        return Err(ClientError::Protocol(format!(
            "STR0_255 too long ({} > 255 bytes): {s}",
            bytes.len()
        )));
    }
    let mut out = Vec::with_capacity(1 + bytes.len());
    out.push(bytes.len() as u8);
    out.extend_from_slice(bytes);
    Ok(out)
}

/// Decode a STR0_255 from the start of `buf`. Returns `(string, rest)`.
pub fn decode_str0_255(buf: &[u8]) -> Result<(&str, &[u8]), ClientError> {
    if buf.is_empty() {
        return Err(ClientError::Protocol("STR0_255 empty buffer".into()));
    }
    let len = buf[0] as usize;
    if buf.len() < 1 + len {
        return Err(ClientError::Protocol(format!(
            "STR0_255 truncated: need {} bytes, have {}",
            1 + len,
            buf.len()
        )));
    }
    let s = std::str::from_utf8(&buf[1..1 + len])
        .map_err(|e| ClientError::Protocol(format!("STR0_255 utf8: {e}")))?;
    Ok((s, &buf[1 + len..]))
}

// ---------------------------------------------------------------------------
// SetupConnection (msg_type 0x00)
// ---------------------------------------------------------------------------

/// Encode the `SetupConnection` payload per the SV2 spec.
///
/// Field order:
/// `protocol | min_version | max_version | flags | endpoint_host |
///  endpoint_port | vendor | hardware_version | firmware | device_id`
pub fn encode_setup_connection(
    endpoint_host: &str,
    vendor: &str,
    hardware_version: &str,
    firmware: &str,
    device_id: &str,
) -> Result<Vec<u8>, ClientError> {
    let mut p = Vec::with_capacity(64);
    p.push(0u8); // protocol = 0 (Mining)
    p.extend_from_slice(&2u16.to_le_bytes()); // min_version = 2
    p.extend_from_slice(&2u16.to_le_bytes()); // max_version = 2
    p.extend_from_slice(&0u32.to_le_bytes()); // flags = 0
    p.extend_from_slice(&encode_str0_255(endpoint_host)?);
    p.extend_from_slice(&0u16.to_le_bytes()); // endpoint_port = 0
    p.extend_from_slice(&encode_str0_255(vendor)?);
    p.extend_from_slice(&encode_str0_255(hardware_version)?);
    p.extend_from_slice(&encode_str0_255(firmware)?);
    p.extend_from_slice(&encode_str0_255(device_id)?);
    Ok(p)
}

/// Decode `SetupConnection.Success`. Returns the assigned `channel_id`.
///
/// Spec payload: `channel_id (U32 LE) | used_version (U16 LE) |
/// flags (U32 LE)`. We only need the channel_id.
pub fn decode_setup_connection_success(buf: &[u8]) -> Result<u32, ClientError> {
    if buf.len() < 4 {
        return Err(ClientError::Protocol(format!(
            "SetupConnection.Success too short: {} < 4",
            buf.len()
        )));
    }
    Ok(u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]))
}

/// Decode `SetupConnection.Error`. Returns the error code string.
///
/// Spec payload: `flags (U32 LE) | error_code (STR0_255)`.
pub fn decode_setup_connection_error(buf: &[u8]) -> Result<String, ClientError> {
    if buf.is_empty() {
        return Ok("unknown".to_string());
    }
    if buf.len() < 4 {
        // No flags + truncated string — return raw bytes as a fallback.
        return Ok(String::from_utf8_lossy(buf).into_owned());
    }
    match decode_str0_255(&buf[4..]) {
        Ok((code, _)) => Ok(code.to_string()),
        Err(_) => Ok(String::from_utf8_lossy(&buf[4..]).into_owned()),
    }
}

// ---------------------------------------------------------------------------
// NewMiningJob (msg_type 0x11) — Parano1d-specific flexible parser
// ---------------------------------------------------------------------------

/// Parse a `BlockTemplate` from a `NewMiningJob` payload.
///
/// Strategy (peakpool is proprietary, so we are flexible):
/// 1. Try JSON first — peakpool is observed to embed Parano1d-style JSON
///    inside the SV2 framing for fields that have no SV2 equivalent.
/// 2. Accept the JSON either as the bare `BlockTemplate` shape OR
///    wrapped inside a top-level object with extra keys (the `flatten`
///    attribute handles unknown extra keys gracefully).
/// 3. Fall back to a best-effort standard SV2 binary parse. The standard
///    `NewMiningJob` payload does NOT carry Parano1d-specific fields, so
///    if we get here we synthesize a `BlockTemplate` with empty Parano1d
///    fields and the channel/job IDs from the standard layout. The
///    caller can detect this case by checking `pow_fields_hex.is_empty()`.
pub fn parse_block_template(payload: &[u8]) -> Result<BlockTemplate, ClientError> {
    // (1) Bare JSON
    if let Ok(t) = serde_json::from_slice::<BlockTemplate>(payload) {
        return Ok(t);
    }

    // (2) JSON with extra wrapper keys — `serde_json::Value` lets us pull
    //     out the Parano1d fields wherever they live in the tree.
    if let Ok(v) = serde_json::from_slice::<serde_json::Value>(payload) {
        if let Some(t) = extract_template_from_json(&v) {
            return Ok(t);
        }
    }

    // (3) Standard SV2 binary NewMiningJob (0x11) payload layout:
    //     channel_id (U32 LE)
    //     job_id (U32 LE)
    //     future_job (BOOL — 1 byte, 0/1)
    //     version (U32 LE)
    //     prev_hash (32 bytes)
    //     min_ntime (U32 LE)
    //     nbits (U32 LE)
    //     coinbase_tx_prefix (STR0_255 — length-prefixed)
    //     coinbase_tx_suffix (STR0_255 — length-prefixed)
    //     merkle_path (U256[] — count U32 LE + count×32 bytes)
    //
    // This layout does NOT carry Parano1d fields. We extract the
    // channel_id + job_id as the `template_id` and leave the Parano1d
    // fields empty so the caller knows to fall back to JSON-RPC.
    if payload.len() >= 9 {
        let channel_id = u32::from_le_bytes([payload[0], payload[1], payload[2], payload[3]]);
        let job_id = u32::from_le_bytes([payload[4], payload[5], payload[6], payload[7]]);
        return Ok(BlockTemplate {
            template_id: format!("sv2:{channel_id}:{job_id}"),
            pow_fields_hex: String::new(),
            nonce_field_index: 10,
            difficulty_target_hex: String::new(),
            height: 0,
            expires_in_seconds: 0,
            n_txs: 0,
        });
    }

    Err(ClientError::Protocol(format!(
        "could not parse BlockTemplate from payload ({} bytes) — neither JSON nor known SV2 binary layout",
        payload.len()
    )))
}

/// Walk a JSON value looking for the Parano1d `BlockTemplate` fields.
/// Returns `Some(template)` if all required fields are present.
fn extract_template_from_json(v: &serde_json::Value) -> Option<BlockTemplate> {
    // First, try direct conversion (already handled by the caller, but
    // we re-try here in case the top-level has extra fields that broke
    // the strict deserialize).
    if let Ok(t) = serde_json::from_value::<BlockTemplate>(v.clone()) {
        return Some(t);
    }
    // Look for the template nested under common wrapper keys.
    let obj = v.as_object()?;
    for key in ["template", "data", "job", "mining_job", "new_mining_job"] {
        if let Some(inner) = obj.get(key) {
            if let Ok(t) = serde_json::from_value::<BlockTemplate>(inner.clone()) {
                return Some(t);
            }
            if let Some(t) = extract_template_from_json(inner) {
                return Some(t);
            }
        }
    }
    // As a last resort, pull the fields out one by one.
    let get_str = |k: &str| obj.get(k).and_then(|x| x.as_str()).map(String::from);
    let get_u64 = |k: &str| obj.get(k).and_then(|x| x.as_u64());
    let get_u32 = |k: &str| obj.get(k).and_then(|x| x.as_u64()).map(|v| v as u32);
    let template_id = get_str("template_id")?;
    let pow_fields_hex = get_str("pow_fields_hex").unwrap_or_default();
    let nonce_field_index = get_u32("nonce_field_index").unwrap_or(10);
    let difficulty_target_hex = get_str("difficulty_target_hex").unwrap_or_default();
    let height = get_u64("height").unwrap_or(0);
    let expires_in_seconds = get_u64("expires_in_seconds").unwrap_or(0);
    let n_txs = get_u32("n_txs").unwrap_or(0);
    Some(BlockTemplate {
        template_id,
        pow_fields_hex,
        nonce_field_index,
        difficulty_target_hex,
        height,
        expires_in_seconds,
        n_txs,
    })
}

// ---------------------------------------------------------------------------
// SubmitShares (msg_type 0x13) + result parsers
// ---------------------------------------------------------------------------

/// Encode a `SubmitShares` payload. We use a JSON shape carrying the
/// Parano1d-specific `template_id` + `nonce_le_hex` since the standard
/// SV2 `SubmitShares` binary layout assumes SHA256 mining (job_id + nonce
/// + ntime + version) and has no place for Parano1d's `template_id`.
///
/// peakpool is expected to accept this JSON form. If it doesn't, the
/// client will receive a `SubmitShares.Error` and the caller can fall
/// back to JSON-RPC submit via the existing `paranoid_submitBlock` path.
pub fn encode_submit_shares(
    channel_id: u32,
    template_id: &str,
    nonce_le_hex: &str,
) -> Result<Vec<u8>, ClientError> {
    let json = serde_json::json!({
        "channel_id": channel_id,
        "template_id": template_id,
        "nonce_le_hex": nonce_le_hex,
    });
    serde_json::to_vec(&json)
        .map_err(|e| ClientError::Protocol(format!("encode SubmitShares JSON: {e}")))
}

/// Parse the response to a `SubmitShares` submission. Tries JSON first
/// (peakpool's Parano1d-specific response shape), then falls back to the
/// standard SV2 binary `SubmitShares.Success` / `.Error` layouts.
pub fn parse_submit_result(payload: &[u8]) -> Result<SubmitResult, ClientError> {
    // (1) JSON shape with explicit `accepted` / `rejected` / `error`
    //     fields, or with a `block_hash` / `hash` field indicating
    //     acceptance.
    #[derive(Deserialize)]
    struct JsonResponse {
        #[serde(default)]
        accepted: bool,
        #[serde(default)]
        rejected: bool,
        #[serde(default)]
        block_hash: Option<String>,
        #[serde(default)]
        hash: Option<String>,
        #[serde(default)]
        reason: Option<String>,
        #[serde(default)]
        error: Option<String>,
        #[serde(default)]
        result: Option<String>,
    }
    if let Ok(r) = serde_json::from_slice::<JsonResponse>(payload) {
        // Explicit rejection
        if r.rejected || r.reason.is_some() || r.error.is_some() {
            let reason = r
                .reason
                .or(r.error)
                .or(r.result)
                .unwrap_or_else(|| "rejected".to_string());
            return Ok(SubmitResult::Rejected(reason));
        }
        // Explicit acceptance
        if r.accepted {
            let hash = r.block_hash.or(r.hash).unwrap_or_default();
            return Ok(SubmitResult::Accepted(hash));
        }
        // Implicit acceptance via presence of a hash field
        if r.block_hash.is_some() || r.hash.is_some() {
            let hash = r.block_hash.or(r.hash).unwrap_or_default();
            return Ok(SubmitResult::Accepted(hash));
        }
    }

    // (2) Standard SV2 binary `SubmitShares.Success` (0x14) layout:
    //     channel_id (U32 LE) | last_sequence_number (U32 LE) |
    //     new_submits_accepted_count (U32 LE) |
    //     new_shares_sum (U32 LE)
    //     We treat any 16-byte-or-longer payload as accepted.
    if payload.len() >= 16 {
        return Ok(SubmitResult::Accepted(String::new()));
    }

    // (3) Standard SV2 binary `SubmitShares.Error` (0x15) layout:
    //     channel_id (U32 LE) | sequence_number (U32 LE) |
    //     error_code (STR0_255)
    if payload.len() >= 8 {
        if let Ok((code, _)) = decode_str0_255(&payload[8..]) {
            return Ok(SubmitResult::Rejected(code.to_string()));
        }
        return Ok(SubmitResult::Rejected(
            String::from_utf8_lossy(&payload[8..]).into_owned(),
        ));
    }

    // (4) Empty / unknown payload — assume accepted with empty hash.
    //     This is permissive but matches the spirit of "no news is good
    //     news" — peakpool should send an explicit error if something
    //     went wrong.
    if payload.is_empty() {
        return Ok(SubmitResult::Accepted(String::new()));
    }

    Err(ClientError::Protocol(format!(
        "could not parse SubmitResult from payload ({} bytes)",
        payload.len()
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn str0_255_round_trip() {
        let s = "android-miner";
        let enc = encode_str0_255(s).expect("encode");
        assert_eq!(enc[0] as usize, s.len());
        let (dec, rest) = decode_str0_255(&enc).expect("decode");
        assert_eq!(dec, s);
        assert!(rest.is_empty());
    }

    #[test]
    fn str0_255_rejects_too_long() {
        let s = "x".repeat(256);
        assert!(encode_str0_255(&s).is_err());
    }

    #[test]
    fn setup_connection_payload_layout() {
        let p = encode_setup_connection(
            "android-miner",
            "SecretArrow",
            "noidmobile-android",
            "0.5.0",
            "",
        )
        .expect("encode");
        // protocol
        assert_eq!(p[0], 0);
        // min_version = 2 (LE)
        assert_eq!(&p[1..3], &[2u8, 0]);
        // max_version = 2 (LE)
        assert_eq!(&p[3..5], &[2u8, 0]);
        // flags = 0 (LE U32)
        assert_eq!(&p[5..9], &[0u8, 0, 0, 0]);
        // endpoint_host STR0_255 = 13 bytes: 0x0d + "android-miner"
        assert_eq!(p[9], 13);
        assert_eq!(&p[10..23], b"android-miner");
    }

    #[test]
    fn block_template_json_round_trip() {
        let t = BlockTemplate {
            template_id: "tpl-123".into(),
            pow_fields_hex: "ab".repeat(128),
            nonce_field_index: 10,
            difficulty_target_hex: "00".repeat(32),
            height: 42,
            expires_in_seconds: 30,
            n_txs: 1,
        };
        let json = serde_json::to_vec(&t).expect("serialize");
        let t2 = parse_block_template(&json).expect("parse");
        assert_eq!(t.template_id, t2.template_id);
        assert_eq!(t.pow_fields_hex, t2.pow_fields_hex);
        assert_eq!(t.height, t2.height);
    }

    #[test]
    fn block_template_nested_under_wrapper() {
        let json = br#"{"type":"new_mining_job","template":{"template_id":"x","pow_fields_hex":"","nonce_field_index":10,"difficulty_target_hex":"","height":1,"expires_in_seconds":2,"n_txs":0}}"#;
        let t = parse_block_template(json).expect("parse");
        assert_eq!(t.template_id, "x");
        assert_eq!(t.height, 1);
    }

    #[test]
    fn block_template_falls_back_to_sv2_binary() {
        // channel_id=4, job_id=7, future_job=0, version=1, prev_hash=32×0
        // ... we only check the first 8 bytes are parsed as channel_id+job_id
        let mut p = Vec::new();
        p.extend_from_slice(&4u32.to_le_bytes());
        p.extend_from_slice(&7u32.to_le_bytes());
        p.push(0); // future_job
        // Pad to make payload.len() >= 9 (already is)
        let t = parse_block_template(&p).expect("parse");
        assert_eq!(t.template_id, "sv2:4:7");
        assert!(t.pow_fields_hex.is_empty());
    }

    #[test]
    fn submit_result_json_accepted() {
        let json = br#"{"accepted":true,"block_hash":"deadbeef"}"#;
        let r = parse_submit_result(json).expect("parse");
        assert_eq!(r, SubmitResult::Accepted("deadbeef".into()));
    }

    #[test]
    fn submit_result_json_rejected() {
        let json = br#"{"rejected":true,"reason":"stale"}"#;
        let r = parse_submit_result(json).expect("parse");
        assert_eq!(r, SubmitResult::Rejected("stale".into()));
    }

    #[test]
    fn submit_result_sv2_binary_success() {
        let mut p = Vec::new();
        p.extend_from_slice(&1u32.to_le_bytes()); // channel_id
        p.extend_from_slice(&2u32.to_le_bytes()); // last_sequence_number
        p.extend_from_slice(&3u32.to_le_bytes()); // new_submits_accepted_count
        p.extend_from_slice(&4u32.to_le_bytes()); // new_shares_sum
        let r = parse_submit_result(&p).expect("parse");
        assert_eq!(r, SubmitResult::Accepted(String::new()));
    }

    #[test]
    fn submit_result_sv2_binary_error() {
        let mut p = Vec::new();
        p.extend_from_slice(&1u32.to_le_bytes()); // channel_id
        p.extend_from_slice(&2u32.to_le_bytes()); // sequence_number
        p.extend_from_slice(&encode_str0_255("stale").unwrap()); // error_code
        let r = parse_submit_result(&p).expect("parse");
        assert_eq!(r, SubmitResult::Rejected("stale".into()));
    }
}
