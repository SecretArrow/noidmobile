# noidmobile-pow

Shared Rust PoW crate for the noidmobile monorepo.

This directory contains a single workspace member, `noidmobile-pow/`,
which wraps the upstream [ignotusnemo/parano1d](https://github.com/ignotusnemo/parano1d)
crates `noid_poseidon2b` + `noid_core` and exposes two FFI surfaces:

- **WASM** for the web miner (`wasm-pack build --target web --release`)
- **C ABI / `.so`** for the Android NDK (`cargo ndk ... build --release`)

The hash logic mirrors `noid_extminer/src/main.rs` exactly: 16 × 16-byte
LE `Block128` fields, nonce at field index 10, Poseidon2b with
`TAG_POWHDR`, 32-byte digest, validity check via `le256_lt(hash, target)`.

See `noidmobile-pow/README.md` for build commands and the full API.
