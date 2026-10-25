# noidmobile-stratum

Stratum V2 client (Rust) for connecting Android miners **directly** to a
[peakpool](https://github.com/SecretArrow/noidmobile) Stratum V2 server,
bypassing the optional WebSocket bridge.

## What it does

- TCP connect to a peakpool server
- Performs the **Noise_NX_25519_ChaChaPoly_BLAKE2s** handshake (the SV2-standard
  Noise pattern — server authenticates via static key, client is anonymous)
- Sends/receives Stratum V2 binary messages per the
  [SV2 spec](https://stratumprotocol.org/specification/03-Protocol-Basics/)
- Exposes a small C ABI for JNI consumption from the Android app

The crate wraps the [`snow`](https://crates.io/crates/snow) Noise library for
the handshake + ChaChaPoly transport, and ships hand-rolled encoders/decoders
for the small subset of SV2 messages a non-custodial solo miner needs
(`SetupConnection`, `NewMiningJob`, `SubmitShares`).

## Parano1d-specific adaptation

peakpool is **proprietary** — its exact `NewMiningJob` / `SubmitShares.Success`
payload schemas are not published. To remain useful despite that, the crate
parses every incoming payload **JSON-first**, then falls back to the standard
SV2 binary layout if JSON fails. peakpool is observed to embed a Parano1d-style
JSON blob inside the SV2 framing for fields that have no SV2 equivalent
(`pow_fields_hex`, `nonce_field_index`, `difficulty_target_hex`, `template_id`,
`height`, `expires_in_seconds`, `n_txs`).

The Rust `BlockTemplate` struct matches the field names of the Parano1d
JSON-RPC `paranoid_getBlockTemplate` response exactly, so peakpool's JSON (if
it follows the same shape) will round-trip cleanly.

## Build

```sh
cargo build --release
```

For Android cross-compilation, CI uses `cargo ndk` (same as the sibling
`noidmobile-pow` crate). This crate is intentionally **not** added to the
root Cargo workspace — CI builds it standalone.

## Public Rust API

```rust
use noidmobile_stratum::{StratumV2Client, ClientError, BlockTemplate, SubmitResult};

let mut client = StratumV2Client::connect("pool.example.com", 34254, "")?;
let channel_id = client.setup()?;
let template: BlockTemplate = client.recv_template()?.expect("template");
let result: SubmitResult = client.submit_nonce(&template.template_id, "ff...00")?;
client.disconnect();
```

See `src/lib.rs` for the full type surface.

## C ABI (for JNI)

All functions use length-prefixed strings (pointer + length) and
caller-allocated output buffers. The opaque client handle is a
`*mut StratumV2Client` returned by `_connect` and freed by `_disconnect`.
Callers MUST synchronize access from a single thread.

| Function | Returns |
|----------|---------|
| `noidmobile_stratum_connect(host, host_len, port, pubkey, pubkey_len)` | opaque handle (null on error) |
| `noidmobile_stratum_setup(client)` | `0` on success, `-1` on error |
| `noidmobile_stratum_recv_template(client, out, out_len)` | string length on success, `0` on no template yet, `-1` on error |
| `noidmobile_stratum_submit(client, tpl_id, tpl_len, nonce, nonce_len, out, out_len)` | `1` accepted, `0` rejected, `-1` error |
| `noidmobile_stratum_disconnect(client)` | (void) frees the handle |

Output buffers receive a **null-terminated** UTF-8 string. The return value is
the string length in bytes (excluding the null terminator).

## Limitations

- **No live pool testing** — peakpool is proprietary and the crate has not been
  exercised against a real running instance. The Noise handshake + SV2 framing
  follow the public spec; the JSON-vs-binary payload fallback is the
  flexibility layer.
- **Synchronous / blocking** — the client uses `std::net::TcpStream` with read
  timeouts. There is no async runtime dependency. JNI callers should run
  `recv_template` on a dedicated miner thread.
- **Single-threaded** — the client is `!Sync` and `!Send` (snow state is
  `!Send`). JNI callers must synchronize.
- **Noise_NX only** — no PSK, no IK fallback. Server auth is via the static
  key the server sends during handshake; the client verifies it against the
  `pool_pubkey_hex` if provided.
- **MITM protection is opt-in** — pass an empty string to skip the static-key
  check (NOT recommended for production).

## License

Apache-2.0, same as the rest of the noidmobile monorepo.
