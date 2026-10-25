// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! `noidmobile_stratum` — Stratum V2 client (Noise_NX) for connecting
//! Android miners directly to a peakpool Stratum V2 server.
//!
//! See `README.md` for the high-level overview. The public surface is:
//!
//! - [`StratumV2Client`] — synchronous TCP client performing the Noise
//!   handshake + SV2 message exchange.
//! - [`BlockTemplate`] — Parano1d mining template (matches the field
//!   names of the JSON-RPC `paranoid_getBlockTemplate` response).
//! - [`SubmitResult`] — accepted/rejected response from `submit_nonce`.
//! - [`ClientError`] — error type returned by all client methods.
//!
//! The C ABI (for JNI consumption from the Android app) lives in the
//! private `cffi` module and is exported via `#[no_mangle] extern "C"`
//! functions prefixed with `noidmobile_stratum_`.

pub mod client;
pub mod framing;
pub mod messages;
pub mod noise;
mod cffi;

pub use client::{ClientError, StratumV2Client};
pub use messages::{BlockTemplate, SubmitResult};
