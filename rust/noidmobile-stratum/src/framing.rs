// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! Stratum V2 message framing — encode/decode the 6-byte SV2 frame header
//! and split a decrypted Noise plaintext into `(header, payload)`.
//!
//! Frame layout per the SV2 spec (section 3 of
//! <https://stratumprotocol.org/specification/03-Protocol-Basics/>):
//!
//! ```text
//! | Field          | Type | Size | Notes                              |
//! |----------------|------|------|------------------------------------|
//! | extension_type | U16  | 2    | bit 15 = channel_msg flag; LE     |
//! | msg_type       | U8   | 1    | message type ID                   |
//! | msg_length     | U24  | 3    | payload length (excludes header); LE |
//! | payload        | BYTES| var  | message-specific                  |
//! ```
//!
//! All multi-byte integer fields are **little-endian** per the SV2 spec.
//!
//! On the wire, every SV2 frame is wrapped in Noise transport encryption:
//! the entire frame (header + payload) is the plaintext handed to Noise's
//! `write_message`, and the resulting ciphertext is preceded by a 2-byte
//! **big-endian** length prefix (the standard Noise message-length
//! convention used by SRI's reference SV2 implementation). This module
//! does NOT add the wire length prefix — that lives in `client.rs` next
//! to the actual `TcpStream` reads/writes, since it has nothing to do
//! with the SV2 message format itself.

use crate::ClientError;

/// Number of bytes in an SV2 frame header.
pub const SV2_HEADER_LEN: usize = 6;

/// Maximum payload length. The wire length prefix is U16 (max 65535), so
/// the ciphertext cap is 65535; subtract the 16-byte AEAD tag and the
/// 6-byte SV2 header and we get a safe payload upper bound of 65513.
pub const SV2_MAX_PAYLOAD_LEN: usize = 65513;

/// Parsed SV2 frame header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sv2Header {
    /// Bit 15 is the `channel_msg` flag (per spec). Other bits are
    /// reserved for extension negotiation (unused by the basic mining
    /// protocol).
    pub extension_type: u16,
    /// Message type ID (e.g. 0x00 for `SetupConnection`).
    pub msg_type: u8,
    /// Payload length in bytes. Stored as `u32` for arithmetic safety,
    /// but the on-wire encoding is U24 (max 16_777_215).
    pub msg_length: u32,
}

impl Sv2Header {
    /// True if the `channel_msg` flag (bit 15 of `extension_type`) is set,
    /// meaning the payload begins with a 4-byte U32 LE `channel_id`.
    pub fn channel_msg(&self) -> bool {
        (self.extension_type & 0x8000) != 0
    }

    /// Serialize the header into a 6-byte little-endian buffer.
    pub fn encode(&self) -> [u8; SV2_HEADER_LEN] {
        let mut buf = [0u8; SV2_HEADER_LEN];
        // extension_type: U16 LE
        buf[0] = (self.extension_type & 0xff) as u8;
        buf[1] = ((self.extension_type >> 8) & 0xff) as u8;
        // msg_type: U8
        buf[2] = self.msg_type;
        // msg_length: U24 LE
        buf[3] = (self.msg_length & 0xff) as u8;
        buf[4] = ((self.msg_length >> 8) & 0xff) as u8;
        buf[5] = ((self.msg_length >> 16) & 0xff) as u8;
        buf
    }

    /// Parse a 6-byte (or longer) buffer into a header. Only the first
    /// `SV2_HEADER_LEN` bytes are consumed.
    pub fn decode(buf: &[u8]) -> Result<Self, ClientError> {
        if buf.len() < SV2_HEADER_LEN {
            return Err(ClientError::Protocol(format!(
                "sv2 header too short: {} < {}",
                buf.len(),
                SV2_HEADER_LEN
            )));
        }
        let extension_type = (buf[0] as u16) | ((buf[1] as u16) << 8);
        let msg_type = buf[2];
        let msg_length =
            (buf[3] as u32) | ((buf[4] as u32) << 8) | ((buf[5] as u32) << 16);
        if msg_length as usize > SV2_MAX_PAYLOAD_LEN {
            return Err(ClientError::Protocol(format!(
                "sv2 msg_length too large: {msg_length} > {}",
                SV2_MAX_PAYLOAD_LEN
            )));
        }
        Ok(Self {
            extension_type,
            msg_type,
            msg_length,
        })
    }
}

/// Build a complete SV2 frame (header + payload) ready to be encrypted
/// by Noise. `extension_type` defaults to 0 (no channel_msg flag, no
/// extension) — the basic mining protocol doesn't use extensions.
pub fn encode_message(msg_type: u8, payload: &[u8]) -> Result<Vec<u8>, ClientError> {
    if payload.len() > SV2_MAX_PAYLOAD_LEN {
        return Err(ClientError::Protocol(format!(
            "payload too large: {} > {}",
            payload.len(),
            SV2_MAX_PAYLOAD_LEN
        )));
    }
    let header = Sv2Header {
        extension_type: 0,
        msg_type,
        msg_length: payload.len() as u32,
    };
    let mut out = Vec::with_capacity(SV2_HEADER_LEN + payload.len());
    out.extend_from_slice(&header.encode());
    out.extend_from_slice(payload);
    Ok(out)
}

/// Build a complete SV2 frame with the `channel_msg` flag set, prepending
/// the 4-byte U32 LE `channel_id` to the payload (per spec, when the
/// channel_msg bit is set, the first 4 bytes of the payload are the
/// channel_id).
pub fn encode_channel_message(
    channel_id: u32,
    msg_type: u8,
    payload: &[u8],
) -> Result<Vec<u8>, ClientError> {
    let mut full_payload = Vec::with_capacity(4 + payload.len());
    full_payload.extend_from_slice(&channel_id.to_le_bytes());
    full_payload.extend_from_slice(payload);
    let header = Sv2Header {
        extension_type: 0x8000,
        msg_type,
        msg_length: full_payload.len() as u32,
    };
    if full_payload.len() > SV2_MAX_PAYLOAD_LEN {
        return Err(ClientError::Protocol(format!(
            "payload too large: {} > {}",
            full_payload.len(),
            SV2_MAX_PAYLOAD_LEN
        )));
    }
    let mut out = Vec::with_capacity(SV2_HEADER_LEN + full_payload.len());
    out.extend_from_slice(&header.encode());
    out.extend_from_slice(&full_payload);
    Ok(out)
}

/// Split a decrypted Noise plaintext into `(header, payload_slice)`.
/// The payload slice borrows from `plaintext`.
pub fn decode_message(plaintext: &[u8]) -> Result<(Sv2Header, &[u8]), ClientError> {
    let header = Sv2Header::decode(plaintext)?;
    let payload = &plaintext[SV2_HEADER_LEN..];
    if payload.len() < header.msg_length as usize {
        return Err(ClientError::Protocol(format!(
            "sv2 payload truncated: have {} bytes but header says {}",
            payload.len(),
            header.msg_length
        )));
    }
    // If the channel_msg flag is set, the first 4 bytes of the payload
    // are the channel_id; we return the entire payload (including the
    // channel_id prefix) and let the caller peel it off if they care.
    Ok((header, &payload[..header.msg_length as usize]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_round_trip() {
        let h = Sv2Header {
            extension_type: 0x8000,
            msg_type: 0x11,
            msg_length: 0x001234, // well below SV2_MAX_PAYLOAD_LEN (65513)
        };
        let bytes = h.encode();
        assert_eq!(bytes.len(), SV2_HEADER_LEN);
        // LE encoding checks:
        //   extension_type=0x8000 → [0x00, 0x80]
        //   msg_type=0x11         → [0x11]
        //   msg_length=0x001234   → [0x34, 0x12, 0x00]
        assert_eq!(bytes[0], 0x00);
        assert_eq!(bytes[1], 0x80);
        assert_eq!(bytes[2], 0x11);
        assert_eq!(bytes[3], 0x34);
        assert_eq!(bytes[4], 0x12);
        assert_eq!(bytes[5], 0x00);

        let h2 = Sv2Header::decode(&bytes).expect("decode");
        assert_eq!(h, h2);
        assert!(h2.channel_msg());
    }

    #[test]
    fn encode_decode_message() {
        let payload = b"hello peakpool";
        let frame = encode_message(0x00, payload).expect("encode");
        let (h, p) = decode_message(&frame).expect("decode");
        assert_eq!(h.msg_type, 0x00);
        assert_eq!(h.msg_length as usize, payload.len());
        assert_eq!(p, payload);
    }

    #[test]
    fn decode_rejects_short_buffer() {
        let buf = [0u8; 3];
        assert!(Sv2Header::decode(&buf).is_err());
    }

    #[test]
    fn decode_rejects_truncated_payload() {
        // Header claims 100 bytes but only 3 follow.
        let mut buf = vec![0u8; SV2_HEADER_LEN + 3];
        buf[3] = 100; // msg_length = 100 (LE)
        assert!(decode_message(&buf).is_err());
    }

    #[test]
    fn channel_message_has_flag_and_id() {
        let frame = encode_channel_message(0xdeadbeef, 0x13, b"abc").expect("encode");
        let (h, p) = decode_message(&frame).expect("decode");
        assert!(h.channel_msg());
        assert_eq!(h.msg_type, 0x13);
        assert_eq!(p.len(), 4 + 3);
        // First 4 bytes of payload = channel_id LE
        let cid = u32::from_le_bytes([p[0], p[1], p[2], p[3]]);
        assert_eq!(cid, 0xdeadbeef);
        // Remaining bytes = original payload
        assert_eq!(&p[4..], b"abc");
    }
}
