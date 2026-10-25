# noidmobile-pow

Shared PoW engine for the [noidmobile](https://github.com/SecretArrow/noidmobile)
monorepo. Wraps the upstream `noid_poseidon2b` + `noid_core` crates from
<https://github.com/ignotusnemo/parano1d> and exposes two FFI surfaces:

- **WASM** (`#[cfg(target_arch = "wasm32")]`) — for the web miner, via `wasm-bindgen`.
- **C ABI** (otherwise) — for Android NDK / JNI in `libnoidmobile_pow.so`.

The hash logic mirrors the upstream `noid_extminer/src/main.rs` reference
miner: 16 × 16-byte LE `Block128` fields → set field 10 to the nonce →
Poseidon2b with `TAG_POWHDR` → 32-byte digest. Validity is `hash < target`
as 256-bit LE integers.

## Build

### Web (WASM)

```bash
rustup target add wasm32-unknown-unknown
cargo install wasm-pack --locked
cd rust/noidmobile-pow
wasm-pack build --target web --release
# Output: pkg/noidmobile_pow.js + noidmobile_pow_bg.wasm
```

The CI workflow `.github/workflows/build-wasm.yml` does this automatically
on push to `main` and commits the artifacts to `web/wasm/` for the web
miner to consume.

### Android (NDK)

```bash
rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
cd rust/noidmobile-pow
cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a \
    -o ../../android/app/src/main/jniLibs build --release
# Output: libnoidmobile_pow.so in each jniLibs/<abi>/ subdir
```

The CI workflow `.github/workflows/build-android.yml` automates this and
then assembles the APK via Gradle.

## API

### Rust

- `hash_pow(pow_fields_hex: &str, nonce_le_hex: &str) -> Result<[u8; 32], String>`
- `check_target(hash: &[u8; 32], target_le_hex: &str) -> bool`
- `validate_bech32m_o1(address: &str) -> bool`
- `derive_address_hex(secret_hex: &str) -> Result<String, String>` — best-effort.
- `batch_hash(pow_fields_hex: &str, nonce_start: u64, nonce_count: u32) -> Result<Vec<u8>, String>`

### WASM (TypeScript)

```typescript
import init, { wasm_hash_pow, wasm_check_target, wasm_validate_address, wasm_batch_hash }
  from '../wasm/noidmobile_pow.js';

await init();
const digest = wasm_hash_pow(powFieldsHex, nonceLeHex);     // 64-char hex
const ok     = wasm_check_target(digest, targetLeHex);       // boolean
const digests = wasm_batch_hash(powFieldsHex, 0n, 256);      // 256*64 hex chars
```

### C ABI (Android NDK)

```c
int32_t noidmobile_hash_pow(const char* pow_fields_hex, size_t pow_len,
                            const char* nonce_le_hex,  size_t nonce_len,
                            uint8_t out[32]);

int32_t noidmobile_check_target(const uint8_t hash[32], size_t hash_len,
                                const char* target_le_hex, size_t target_len);

int32_t noidmobile_validate_address(const char* addr, size_t addr_len);

int32_t noidmobile_batch_hash(const char* pow_fields_hex, size_t pow_len,
                              uint64_t nonce_start, uint32_t nonce_count,
                              uint8_t* out /* nonce_count * 32 bytes */);

int32_t noidmobile_le256_lt(const uint8_t a[32], const uint8_t b[32]);
```

## Tests

```bash
cargo test --manifest-path rust/noidmobile-pow/Cargo.toml
```

## License

Apache-2.0, same as the upstream Parano1d crates.
