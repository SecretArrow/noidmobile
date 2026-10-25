# noidmobile — CI/CD

This directory contains the GitHub Actions workflows and the Dependabot
config for the noidmobile monorepo.

## Workflows

| File                | Trigger                          | What it does                                                                       |
| ------------------- | -------------------------------- | ---------------------------------------------------------------------------------- |
| `lint.yml`          | push to main, pull_request       | Web ESLint, Rust `cargo fmt` + `cargo clippy`, Android `:app:lintDebug`, stratum `tsc --noEmit` |
| `test.yml`          | push to main, pull_request       | Web tests (if configured), `cargo test`, `:app:testDebugUnitTest`, stratum tests (if configured) |
| `build-wasm.yml`    | push to main, PR, workflow_dispatch | `wasm-pack build --target web --release`, commits `web/wasm/*` back to main, uploads `pkg/` as an artifact |
| `build-android.yml` | push to main, tags `v*`, PR, workflow_dispatch | Builds Rust crate via `cargo-ndk` for arm64-v8a/x86_64/armeabi-v7a, then `assembleRelease bundleRelease`, signs APK if keystore secret is set, creates a GitHub Release on tag push |
| `build-stratum.yml` | push to main, PR (stratum/**)    | Stratum bridge `tsc --noEmit` typecheck                                            |
| `e2e.yml`           | pull_request, workflow_dispatch  | Playwright smoke test that visits `/` and asserts the title contains "NOID"       |
| `auto-fix.yml`      | workflow_run on lint/test failures | Runs `eslint --fix`, `cargo fix`, attempts to commit fixes; opens an issue on failure |

## Required secrets

Set these in **Settings → Secrets and variables → Actions**.

| Secret                          | Used by             | Required for                              |
| ------------------------------- | ------------------- | ----------------------------------------- |
| `ANDROID_KEYSTORE_BASE64`       | `build-android.yml` | Signing the release APK (base64-encoded `.jks` / `.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD`     | `build-android.yml` | Keystore password                         |
| `ANDROID_KEY_ALIAS`             | `build-android.yml` | Signing key alias                         |
| `ANDROID_KEY_PASSWORD`          | `build-android.yml` | Signing key password                      |

No other secrets are needed. `GITHUB_TOKEN` is auto-provided by GitHub
Actions and is used for committing WASM artifacts back to the repo,
opening auto-fix issues, and creating GitHub Releases.

To create a signing keystore locally and base64-encode it:

```bash
keytool -genkeypair -v -keystore noidmobile.keystore -alias noidmobile \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 noidmobile.keystore  # paste this into ANDROID_KEYSTORE_BASE64
```

## Workflow concurrency

All workflows use `concurrency: { group: <name>-${{ github.ref }}, cancel-in-progress: true }`
so pushing to an open PR cancels the previous run.

## Caching

- npm caches via `actions/setup-node@v4` `cache: 'npm'`
- Rust caches via `Swatinem/rust-cache@v2`
- Gradle caches via `gradle/actions/setup-gradle@v4`
- Android SDK + NDK installed via `android-actions/setup-android@v3`
