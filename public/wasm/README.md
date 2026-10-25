# /public/wasm/

This directory holds the WASM PoW backend, built from the Rust crate at
`/rust/noidmobile-pow/` by the GitHub Actions workflow
`.github/workflows/build-wasm.yml`.

## Build

The WASM is rebuilt automatically on every push to `main` and committed back
to this directory by the CI bot. To build locally:

```bash
./scripts/build-wasm.sh
```

This produces:
- `noidmobile_pow.js` — the wasm-bindgen JS glue
- `noidmobile_pow_bg.wasm` — the compiled WASM binary

## Without WASM

The web miner refuses to start if the WASM backend is missing. The wallet
features (entropy keygen, balance queries, sends) work without WASM because
the actual Poseidon2b proofs happen on the local `parano1d` node — the web
wallet is only a remote control + secret manager.

We deliberately do NOT ship a hand-rolled TypeScript Poseidon2b. The upstream
parameters (round constants, MDS matrix, S-box, domain tag layout) are
specific to Parano1d; any divergence would produce invalid hashes and waste
CPU forever. A wrong hash can never submit a valid block.
