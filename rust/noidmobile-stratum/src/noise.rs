// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! Noise_NX_25519_ChaChaPoly_BLAKE2s handshake wrapper around the `snow`
//! crate, scoped to exactly what the Stratum V2 client needs:
//!
//! 1. `NoiseHandshake::new_initiator(...)` — build the client side of an NX
//!    handshake. The optional `expected_remote_pubkey` is the pool's static
//!    public key (32 bytes) used for MITM protection.
//! 2. `write_first()` — produces the initiator's first message (`e`). For
//!    Noise_NX this is exactly the 32-byte ephemeral public key (no payload,
//!    no AEAD tag because no DH has happened yet).
//! 3. `read_response(msg)` — consumes the responder's message
//!    (`e, ee, s, es`). After this call, the server's static key is
//!    available via `get_remote_static_key()` and verified against the
//!    expected key (if provided).
//! 4. `into_transport()` — finalize and transition to `NoiseTransport`,
//!    which exposes `encrypt()` / `decrypt()` for SV2 frames.
//!
//! All errors are mapped to `ClientError::Handshake` so the client layer can
//! surface them uniformly. This module never panics.

use snow::params::NoiseParams;
use snow::{Builder, HandshakeState, TransportState};

use crate::ClientError;

/// The Noise pattern name. Stratum V2 uses Noise_NX for the standard
/// mining protocol — the server authenticates via its static key, the
/// client remains anonymous.
const NOISE_PATTERN: &str = "Noise_NX_25519_ChaChaPoly_BLAKE2s";

/// Maximum plaintext/ciphertext we ever hand to snow. Generous; the real
/// cap is the 2-byte length prefix on the wire (= 65535 bytes ciphertext).
const NOISE_BUF_LEN: usize = 1 << 16;

/// Live Noise handshake state.
pub struct NoiseHandshake {
    state: HandshakeState,
    /// Expected remote static public key (32 bytes). `None` skips the
    /// MITM check (NOT recommended for production).
    expected_remote: Option<[u8; 32]>,
}

/// Live Noise transport state (post-handshake).
pub struct NoiseTransport {
    state: TransportState,
}

impl NoiseHandshake {
    /// Build the initiator side of a Noise_NX handshake.
    ///
    /// NX does NOT require the client to have a static key — snow generates
    /// the ephemeral keypair internally on the first `write_message`.
    pub fn new_initiator(
        expected_remote_pubkey: Option<&[u8; 32]>,
    ) -> Result<Self, ClientError> {
        let params: NoiseParams = NOISE_PATTERN.parse().map_err(|e| {
            ClientError::Handshake(format!("invalid noise pattern '{NOISE_PATTERN}': {e}"))
        })?;
        let builder = Builder::new(params);
        let state = builder
            .build_initiator()
            .map_err(|e| ClientError::Handshake(format!("snow build_initiator: {e}")))?;
        Ok(Self {
            state,
            expected_remote: expected_remote_pubkey.copied(),
        })
    }

    /// Write the initiator's first Noise message. For NX this is the bare
    /// ephemeral public key (32 bytes). The returned Vec is what goes on
    /// the wire (after the 2-byte length prefix added by the framing layer).
    pub fn write_first(&mut self) -> Result<Vec<u8>, ClientError> {
        let mut out = vec![0u8; NOISE_BUF_LEN];
        let n = self
            .state
            .write_message(&[], &mut out)
            .map_err(|e| ClientError::Handshake(format!("snow write_message (msg 1): {e}")))?;
        out.truncate(n);
        Ok(out)
    }

    /// Consume the responder's Noise message (`e, ee, s, es` for NX).
    /// After this call the handshake is complete and `into_transport` can
    /// be called. If `expected_remote_pubkey` was provided, the server's
    /// static key is verified against it here — any mismatch is treated as
    /// a man-in-the-middle attack and aborts the handshake.
    pub fn read_response(&mut self, msg: &[u8]) -> Result<(), ClientError> {
        let mut out = vec![0u8; NOISE_BUF_LEN];
        self.state
            .read_message(msg, &mut out)
            .map_err(|e| ClientError::Handshake(format!("snow read_message (msg 2): {e}")))?;

        if let Some(expected) = self.expected_remote.as_ref() {
            let remote = self.state.get_remote_static().ok_or_else(|| {
                ClientError::Handshake(
                    "server did not transmit a static key (NX invariant violated)".into(),
                )
            })?;
            // Constant-time comparison would be ideal here, but for 32-byte
            // pubkeys a simple equality check is acceptable — the key is
            // public, not secret, so a timing leak reveals nothing useful.
            if remote != expected.as_slice() {
                return Err(ClientError::Handshake(format!(
                    "server static key mismatch (possible MITM): expected {}, got {}",
                    hex::encode(expected),
                    hex::encode(remote)
                )));
            }
        }

        Ok(())
    }

    /// Transition to transport mode. Consumes `self`.
    pub fn into_transport(self) -> Result<NoiseTransport, ClientError> {
        // Sanity-check: for NX the handshake IS finished after one round
        // trip. If it isn't, snow will refuse to enter transport mode and
        // we surface the error.
        let state = self
            .state
            .into_transport_mode()
            .map_err(|e| ClientError::Handshake(format!("snow into_transport_mode: {e}")))?;
        Ok(NoiseTransport { state })
    }
}

impl NoiseTransport {
    /// Encrypt a plaintext SV2 frame (header + payload) into a ciphertext
    /// suitable for writing on the wire. Each call increments the
    /// ChaChaPoly nonce counter internally (snow manages this).
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, ClientError> {
        // Output buffer must hold plaintext + 16-byte AEAD tag.
        let mut out = vec![0u8; plaintext.len() + 16];
        let n = self
            .state
            .write_message(plaintext, &mut out)
            .map_err(|e| ClientError::Protocol(format!("noise encrypt (write_message): {e}")))?;
        out.truncate(n);
        Ok(out)
    }

    /// Decrypt a ciphertext into the original plaintext SV2 frame. Each
    /// call increments the recv nonce counter internally.
    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, ClientError> {
        // Output buffer: ciphertext.len() - 16 (tag) is the plaintext upper
        // bound; allocating ciphertext.len() is always safe.
        let mut out = vec![0u8; ciphertext.len()];
        let n = self
            .state
            .read_message(ciphertext, &mut out)
            .map_err(|e| ClientError::Protocol(format!("noise decrypt (read_message): {e}")))?;
        out.truncate(n);
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// End-to-end Noise_NX handshake between a snow responder (with a
    /// freshly generated static key) and our `NoiseHandshake` initiator.
    /// Verifies that the derived transport keys encrypt/decrypt correctly
    /// in both directions.
    #[test]
    fn nx_handshake_round_trip() {
        let params: NoiseParams = NOISE_PATTERN.parse().expect("pattern parses");

        // Generate a static keypair for the responder.
        let kp = Builder::new(params.clone())
            .generate_keypair()
            .expect("generate_keypair");
        assert_eq!(kp.public.len(), 32, "X25519 pubkey is 32 bytes");
        assert_eq!(kp.private.len(), 32, "X25519 privkey is 32 bytes");

        let mut responder = Builder::new(params)
            .local_private_key(&kp.private)
            .expect("local_private_key")
            .build_responder()
            .expect("responder builds");

        // Initiator knows the responder's pubkey upfront (MITM check).
        let mut expected = [0u8; 32];
        expected.copy_from_slice(&kp.public);
        let mut init =
            NoiseHandshake::new_initiator(Some(&expected)).expect("initiator builds");

        // Initiator -> responder: msg 1 (e). 32 bytes, no AEAD tag.
        let msg1 = init.write_first().expect("write_first");
        assert_eq!(msg1.len(), 32, "NX msg1 is exactly the ephemeral pubkey");

        let mut rsp_out = vec![0u8; NOISE_BUF_LEN];
        let n1 = responder
            .read_message(&msg1, &mut rsp_out)
            .expect("responder reads msg1");
        assert_eq!(n1, 0, "msg1 carries no encrypted payload");

        // Responder -> initiator: msg 2 (e, ee, s, es). 96 bytes for NX
        // when snow appends the empty-payload AEAD tag at the end.
        let mut msg2_buf = vec![0u8; NOISE_BUF_LEN];
        let n2 = responder
            .write_message(&[], &mut msg2_buf)
            .expect("responder writes msg2");
        let msg2 = msg2_buf[..n2].to_vec();

        init.read_response(&msg2).expect("init reads msg2");
        // NX msg2 layout: 32 (e) + 48 (encrypted static s + AEAD tag) +
        // 16 (AEAD tag for the empty payload that snow appends at the end
        // of every write_message call once a cipher key is established).
        // Total = 96 bytes.
        assert_eq!(n2, 96, "NX msg2 = e + enc(s) + payload_tag");
        let mut init_t = init.into_transport().expect("init -> transport");
        let mut rsp_t = responder
            .into_transport_mode()
            .expect("rsp -> transport");

        // Encrypt + decrypt in the initiator -> responder direction.
        let test_msg = b"hello peakpool";
        let ct = init_t.encrypt(test_msg).expect("init encrypt");
        let mut pt_buf = vec![0u8; NOISE_BUF_LEN];
        let pn = rsp_t
            .read_message(&ct, &mut pt_buf)
            .expect("rsp decrypts");
        assert_eq!(&pt_buf[..pn], test_msg);

        // Encrypt + decrypt in the responder -> initiator direction.
        let test_msg2 = b"welcome miner";
        let mut ct2_buf = vec![0u8; test_msg2.len() + 16];
        let cn = rsp_t
            .write_message(test_msg2, &mut ct2_buf)
            .expect("rsp encrypt");
        let ct2 = ct2_buf[..cn].to_vec();
        let pt2 = init_t.decrypt(&ct2).expect("init decrypts");
        assert_eq!(&pt2[..], test_msg2);
    }
}
