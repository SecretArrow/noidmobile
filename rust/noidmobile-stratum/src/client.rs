// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! `StratumV2Client` — synchronous, blocking TCP client that performs the
//! Noise_NX handshake with a peakpool server and exchanges SV2 frames.
//!
//! The client is **single-threaded** by design (snow's `HandshakeState` /
//! `TransportState` are `!Send` and `!Sync`). JNI callers must synchronize
//! access — typically by confining the client to a single miner thread.
//!
//! Wire format:
//! - Every Noise message (handshake OR transport) is preceded by a 2-byte
//!   **big-endian** length prefix (the standard Noise message-length
//!   convention used by SRI's reference SV2 implementation).
//! - After Noise transport is established, each Noise plaintext contains
//!   a full SV2 frame (6-byte header + payload), encoded with all
//!   multi-byte fields in **little-endian** per the SV2 spec.
//!
//! All public methods return `Result<_, ClientError>` and never panic.

use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::framing::{decode_message, encode_message};
use crate::messages::*;
use crate::noise::{NoiseHandshake, NoiseTransport};
use crate::{BlockTemplate, SubmitResult};

/// Max ciphertext we'll accept on the wire. The 2-byte length prefix
/// caps this at 65535, but we round to a power of two for the recv buffer.
const MAX_WIRE_LEN: usize = 1 << 16;

/// TCP connect timeout.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// Noise handshake read timeout (short — the handshake should complete
/// in well under a second on a healthy network).
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// Post-handshake read timeout. The JNI layer relies on this to break
/// out of `recv_template` periodically so it can check for stop signals.
const READ_TIMEOUT: Duration = Duration::from_secs(60);
/// Write timeout.
const WRITE_TIMEOUT: Duration = Duration::from_secs(30);

/// Errors surfaced by the client. All variants carry a human-readable
/// message; no underlying error types leak across the public API so the
/// C ABI can format them as plain strings.
#[derive(Debug)]
pub enum ClientError {
    /// `connect()` failed — bad address, DNS error, TCP refused, etc.
    Connect(String),
    /// Noise handshake failed — bad pattern, snow error, MITM detected.
    Handshake(String),
    /// Post-handshake protocol error — bad SV2 frame, unknown message
    /// type, payload parse failure, AEAD decrypt failure.
    Protocol(String),
    /// Underlying I/O error — read/write/timeout. The substring
    /// `"timed out"` is used by `recv_template` to detect the benign
    /// "no template yet" case.
    Io(String),
}

impl std::fmt::Display for ClientError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ClientError::Connect(m) => write!(f, "connect: {m}"),
            ClientError::Handshake(m) => write!(f, "handshake: {m}"),
            ClientError::Protocol(m) => write!(f, "protocol: {m}"),
            ClientError::Io(m) => write!(f, "io: {m}"),
        }
    }
}

impl std::error::Error for ClientError {}

/// Live Stratum V2 client connection.
pub struct StratumV2Client {
    stream: TcpStream,
    transport: NoiseTransport,
    /// Assigned by the server in `SetupConnection.Success`. Used as the
    /// `channel_id` prefix on subsequent channel-bound messages.
    channel_id: u32,
}

impl StratumV2Client {
    /// Connect to `host:port` and perform the Noise_NX handshake.
    ///
    /// `pool_pubkey_hex` is the pool's expected static public key as 64
    /// hex chars (32 bytes). Pass an empty string to skip the MITM check
    /// (NOT recommended for production — only safe for local dev pools).
    ///
    /// After `connect` returns, the connection is encrypted but no SV2
    /// messages have been exchanged. Call `setup()` next.
    pub fn connect(host: &str, port: u16, pool_pubkey_hex: &str) -> Result<Self, ClientError> {
        // Parse the pool's expected static pubkey (if provided)
        let expected_pubkey: Option<[u8; 32]> = if pool_pubkey_hex.is_empty() {
            None
        } else {
            let bytes = hex::decode(pool_pubkey_hex)
                .map_err(|e| ClientError::Connect(format!("invalid pool_pubkey_hex: {e}")))?;
            if bytes.len() != 32 {
                return Err(ClientError::Connect(format!(
                    "pool_pubkey_hex must be 32 bytes (64 hex chars), got {}",
                    bytes.len()
                )));
            }
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&bytes);
            Some(arr)
        };

        // TCP connect (with timeout — TcpStream::connect does NOT take a
        // timeout, so we resolve+iterate the addr list manually).
        let addr_str = format!("{host}:{port}");
        let addrs: Vec<_> = addr_str
            .to_socket_addrs()
            .map_err(|e| ClientError::Connect(format!("dns '{addr_str}': {e}")))?
            .collect();
        let mut stream: Option<TcpStream> = None;
        let mut last_err: Option<String> = None;
        for a in addrs {
            match TcpStream::connect_timeout(&a, CONNECT_TIMEOUT) {
                Ok(s) => {
                    stream = Some(s);
                    break;
                }
                Err(e) => {
                    last_err = Some(format!("tcp {a}: {e}"));
                }
            }
        }
        let mut stream = stream.ok_or_else(|| {
            ClientError::Connect(last_err.unwrap_or_else(|| "no addresses resolved".into()))
        })?;
        stream
            .set_read_timeout(Some(HANDSHAKE_TIMEOUT))
            .map_err(|e| ClientError::Io(format!("set_read_timeout (hs): {e}")))?;
        stream
            .set_write_timeout(Some(WRITE_TIMEOUT))
            .map_err(|e| ClientError::Io(format!("set_write_timeout: {e}")))?;
        // Disable Nagle's algorithm — SV2 messages are small and we want
        // low latency. The pool is unlikely to be Nagle-limited anyway.
        let _ = stream.set_nodelay(true);

        // Noise handshake
        let mut hs = NoiseHandshake::new_initiator(expected_pubkey.as_ref())?;

        // Step 1: write initiator's first message (just the ephemeral `e`)
        let msg1 = hs.write_first()?;
        write_len_prefixed(&mut stream, &msg1)?;

        // Step 2: read responder's message (e, ee, s, es)
        let msg2 = read_len_prefixed(&mut stream)?;
        hs.read_response(&msg2)?;

        // Transition to transport
        let transport = hs.into_transport()?;

        // Restore the longer read timeout for normal operation
        stream
            .set_read_timeout(Some(READ_TIMEOUT))
            .map_err(|e| ClientError::Io(format!("set_read_timeout (post-hs): {e}")))?;

        Ok(Self {
            stream,
            transport,
            channel_id: 0,
        })
    }

    /// Returns the channel_id assigned by the server (0 before `setup()`
    /// has succeeded).
    pub fn channel_id(&self) -> u32 {
        self.channel_id
    }

    /// Send `SetupConnection`, wait for `SetupConnection.Success` /
    /// `SetupConnection.Error`. On success, stores and returns the
    /// assigned `channel_id`.
    pub fn setup(&mut self) -> Result<u32, ClientError> {
        let payload = encode_setup_connection(
            "android-miner",
            "SecretArrow",
            "noidmobile-android",
            "0.5.0",
            "",
        )?;
        self.send_message(MSG_SETUP_CONNECTION, &payload)?;

        // Wait for response (Success or Error)
        let plaintext = self.recv_message_inner()?;
        let (header, payload) = decode_message(&plaintext)?;
        match header.msg_type {
            MSG_SETUP_CONNECTION_SUCCESS => {
                let channel_id = decode_setup_connection_success(payload)?;
                self.channel_id = channel_id;
                Ok(channel_id)
            }
            MSG_SETUP_CONNECTION_ERROR => {
                let reason = decode_setup_connection_error(payload)?;
                Err(ClientError::Protocol(format!("SetupConnection.Error: {reason}")))
            }
            other => Err(ClientError::Protocol(format!(
                "unexpected message type 0x{other:02x} (expected SetupConnection.Success=0x01 or .Error=0x02)"
            ))),
        }
    }

    /// Block until the pool sends a new mining template (`NewMiningJob`
    /// or `NewExtendedMiningJob`). Returns `Ok(None)` on read timeout
    /// (i.e. no template arrived within `READ_TIMEOUT`), so the caller
    /// can loop and check for stop signals. Returns `Ok(None)` also if
    /// the pool sends a non-template message that we ignore. Returns
    /// `Err` on connection error.
    pub fn recv_template(&mut self) -> Result<Option<BlockTemplate>, ClientError> {
        loop {
            let plaintext = match self.recv_message_inner() {
                Ok(p) => p,
                Err(ClientError::Io(ref msg)) if msg.contains("timed out") => {
                    return Ok(None);
                }
                Err(e) => return Err(e),
            };
            let (header, payload) = match decode_message(&plaintext) {
                Ok(v) => v,
                Err(e) => {
                    // Log and skip — keep waiting for a parseable frame.
                    log::warn!("recv_template: skipping bad frame: {e}");
                    continue;
                }
            };
            match header.msg_type {
                MSG_NEW_MINING_JOB | MSG_NEW_EXTENDED_MINING_JOB => {
                    match parse_block_template(payload) {
                        Ok(t) => return Ok(Some(t)),
                        Err(e) => {
                            log::warn!("recv_template: parse_block_template failed: {e}");
                            return Ok(None);
                        }
                    }
                }
                MSG_SETUP_CONNECTION_ERROR => {
                    let reason = decode_setup_connection_error(payload)?;
                    return Err(ClientError::Protocol(format!(
                        "SetupConnection.Error (during recv): {reason}"
                    )));
                }
                MSG_RECONNECT => {
                    log::info!("recv_template: server requested reconnect");
                    return Err(ClientError::Protocol("server requested reconnect".into()));
                }
                _ => {
                    log::debug!(
                        "recv_template: ignoring message type 0x{:02x} ({} bytes)",
                        header.msg_type,
                        payload.len()
                    );
                    // Loop and wait for the next message.
                }
            }
        }
    }

    /// Submit a found nonce. `nonce_le_hex` = 16-byte LE nonce as 32 hex
    /// chars (Parano1d convention). Returns:
    /// - `Ok(SubmitResult::Accepted(hash))` on success
    /// - `Ok(SubmitResult::Rejected(reason))` if the pool rejected
    /// - `Err(...)` on connection error
    ///
    /// While waiting for the response, any `NewMiningJob` messages are
    /// silently dropped (the caller is expected to call `recv_template`
    /// separately to discover new templates).
    pub fn submit_nonce(
        &mut self,
        template_id: &str,
        nonce_le_hex: &str,
    ) -> Result<SubmitResult, ClientError> {
        let payload = encode_submit_shares(self.channel_id, template_id, nonce_le_hex)?;
        self.send_message(MSG_SUBMIT_SHARES, &payload)?;

        loop {
            let plaintext = self.recv_message_inner()?;
            let (header, payload) = match decode_message(&plaintext) {
                Ok(v) => v,
                Err(e) => {
                    log::warn!("submit_nonce: skipping bad frame: {e}");
                    continue;
                }
            };
            match header.msg_type {
                MSG_SUBMIT_SHARES_SUCCESS | MSG_SUBMIT_SHARES_ERROR => {
                    return parse_submit_result(payload);
                }
                MSG_NEW_MINING_JOB | MSG_NEW_EXTENDED_MINING_JOB => {
                    log::debug!(
                        "submit_nonce: got NewMiningJob while waiting for submit result, ignoring"
                    );
                }
                _ => {
                    log::debug!(
                        "submit_nonce: ignoring message type 0x{:02x} ({} bytes)",
                        header.msg_type,
                        payload.len()
                    );
                }
            }
        }
    }

    /// Disconnect cleanly. Best-effort — shuts down the TCP socket and
    /// drops the transport state. After this call, the client should not
    /// be used again.
    pub fn disconnect(&mut self) {
        let _ = self.stream.shutdown(Shutdown::Both);
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /// Encrypt + send a single SV2 frame.
    fn send_message(&mut self, msg_type: u8, payload: &[u8]) -> Result<(), ClientError> {
        let frame = encode_message(msg_type, payload)?;
        let ciphertext = self.transport.encrypt(&frame)?;
        write_len_prefixed(&mut self.stream, &ciphertext)
    }

    /// Receive + decrypt a single SV2 frame's plaintext (header + payload).
    fn recv_message_inner(&mut self) -> Result<Vec<u8>, ClientError> {
        let ciphertext = read_len_prefixed(&mut self.stream)?;
        self.transport.decrypt(&ciphertext)
    }
}

impl Drop for StratumV2Client {
    fn drop(&mut self) {
        // Best-effort clean shutdown on drop. We don't surface errors
        // since Drop can't return them.
        let _ = self.stream.shutdown(Shutdown::Both);
    }
}

// ---------------------------------------------------------------------
// Wire framing helpers (2-byte BE length prefix per Noise convention)
// ---------------------------------------------------------------------

/// Write `data` to the stream with a 2-byte big-endian length prefix.
fn write_len_prefixed(stream: &mut TcpStream, data: &[u8]) -> Result<(), ClientError> {
    if data.len() > 0xFFFF {
        return Err(ClientError::Protocol(format!(
            "frame too large for u16 length prefix: {} > 65535",
            data.len()
        )));
    }
    let mut buf = Vec::with_capacity(2 + data.len());
    buf.push(((data.len() >> 8) & 0xff) as u8);
    buf.push((data.len() & 0xff) as u8);
    buf.extend_from_slice(data);
    stream
        .write_all(&buf)
        .map_err(|e| io_err("write", e))?;
    Ok(())
}

/// Read a 2-byte big-endian length prefix followed by that many bytes.
fn read_len_prefixed(stream: &mut TcpStream) -> Result<Vec<u8>, ClientError> {
    let mut len_buf = [0u8; 2];
    read_exact(stream, &mut len_buf)?;
    let len = ((len_buf[0] as usize) << 8) | (len_buf[1] as usize);
    if len == 0 {
        // A zero-length Noise message is not valid for SV2 (the smallest
        // valid transport ciphertext is 16 bytes — AEAD tag of an empty
        // plaintext). Treat as a protocol error.
        return Err(ClientError::Protocol(
            "received zero-length Noise message".into(),
        ));
    }
    if len > MAX_WIRE_LEN {
        return Err(ClientError::Protocol(format!(
            "wire frame too large: {len} > {MAX_WIRE_LEN}"
        )));
    }
    let mut data = vec![0u8; len];
    read_exact(stream, &mut data)?;
    Ok(data)
}

/// `read_exact` wrapper that classifies io::Error kinds correctly so
/// `recv_template` can detect timeouts.
fn read_exact(stream: &mut TcpStream, buf: &mut [u8]) -> Result<(), ClientError> {
    stream
        .read_exact(buf)
        .map_err(|e| io_err("read", e))?;
    Ok(())
}

/// Map an `io::Error` to a `ClientError::Io` with a stable message
/// format. Timeouts (`ErrorKind::TimedOut` / `WouldBlock`) produce a
/// message containing the substring `"timed out"` so `recv_template`
/// can detect them.
fn io_err(op: &str, e: std::io::Error) -> ClientError {
    let kind = e.kind();
    match kind {
        std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock => {
            ClientError::Io(format!("{op}: timed out"))
        }
        std::io::ErrorKind::UnexpectedEof => {
            ClientError::Io(format!("{op}: connection closed by peer"))
        }
        _ => ClientError::Io(format!("{op}: {e}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_error_display() {
        let e = ClientError::Connect("bad host".into());
        assert_eq!(format!("{e}"), "connect: bad host");
        let e = ClientError::Io("read: timed out".into());
        assert_eq!(format!("{e}"), "io: read: timed out");
    }

    #[test]
    fn io_err_classifies_timeout() {
        let e = io_err("read", std::io::Error::from(std::io::ErrorKind::TimedOut));
        match e {
            ClientError::Io(m) => assert!(m.contains("timed out")),
            _ => panic!("expected Io"),
        }
    }

    #[test]
    fn io_err_classifies_eof() {
        let e = io_err("read", std::io::Error::from(std::io::ErrorKind::UnexpectedEof));
        match e {
            ClientError::Io(m) => assert!(m.contains("connection closed")),
            _ => panic!("expected Io"),
        }
    }

    /// Round-trip a frame through the 2-byte length prefix codec by
    /// feeding bytes through a `std::io::Cursor` (which implements both
    /// `Read` and `Write`).
    #[test]
    fn len_prefixed_round_trip() {
        use std::io::Cursor;
        let data = b"hello world";
        let mut buf = Vec::new();
        // Write 2-byte BE length + data
        buf.push(((data.len() >> 8) & 0xff) as u8);
        buf.push((data.len() & 0xff) as u8);
        buf.extend_from_slice(data);
        let mut cursor = Cursor::new(buf);
        // TcpStream and Cursor both implement Read+Write, but our helpers
        // are typed to TcpStream. We re-implement the read inline to
        // sanity-check the format.
        let mut len_buf = [0u8; 2];
        cursor.read_exact(&mut len_buf).expect("read len");
        let len = ((len_buf[0] as usize) << 8) | (len_buf[1] as usize);
        assert_eq!(len, data.len());
        let mut got = vec![0u8; len];
        cursor.read_exact(&mut got).expect("read data");
        assert_eq!(&got, data);
    }

    /// Sanity check: SV2_MAX_PAYLOAD_LEN is consistent with the wire cap.
    #[test]
    fn max_payload_len_is_consistent() {
        // 2-byte BE length prefix caps the wire at 65535 bytes.
        // Ciphertext = plaintext + 16 (AEAD tag), plaintext = header (6) + payload.
        // So max payload = 65535 - 16 - 6 = 65513.
        assert_eq!(crate::framing::SV2_MAX_PAYLOAD_LEN, 65535 - 16 - 6);
    }
}
