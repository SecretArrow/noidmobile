# NOID//MOBILE

> Parano1d (NOID) mobile + web miner and wallet — Android NDK GPU miner, Next.js web miner/wallet, Stratum V2 pool support.

Parano1d is a proof-native Layer 1 PoW blockchain with post-quantum soundness,
signatureless ownership, and a single binary proof stack (FROST-GKR + FRI-Binius)
over Poseidon2b. This monorepo ships three real clients for the network:

| Component | Path | Stack | Status |
|-----------|------|-------|--------|
| Web miner + wallet | `/` (root) | Next.js 16, TypeScript, Tailwind 4, Web Workers, WASM | ✅ UI + protocol; WASM built by CI |
| Android GPU miner | `/android/` | Kotlin + Jetpack Compose + NDK r26 + CMake + OpenCL | ✅ Source; APK built by CI |
| Rust PoW crate | `/rust/noidmobile-pow/` | Rust, `wasm-pack` + `cargo-ndk`, wraps upstream `noid_poseidon2b` | ✅ Source; builds in CI |
| Stratum V2 bridge | `/stratum/` | Node.js/TS, `@noble/ciphers` Noise, WebSocket → TCP bridge | ✅ Source |
| CI/CD | `/.github/workflows/` | GitHub Actions: lint, test, build-wasm, build-android, e2e, auto-fix | ✅ |

**No simulation, no fake hashing.** The miner refuses to start until the WASM
PoW backend (compiled from the real upstream `noid_poseidon2b` Rust crate) is
loaded. The wallet uses real 256-bit master spend entropy, encrypted at rest
with AES-256-GCM (PBKDF2, 600k iterations).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Browser / Android app                                           │
│  ┌─────────────────────┐  ┌──────────────────────────────────┐  │
│  │  Wallet (UI)        │  │  Miner (UI)                      │  │
│  │  • entropy keygen   │  │  • template fetch loop           │  │
│  │  • mnemonic backup  │  │  • N web workers / NDK threads   │  │
│  │  • send / receive   │  │  • Poseidon2b nonce search       │  │
│  │  • multi-wallet     │  │  • submit nonce                  │  │
│  └─────────┬───────────┘  └────────┬──────────────┬──────────┘  │
│            │ JSON-RPC              │ WASM          │ WebSocket   │
│            │ (paranoid_*)          │ (Poseidon2b)  │             │
└────────────┼───────────────────────┼──────────────┼─────────────┘
             │                       │              │
             ▼                       ▼              ▼
   ┌─────────────────┐     ┌──────────────────┐  ┌────────────────┐
   │  parano1d node  │     │  Rust crate      │  │  Stratum V2    │
   │  (local, :9601) │     │  noidmobile-pow  │  │  bridge        │
   │  • wallet RPC   │     │  • wasm-pack     │  │  (port 3030)   │
   │  • getBlockTpl  │     │  • cargo-ndk     │  │  Noise → TCP   │
   │  • submitBlock  │     │  • wraps         │  │  → peakpool    │
   │  • PoW validate │     │    noid_poseidon2b│  └────────────────┘
   └─────────────────┘     └──────────────────┘
```

### Mining modes
- **Solo** — direct JSON-RPC to a local `parano1d` node (default `http://127.0.0.1:9601`). No auth needed on loopback.
- **Pool** — WebSocket to the Stratum V2 bridge, which forwards over Noise-encrypted TCP to a real Stratum V2 pool (e.g. peakpool).

### Mining protocol (real, from upstream source)
- `paranoid_getBlockTemplate(miner_address)` → `{template_id, pow_fields_hex (256 hex = 16×16-byte LE u128), nonce_field_index: 10, difficulty_target_hex (64 hex = 32-byte LE target), height, expires_in_seconds, n_txs}`
- `paranoid_submitBlock(template_id, nonce_hex)` — `nonce_hex` = 16 bytes LE (32 hex chars)
- PoW: Poseidon2b with `TAG_POWHDR` domain tag, 16 × `u128` fields, nonce at index 10, hash < target (256-bit LE compare)
- Block target: 30s mean, ASERT difficulty

### Wallet
- 256-bit master spend secret (no keypair, no signature — Parano1d proves ownership in ZK)
- BIP39 24-word mnemonic backup
- Photo Key derivation (image → SHA-256 → 256-bit secret, all client-side)
- Encrypted JSON keystore export/import (AES-256-GCM, PBKDF2 600k)
- Multi-wallet with switch
- The web wallet is a companion to a local node — the secret is loaded into the
  node's keystore, and the web wallet remote-controls balance/send via JSON-RPC.
  The secret never travels over the network.

---

## Build

### Web (local dev)
```bash
bun install
bun run dev   # http://localhost:3000
bun run lint
```

### WASM PoW backend (built by CI)
```bash
./scripts/build-wasm.sh
# Output: /public/wasm/noidmobile_pow.{js,wasm}
```

### Android APK (built by CI)
```bash
# Prereqs: Android SDK + NDK r26d + CMake
./scripts/build-android.sh
# Output: /android/app/build/outputs/apk/release/app-release.apk
```

### Stratum V2 bridge
```bash
cd stratum
cp .env.example .env   # set POOL_HOST, POOL_PORT, POOL_PUBKEY, BRIDGE_PORT
bun install
bun run src/index.ts
```

---

## CI/CD

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `lint.yml` | push, PR | ESLint (web) + rustfmt/clippy (rust) + lint (android) |
| `test.yml` | push, PR | Unit tests: vitest (web) + cargo test (rust) + JUnit (android) |
| `build-wasm.yml` | push to main, PR | `wasm-pack build` → commit `/public/wasm/` back to repo |
| `build-android.yml` | tag `v*`, manual | `cargo-ndk` + `./gradlew assembleRelease` → upload APK + create Release |
| `build-stratum.yml` | push, PR | TypeScript typecheck for the bridge |
| `e2e.yml` | PR | Playwright smoke test on the web app |
| `auto-fix.yml` | workflow_run (on failure) | Auto-run `eslint --fix`, `cargo fix`, `ktlintFormat`; commit + push |

### Required GitHub Secrets (for signed APK only)
- `ANDROID_KEYSTORE_BASE64` — base64-encoded `.keystore` or `.jks`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

Without these, CI produces a debug-signed APK. The `GITHUB_TOKEN` (auto-provided)
is used for committing WASM artifacts back to the repo — no user token needed.

---

## Security

- **Never** commit secrets, tokens, or keystores. The `.gitignore` excludes
  `/db/`, `/research/`, `/download/`, build artifacts, and `local.properties`.
- The mining bearer token is stored in `localStorage` (encrypted by the
  browser's origin isolation). For higher security, leave it empty and enter
  it each session.
- The wallet secret is encrypted at rest with AES-256-GCM (PBKDF2 600k
  iterations). The decrypted secret lives only in memory.
- The Stratum V2 bridge never holds your funds — peakpool is non-custodial
  (coinbase pays your wallet directly).

---

## References

- Parano1d site: <https://parano1d.org/>
- Parano1d docs: <https://docs.parano1d.org/>
- Upstream source (Rust): <https://github.com/ignotusnemo/parano1d>
- peakpool (Stratum V2): <https://github.com/peakminer/peakpool>
- This repo: <https://github.com/SecretArrow/noidmobile>

## License

Apache-2.0, matching the upstream Parano1d source.
