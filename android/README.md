# noidmobile — Android app

Native Android miner + wallet for the [Parano1d (NOID)](https://parano1d.org)
network. Kotlin + Jetpack Compose + NDK + OpenCL, with the actual PoW
hashing done by the shared Rust crate [`noidmobile-pow`](../rust/).

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Kotlin UI (Jetpack Compose, Material 3, cyberpunk theme)   │
│   Dashboard │ Miner │ Wallet │ Settings                    │
└─────┬──────────────────────────────────────────────────────┘
      │
┌─────▼──────────────────────────────────────────────────────┐
│ Kotlin miner engine + RPC client + Stratum bridge client   │
│   MinerEngine  RpcClient  StratumClient  Vault             │
└─────┬──────────────────────────────────────────────────────┘
      │ JNI
┌─────▼──────────────────────────────────────────────────────┐
│ libnoid_jni.so  (src/main/cpp/noid_jni.cpp)                │
│   + opencl_miner.cpp  (GPU batch loader, CPU fallback)     │
└─────┬──────────────────────────────────────────────────────┘
      │ C ABI
┌─────▼──────────────────────────────────────────────────────┐
│ libnoidmobile_pow.so  (built from rust/noidmobile-pow)     │
│   Poseidon2b hash + target check + batch hash              │
└────────────────────────────────────────────────────────────┘
```

## Build

### Prerequisites

- Android Studio Iguana+ (or just the command-line SDK tools)
- Android SDK with **NDK r26d** (`26.3.11579264`) and **CMake 3.22.1**
- Rust toolchain with `cargo-ndk`
- The shared Rust crate must be built first (see below).

### 1. Build the shared Rust crate for Android ABIs

```bash
rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked

cd ../rust/noidmobile-pow
cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a \
    -o ../../android/app/src/main/jniLibs build --release
```

This drops `libnoidmobile_pow.so` into `app/src/main/jniLibs/<abi>/`.

### 2. Build the APK

```bash
cd ../android
cp local.properties.template local.properties   # then edit sdk.dir
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

For a debug build: `./gradlew assembleDebug`.

The CI workflow `.github/workflows/build-android.yml` automates both
steps and signs the APK when `secrets.ANDROID_KEYSTORE_BASE64` is set.

## Loading the wallet secret

The 256-bit spend secret is generated on-device with `SecureRandom` and
stored in `EncryptedSharedPreferences` (AES-GCM 256, master key in the
Android Keystore). It is **never** logged and **never** leaves the
device in plaintext — the wallet screen shows it once as a hex recovery
code so you can write it down.

The node RPC `paranoid_walletGetAddress` is the source of truth for the
derived address. The app is a **remote control**: it loads the wallet
secret into a local node (which the user runs separately), then drives
it over JSON-RPC.

## Switching solo / pool mode

- **SOLO** (default): the app talks directly to your local node via
  `paranoid_getBlockTemplate` / `paranoid_submitBlock`. No auth needed
  on `127.0.0.1`.
- **POOL**: the app talks to the Stratum V2 bridge mini-service
  (`../stratum/`) over WebSocket. The bridge handles the Noise handshake
  with the pool; the app only does Poseidon2b hashing.

Toggle the mode in the Miner screen. The setting persists in the
encrypted vault.

## OpenCL

The C++ side tries to create an OpenCL context on the GPU (Adreno /
Mali). If it succeeds, the batch hash is routed through the GPU kernel
in `src/main/cpp/opencl_miner.cl`. The shipped kernel is a **stub** —
it writes a sentinel that the C++ wrapper detects and falls back to the
CPU path in `libnoidmobile_pow.so`. A production GPU Poseidon2b kernel
is future work.

The CPU path is the default and **always works**. The app **never**
crashes if OpenCL is unavailable.

## Security notes

- Wallet secrets are stored in `EncryptedSharedPreferences` with a
  Keystore-backed master key.
- The mining key (Bearer token) is stored in the same encrypted vault.
- The app `android:allowBackup="false"` and `android:fullBackupContent="false"`.
- `network_security_config.xml` allows cleartext only to `127.0.0.1`,
  `localhost`, `10.0.2.2`, and RFC1918 private ranges. Public RPC
  endpoints must use TLS.
- No secret is ever written to logcat.
